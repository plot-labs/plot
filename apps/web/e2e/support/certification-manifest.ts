import { createHash } from "node:crypto";
import { closeSync, constants, mkdirSync, openSync, readFileSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";

import {
  ARTIFACT_SCHEMA_VERSION,
  parseEvidenceEnvelope,
  sealCampaignManifest,
  sealModelExecutionManifest,
  type EvidenceEnvelope,
  type EvidenceMetrics,
  type SealedArtifact,
  type CampaignManifest,
  type ModelExecutionManifest,
} from "./certification-artifact";

const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const ATTEMPT_ID = /^attempt-[a-f0-9]{16,64}$/;
const HASH = /^sha256:[a-f0-9]{64}$/;
const SCENARIO_ID = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const SECRET_ENV_KEYS = [
  "OPENROUTER_API_KEY",
  "OPENAI_API_KEY",
  "GITHUB_TOKEN",
  "GH_TOKEN",
  "GITHUB_APP_PRIVATE_KEY",
  "GITHUB_INSTALLATION_TOKEN",
  "GITHUB_WEBHOOK_SECRET",
  "PLOT_GITHUB_PRIVATE_KEY",
  "PLOT_GITHUB_STATE_SECRET",
  "SPRING_AI_OPENAI_API_KEY",
	"SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL",
	"SPRING_DATASOURCE_URL",
	"SPRING_DATASOURCE_USERNAME",
	"SPRING_DATASOURCE_PASSWORD",
	"PLOT_CERTIFICATION_DATABASE_URL",
	"PLOT_CERTIFICATION_DATABASE_USERNAME",
	"PLOT_CERTIFICATION_DATABASE_PASSWORD",
	"PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN",
	"PLOT_CERTIFICATION_DATABASE_FINGERPRINT",
] as const;
const MAX_MANIFEST_BYTES = 128 * 1024;
const BROWSER_OBSERVATION_CODE_VALUES = [
  "BROWSER_CONTRACT_OBSERVED",
  "CITATION_POPOVER_OBSERVED",
  "EVIDENCE_FREE_SENTENCE_OBSERVED",
  "EXPORT_CONFIRMATION_OBSERVED",
  "HUMAN_DECISION_OBSERVED",
  "MARKDOWN_SAFETY_OBSERVED",
  "PENDING_AUDIT_RECONCILIATION",
  "REAL_GITHUB_BLOCKS_OBSERVED",
  "STALE_EDIT_OBSERVED",
  "BROWSER_CONTRACT_FAILED",
  "BROWSER_INFRASTRUCTURE_INCONCLUSIVE",
] as const;
const BROWSER_OBSERVATION_CODES = new Set<BrowserObservationCode>(BROWSER_OBSERVATION_CODE_VALUES);

export type CertificationMode = "real-source" | "synthetic";

export type BrowserCertificationConfig = Readonly<{
  mode: CertificationMode;
  baseUrl: string;
  outputRoot: string;
  campaign: SealedArtifact<CampaignManifest>;
  execution: SealedArtifact<ModelExecutionManifest>;
  attemptId: string;
  scenarioId: string;
  ordinal: number;
  writingBlockIds: readonly string[];
  idempotencyKey: string;
}>;

export type BrowserObservationCode = (typeof BROWSER_OBSERVATION_CODE_VALUES)[number];

export class BrowserCertificationError extends Error {
  constructor(code: string) {
    super(code);
    this.name = "BrowserCertificationError";
  }
}

export function browserFailureOutcome(input: Readonly<{
  error: unknown;
  productContractStarted: boolean;
  externalRequestObserved: boolean;
}>): "HARD_GATE_FAIL" | "INCONCLUSIVE" {
	if (input.externalRequestObserved || input.error instanceof BrowserCertificationError) return "HARD_GATE_FAIL";
	if (browserInfrastructureFailure(input.error)) return "INCONCLUSIVE";
	return input.productContractStarted ? "HARD_GATE_FAIL" : "INCONCLUSIVE";
}

function browserInfrastructureFailure(error: unknown): boolean {
	const message = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
	return /(?:browser|context|target|page) (?:has been )?closed|target closed|browser disconnected|worker process exited|net::ERR_|ECONN(?:RESET|REFUSED)|socket hang up/i.test(message);
}

export function loadBrowserCertificationConfig(
  environment: Readonly<Record<string, string | undefined>> = process.env,
): BrowserCertificationConfig {
  rejectInheritedSecrets(environment);
  const mode = required(environment, "PLOT_CERTIFICATION_MODE");
  if (mode !== "real-source" && mode !== "synthetic") reject("CERTIFICATION_MODE_REJECTED");

  const baseUrl = parseLoopbackBaseUrl(required(environment, "PLOT_CERTIFICATION_BASE_URL"));
  const outputRoot = absoluteDirectory(required(environment, "PLOT_CERTIFICATION_OUTPUT_ROOT"));
  const campaign = sealCampaignManifest(readJsonArtifact(required(environment, "PLOT_CERTIFICATION_CAMPAIGN_MANIFEST")));
  requireHash(environment, "PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH", campaign.hash);
  const execution = sealModelExecutionManifest(
    readJsonArtifact(required(environment, "PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST")),
    campaign,
  );
  requireHash(environment, "PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH", execution.hash);

  const attemptId = required(environment, "PLOT_CERTIFICATION_ATTEMPT_ID");
  if (!ATTEMPT_ID.test(attemptId)) reject("CERTIFICATION_ATTEMPT_REJECTED");
  const scenarioId = required(environment, "PLOT_CERTIFICATION_SCENARIO_ID");
  if (!SCENARIO_ID.test(scenarioId) || !execution.artifact.scenarioIds.includes(scenarioId)) {
    reject("CERTIFICATION_SCENARIO_REJECTED");
  }
  if (mode === "real-source" && scenarioId !== "real-github-journey") reject("CERTIFICATION_SCENARIO_REJECTED");
  const ordinal = Number(required(environment, "PLOT_CERTIFICATION_ATTEMPT_ORDINAL"));
  if (!Number.isSafeInteger(ordinal) || ordinal < 1 || ordinal > 3) reject("CERTIFICATION_ORDINAL_REJECTED");
  const writingBlockIds = uniqueWritingBlockIds(required(environment, "PLOT_CERTIFICATION_WRITING_BLOCK_IDS"));

  return Object.freeze({
    mode,
    baseUrl,
    outputRoot,
    campaign,
    execution,
    attemptId,
    scenarioId,
    ordinal,
    writingBlockIds,
    idempotencyKey: `${execution.artifact.idempotencyNamespace}:${attemptId}`,
  });
}

export function writeBrowserObservation(
  config: BrowserCertificationConfig,
  input: Readonly<{
    outcome: "INCONCLUSIVE" | "HARD_GATE_FAIL";
    metrics: EvidenceMetrics;
    codes: readonly BrowserObservationCode[];
    recordedAt?: string;
  }>,
): { envelope: EvidenceEnvelope; path: string } {
  const codeSet = new Set(input.codes);
  const pendingReconciliation = input.outcome === "INCONCLUSIVE" &&
    codeSet.has("BROWSER_CONTRACT_OBSERVED") &&
    codeSet.has("PENDING_AUDIT_RECONCILIATION") &&
    !codeSet.has("BROWSER_CONTRACT_FAILED") &&
    !codeSet.has("BROWSER_INFRASTRUCTURE_INCONCLUSIVE");
  const infrastructureInconclusive = input.outcome === "INCONCLUSIVE" &&
    input.codes.length === 1 && codeSet.has("BROWSER_INFRASTRUCTURE_INCONCLUSIVE");
	const hardGateFailure = input.outcome === "HARD_GATE_FAIL" && codeSet.has("BROWSER_CONTRACT_FAILED") &&
		(input.codes.length === 1 ||
			(input.codes.length === 2 && codeSet.has("PENDING_AUDIT_RECONCILIATION")));
  if (
    !new Set(["INCONCLUSIVE", "HARD_GATE_FAIL"]).has(input.outcome) ||
    !input.codes.length ||
    codeSet.size !== input.codes.length ||
    input.codes.some((code) => !BROWSER_OBSERVATION_CODES.has(code)) ||
    (!pendingReconciliation && !infrastructureInconclusive && !hardGateFailure)
  ) reject("BROWSER_CODES_REJECTED");
  const artifactId = browserArtifactId(config);
  const envelope = parseEvidenceEnvelope({
    schemaVersion: ARTIFACT_SCHEMA_VERSION,
    artifactType: "EVIDENCE_ENVELOPE",
    artifactId,
    campaignId: config.campaign.artifact.campaignId,
    campaignManifestHash: config.campaign.hash,
    modelExecutionId: config.execution.artifact.modelExecutionId,
    modelExecutionManifestHash: config.execution.hash,
    recordedAt: input.recordedAt ?? new Date().toISOString(),
    evidenceType: "BROWSER_OBSERVATION",
    subjectType: "ATTEMPT",
    attemptId: config.attemptId,
    scenarioId: config.scenarioId,
    ordinal: config.ordinal,
    outcome: input.outcome,
    metrics: input.metrics,
    codes: [...input.codes].sort(),
  }, config.campaign, config.execution);

  const directory = path.resolve(config.outputRoot, config.campaign.artifact.campaignId, "browser");
  const target = path.resolve(directory, `${artifactId}.json`);
  if (!target.startsWith(`${directory}${path.sep}`)) reject("BROWSER_OUTPUT_PATH_REJECTED");
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  let descriptor: number | undefined;
  try {
    descriptor = openSync(
      target,
      constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | constants.O_NOFOLLOW,
      0o600,
    );
    writeFileSync(descriptor, `${JSON.stringify(envelope)}\n`, { encoding: "utf8" });
  } catch {
    reject("BROWSER_OBSERVATION_WRITE_REJECTED");
  } finally {
    if (descriptor !== undefined) closeSync(descriptor);
  }
  return { envelope, path: target };
}

export function browserArtifactId(config: BrowserCertificationConfig): string {
  const identity = [config.campaign.hash, config.execution.hash, config.attemptId, config.scenarioId, config.ordinal].join(":");
  return `artifact-${createHash("sha256").update(`browser:${identity}`).digest("hex")}`;
}

function readJsonArtifact(file: string): unknown {
  const absolute = path.resolve(file);
  if (!path.isAbsolute(file)) reject("CERTIFICATION_MANIFEST_PATH_REJECTED");
  try {
    const stats = statSync(absolute);
    if (!stats.isFile() || stats.size <= 0 || stats.size > MAX_MANIFEST_BYTES) reject("CERTIFICATION_MANIFEST_FILE_REJECTED");
    return JSON.parse(readFileSync(absolute, "utf8")) as unknown;
  } catch (error) {
    if (error instanceof BrowserCertificationError) throw error;
    reject("CERTIFICATION_MANIFEST_READ_REJECTED");
  }
}

function parseLoopbackBaseUrl(value: string): string {
  try {
    const url = new URL(value);
    const loopback = url.hostname === "127.0.0.1" || url.hostname === "[::1]";
    if (
      !loopback || url.protocol !== "http:" || !url.port || url.username || url.password ||
      url.pathname !== "/" || url.search || url.hash
    ) reject("CERTIFICATION_BASE_URL_REJECTED");
    return url.origin;
  } catch (error) {
    if (error instanceof BrowserCertificationError) throw error;
    reject("CERTIFICATION_BASE_URL_REJECTED");
  }
}

function absoluteDirectory(value: string): string {
  if (!path.isAbsolute(value)) reject("CERTIFICATION_OUTPUT_ROOT_REJECTED");
  return path.resolve(value);
}

function uniqueWritingBlockIds(value: string): readonly string[] {
  const ids = value.split(",");
  if (!ids.length || ids.some((id) => !UUID.test(id)) || new Set(ids).size !== ids.length) {
    reject("CERTIFICATION_WRITING_BLOCK_IDS_REJECTED");
  }
  return Object.freeze(ids);
}

function requireHash(environment: Readonly<Record<string, string | undefined>>, key: string, observed: string): void {
  const expected = required(environment, key);
  if (!HASH.test(expected) || expected !== observed) reject("CERTIFICATION_MANIFEST_HASH_REJECTED");
}

function rejectInheritedSecrets(environment: Readonly<Record<string, string | undefined>>): void {
  if (SECRET_ENV_KEYS.some((key) => environment[key] !== undefined)) reject("CERTIFICATION_RUNNER_SECRET_REJECTED");
}

function required(environment: Readonly<Record<string, string | undefined>>, key: string): string {
  const value = environment[key];
  if (!value) reject("CERTIFICATION_ENV_REJECTED");
  return value;
}

function reject(code: string): never {
  throw new BrowserCertificationError(code);
}
