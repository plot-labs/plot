import { createHash } from "node:crypto";

export const ARTIFACT_SCHEMA_ID = "https://plot.local/schemas/production-generation-certification-artifacts/v1";
export const ARTIFACT_SCHEMA_VERSION = "plot.production-generation-certification/v1";

const patterns = {
  artifactId: /^artifact-[a-f0-9]{16,64}$/,
  campaignId: /^campaign-[a-f0-9]{16,64}$/,
  modelExecutionId: /^model-execution-[a-f0-9]{16,64}$/,
  attemptId: /^attempt-[a-f0-9]{16,64}$/,
  hash: /^sha256:[a-f0-9]{64}$/,
  revision: /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/,
  reportId: /^report-[a-f0-9]{16,64}$/,
  sourceAlias: /^source-[a-f0-9]{16,64}$/,
  processIdentity: /^process-[a-f0-9]{16,64}$/,
  namespace: /^namespace-[a-f0-9]{16,64}$/,
  scenarioId: /^[a-z0-9]+(?:-[a-z0-9]+)*$/,
  modelId: /^[a-z0-9][a-z0-9._-]*\/[a-zA-Z0-9][a-zA-Z0-9._:-]*$/,
  providerSlug: /^[a-z0-9]+(?:[._-][a-z0-9]+)*$/,
  code: /^[A-Z][A-Z0-9_]{1,63}$/,
} as const;

export class CertificationArtifactError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CertificationArtifactError";
  }
}

export type RevisionLineage = Readonly<{
  relation: "SUPERSEDES_REVISION";
  priorCampaignId: string;
  priorSourceRevision: string;
  priorCampaignManifestHash: string;
}>;

export type CampaignManifest = Readonly<{
  schemaVersion: typeof ARTIFACT_SCHEMA_VERSION;
  artifactType: "CAMPAIGN_MANIFEST";
  artifactId: string;
  campaignId: string;
  sealedAt: string;
  sourceRevision: string;
  corpusHash: string;
  profileHash: string;
  sourceSnapshotSetHash: string;
  environmentFingerprint: string;
  reportId: string;
  approvedSourceAliases: readonly string[];
  revisionLineage?: RevisionLineage;
}>;

export type ModelExecutionManifest = Readonly<{
  schemaVersion: typeof ARTIFACT_SCHEMA_VERSION;
  artifactType: "MODEL_EXECUTION_MANIFEST";
  artifactId: string;
  campaignId: string;
  campaignManifestHash: string;
  modelExecutionId: string;
  sealedAt: string;
  requestedModel: string;
  servedModel: string;
  modelProfileHash: string;
  pinnedUpstream: string;
  routePolicyHash: string;
  processIdentity: string;
  sourceNamespace: string;
  idempotencyNamespace: string;
  scenarioIds: readonly string[];
}>;

const evidenceOutcomes = ["PASS", "HARD_GATE_FAIL", "INCONCLUSIVE"] as const;
const evidenceTypes = [
  "PREFLIGHT", "ROUTE_CANARY", "MODEL_ATTEMPT", "BROWSER_OBSERVATION", "PERSISTED_AUDIT", "CLEANUP", "DECISION",
] as const;
const evidenceSubjectTypes = ["CAMPAIGN", "MODEL_EXECUTION", "ATTEMPT"] as const;

export type EvidenceOutcome = typeof evidenceOutcomes[number];
export type EvidenceType = typeof evidenceTypes[number];
export type EvidenceSubjectType = typeof evidenceSubjectTypes[number];

export type EvidenceMetrics = Readonly<Record<string, number | boolean>>;
export type EvidenceAttribution = Readonly<{
  requestedModel: string;
  servedModel: string;
  observedUpstream: string;
  responseIdHash: string;
}>;
export type ReplacementLineage = Readonly<{
  relation: "REPLACES_INCONCLUSIVE";
  priorArtifactId: string;
  priorAttemptId: string;
}>;

export type EvidenceEnvelope = Readonly<{
  schemaVersion: typeof ARTIFACT_SCHEMA_VERSION;
  artifactType: "EVIDENCE_ENVELOPE";
  artifactId: string;
  campaignId: string;
  campaignManifestHash: string;
  modelExecutionId?: string;
  modelExecutionManifestHash?: string;
  recordedAt: string;
  evidenceType: EvidenceType;
  subjectType: EvidenceSubjectType;
  attemptId?: string;
  scenarioId?: string;
  ordinal?: number;
  outcome: EvidenceOutcome;
  metrics: EvidenceMetrics;
  codes: readonly string[];
  attribution?: EvidenceAttribution;
  lineage?: ReplacementLineage;
}>;

export type SealedArtifact<T> = Readonly<{ artifact: T; hash: string }>;

const campaignKeys = new Set([
  "schemaVersion", "artifactType", "artifactId", "campaignId", "sealedAt", "sourceRevision", "corpusHash",
  "profileHash", "sourceSnapshotSetHash", "environmentFingerprint", "reportId", "approvedSourceAliases", "revisionLineage",
]);
const executionKeys = new Set([
  "schemaVersion", "artifactType", "artifactId", "campaignId", "campaignManifestHash", "modelExecutionId",
  "sealedAt", "requestedModel", "servedModel", "modelProfileHash", "pinnedUpstream", "routePolicyHash", "processIdentity",
  "sourceNamespace", "idempotencyNamespace", "scenarioIds",
]);
const evidenceKeys = new Set([
  "schemaVersion", "artifactType", "artifactId", "campaignId", "campaignManifestHash", "modelExecutionId",
  "modelExecutionManifestHash", "recordedAt", "evidenceType", "subjectType", "attemptId", "scenarioId",
  "ordinal", "outcome", "metrics", "codes", "attribution", "lineage",
]);
const metricKeys = new Set([
  "latencyMs", "promptTokens", "completionTokens", "reasoningTokens", "cachedTokens", "costUsdMicros",
  "rewriteCount", "citationCount", "reviewNeededSentenceCount", "unresolvedConflictCount", "modelCallCount",
  "citationPrecisionBasisPoints", "citationRecallBasisPoints", "supportedClaimRecallBasisPoints",
  "unsupportedClaimRecallBasisPoints", "conflictRecallBasisPoints", "notRequiredFalsePositiveBasisPoints",
  "exportEventCount", "listenerCount", "liveCredentialCount", "transientArtifactCount", "coldStart",
]);
const campaignEvidenceTypes: readonly EvidenceType[] = ["PREFLIGHT", "CLEANUP", "DECISION"];
const attemptEvidenceTypes: readonly EvidenceType[] = ["MODEL_ATTEMPT", "BROWSER_OBSERVATION", "PERSISTED_AUDIT"];
const revisionLineageKeys = new Set(["relation", "priorCampaignId", "priorSourceRevision", "priorCampaignManifestHash"]);
const attributionKeys = new Set(["requestedModel", "servedModel", "observedUpstream", "responseIdHash"]);
const replacementLineageKeys = new Set(["relation", "priorArtifactId", "priorAttemptId"]);

export function sealCampaignManifest(input: unknown, prior?: SealedArtifact<CampaignManifest>): SealedArtifact<CampaignManifest> {
  const value = record(input, "campaign manifest");
  exactKeys(value, campaignKeys, "campaign manifest");
  literal(value.schemaVersion, ARTIFACT_SCHEMA_VERSION, "schemaVersion");
  literal(value.artifactType, "CAMPAIGN_MANIFEST", "artifactType");
  const lineage = value.revisionLineage === undefined ? undefined : parseRevisionLineage(value.revisionLineage);
  const artifact: CampaignManifest = deepFreeze({
    schemaVersion: ARTIFACT_SCHEMA_VERSION,
    artifactType: "CAMPAIGN_MANIFEST" as const,
    artifactId: patterned(value.artifactId, patterns.artifactId, "artifactId"),
    campaignId: patterned(value.campaignId, patterns.campaignId, "campaignId"),
    sealedAt: timestamp(value.sealedAt, "sealedAt"),
    sourceRevision: patterned(value.sourceRevision, patterns.revision, "sourceRevision"),
    corpusHash: patterned(value.corpusHash, patterns.hash, "corpusHash"),
    profileHash: patterned(value.profileHash, patterns.hash, "profileHash"),
    sourceSnapshotSetHash: patterned(value.sourceSnapshotSetHash, patterns.hash, "sourceSnapshotSetHash"),
    environmentFingerprint: patterned(value.environmentFingerprint, patterns.hash, "environmentFingerprint"),
    reportId: patterned(value.reportId, patterns.reportId, "reportId"),
    approvedSourceAliases: uniqueStrings(value.approvedSourceAliases, patterns.sourceAlias, "approvedSourceAliases", true),
    ...(lineage ? { revisionLineage: lineage } : {}),
  });
  if (lineage) {
    if (!prior) fail("revision lineage requires the sealed prior campaign");
    if (
      lineage.priorCampaignId !== prior.artifact.campaignId ||
      lineage.priorSourceRevision !== prior.artifact.sourceRevision ||
      lineage.priorCampaignManifestHash !== prior.hash
    ) fail("revision lineage does not match the sealed prior campaign");
    if (artifact.campaignId === prior.artifact.campaignId || artifact.sourceRevision === prior.artifact.sourceRevision) {
      fail("a superseding revision must have a new campaign and source revision");
    }
  } else if (prior) {
    fail("prior campaign supplied without explicit revision lineage");
  }
  return deepFreeze({ artifact, hash: canonicalArtifactHash(artifact) });
}

export function sealModelExecutionManifest(
  input: unknown,
  campaign: SealedArtifact<CampaignManifest>,
): SealedArtifact<ModelExecutionManifest> {
  const value = record(input, "model execution manifest");
  exactKeys(value, executionKeys, "model execution manifest");
  literal(value.schemaVersion, ARTIFACT_SCHEMA_VERSION, "schemaVersion");
  literal(value.artifactType, "MODEL_EXECUTION_MANIFEST", "artifactType");
  const scenarioIds = uniqueStrings(value.scenarioIds, patterns.scenarioId, "scenarioIds", true);
  const artifact: ModelExecutionManifest = deepFreeze({
    schemaVersion: ARTIFACT_SCHEMA_VERSION,
    artifactType: "MODEL_EXECUTION_MANIFEST" as const,
    artifactId: patterned(value.artifactId, patterns.artifactId, "artifactId"),
    campaignId: patterned(value.campaignId, patterns.campaignId, "campaignId"),
    campaignManifestHash: patterned(value.campaignManifestHash, patterns.hash, "campaignManifestHash"),
    modelExecutionId: patterned(value.modelExecutionId, patterns.modelExecutionId, "modelExecutionId"),
    sealedAt: timestamp(value.sealedAt, "sealedAt"),
    requestedModel: patterned(value.requestedModel, patterns.modelId, "requestedModel"),
    servedModel: patterned(value.servedModel, patterns.modelId, "servedModel"),
    modelProfileHash: patterned(value.modelProfileHash, patterns.hash, "modelProfileHash"),
    pinnedUpstream: patterned(value.pinnedUpstream, patterns.providerSlug, "pinnedUpstream"),
    routePolicyHash: patterned(value.routePolicyHash, patterns.hash, "routePolicyHash"),
    processIdentity: patterned(value.processIdentity, patterns.processIdentity, "processIdentity"),
    sourceNamespace: patterned(value.sourceNamespace, patterns.namespace, "sourceNamespace"),
    idempotencyNamespace: patterned(value.idempotencyNamespace, patterns.namespace, "idempotencyNamespace"),
    scenarioIds,
  });
  if (artifact.campaignId !== campaign.artifact.campaignId) fail("model execution references another campaign");
  if (artifact.campaignManifestHash !== campaign.hash) fail("model execution campaign manifest hash mismatch");
  return deepFreeze({ artifact, hash: canonicalArtifactHash(artifact) });
}

export function parseEvidenceEnvelope(
  input: unknown,
  campaign: SealedArtifact<CampaignManifest>,
  execution?: SealedArtifact<ModelExecutionManifest> | null,
  prior?: EvidenceEnvelope,
): EvidenceEnvelope {
  const value = record(input, "evidence envelope");
  exactKeys(value, evidenceKeys, "evidence envelope");
  literal(value.schemaVersion, ARTIFACT_SCHEMA_VERSION, "schemaVersion");
  literal(value.artifactType, "EVIDENCE_ENVELOPE", "artifactType");
  const subjectType = oneOf(value.subjectType, evidenceSubjectTypes, "subjectType");
  const evidenceType = oneOf(value.evidenceType, evidenceTypes, "evidenceType");
  const artifact: EvidenceEnvelope = deepFreeze({
    schemaVersion: ARTIFACT_SCHEMA_VERSION,
    artifactType: "EVIDENCE_ENVELOPE",
    artifactId: patterned(value.artifactId, patterns.artifactId, "artifactId"),
    campaignId: patterned(value.campaignId, patterns.campaignId, "campaignId"),
    campaignManifestHash: patterned(value.campaignManifestHash, patterns.hash, "campaignManifestHash"),
    recordedAt: timestamp(value.recordedAt, "recordedAt"),
    evidenceType,
    subjectType,
    outcome: oneOf(value.outcome, evidenceOutcomes, "outcome"),
    metrics: parseMetrics(value.metrics),
    codes: uniqueStrings(value.codes, patterns.code, "codes", false),
    ...(value.modelExecutionId === undefined ? {} : { modelExecutionId: patterned(value.modelExecutionId, patterns.modelExecutionId, "modelExecutionId") }),
    ...(value.modelExecutionManifestHash === undefined ? {} : { modelExecutionManifestHash: patterned(value.modelExecutionManifestHash, patterns.hash, "modelExecutionManifestHash") }),
    ...(value.attemptId === undefined ? {} : { attemptId: patterned(value.attemptId, patterns.attemptId, "attemptId") }),
    ...(value.scenarioId === undefined ? {} : { scenarioId: patterned(value.scenarioId, patterns.scenarioId, "scenarioId") }),
    ...(value.ordinal === undefined ? {} : { ordinal: integer(value.ordinal, "ordinal", 1, 3) }),
    ...(value.attribution === undefined ? {} : { attribution: parseAttribution(value.attribution) }),
    ...(value.lineage === undefined ? {} : { lineage: parseReplacementLineage(value.lineage) }),
  });
  if (artifact.campaignId !== campaign.artifact.campaignId) fail("evidence references another campaign");
  if (artifact.campaignManifestHash !== campaign.hash) fail("evidence campaign manifest hash mismatch");
  validateEvidenceSubject(artifact, execution);
  validateReplacement(artifact, prior);
  return artifact;
}

export function validateArtifactBundle(
  campaign: SealedArtifact<CampaignManifest>,
  executions: readonly SealedArtifact<ModelExecutionManifest>[],
  evidence: readonly EvidenceEnvelope[],
): void {
  const artifactIds = [campaign.artifact.artifactId, ...executions.map((item) => item.artifact.artifactId), ...evidence.map((item) => item.artifactId)];
  if (new Set(artifactIds).size !== artifactIds.length) fail("duplicate artifact identity");
  const executionIds = executions.map((item) => item.artifact.modelExecutionId);
  if (new Set(executionIds).size !== executionIds.length) fail("duplicate model execution identity");
  executions.forEach((item) => {
    if (item.artifact.campaignId !== campaign.artifact.campaignId || item.artifact.campaignManifestHash !== campaign.hash) {
      fail("bundle contains a cross-campaign model execution");
    }
  });
  const executionsById = new Map(executions.map((item) => [item.artifact.modelExecutionId, item]));
  evidence.forEach((envelope) => {
    if (envelope.campaignId !== campaign.artifact.campaignId || envelope.campaignManifestHash !== campaign.hash) {
      fail("bundle contains cross-campaign evidence");
    }
    if (envelope.subjectType === "CAMPAIGN") {
      if (envelope.modelExecutionId || envelope.modelExecutionManifestHash) fail("campaign evidence cannot reference a model execution");
      return;
    }
    if (!envelope.modelExecutionId) fail("bundle evidence is missing its model execution");
    const execution = executionsById.get(envelope.modelExecutionId);
    if (!execution) fail("bundle evidence references a model execution that is not present");
    if (envelope.modelExecutionManifestHash !== execution.hash) fail("bundle evidence model execution manifest hash mismatch");
  });
}

export function canonicalArtifactHash(value: unknown): string {
  return `sha256:${createHash("sha256").update(stableJson(value)).digest("hex")}`;
}

function validateEvidenceSubject(artifact: EvidenceEnvelope, execution?: SealedArtifact<ModelExecutionManifest> | null) {
  if (artifact.subjectType === "CAMPAIGN") {
    if (!campaignEvidenceTypes.includes(artifact.evidenceType) || artifact.modelExecutionId || artifact.modelExecutionManifestHash ||
      artifact.attemptId || artifact.scenarioId || artifact.ordinal || artifact.attribution || artifact.lineage) {
      fail("campaign evidence contains model or attempt fields");
    }
    if (execution) fail("campaign evidence must not be parsed against a model execution");
    return;
  }
  if (!execution) fail("model and attempt evidence require a sealed model execution");
  if (artifact.modelExecutionId !== execution.artifact.modelExecutionId) fail("evidence references another model execution");
  if (artifact.modelExecutionManifestHash !== execution.hash) fail("evidence model execution manifest hash mismatch");
  if (artifact.subjectType === "MODEL_EXECUTION") {
    if (artifact.evidenceType !== "ROUTE_CANARY" || artifact.attemptId || artifact.scenarioId || artifact.ordinal || artifact.lineage ||
      !artifact.attribution) fail("invalid model execution evidence fields");
  } else {
    if (!attemptEvidenceTypes.includes(artifact.evidenceType) || !artifact.attemptId || !artifact.scenarioId || !artifact.ordinal) {
      fail("attempt evidence is missing its sealed attempt identity");
    }
    if (!execution.artifact.scenarioIds.includes(artifact.scenarioId)) fail("evidence references an unsealed scenario");
  }
  if (artifact.attribution) {
    if (artifact.attribution.requestedModel !== execution.artifact.requestedModel) fail("attempt mixes model profiles");
    if (artifact.attribution.servedModel !== execution.artifact.servedModel) fail("attempt served model does not match the sealed served model");
    if (artifact.attribution.observedUpstream !== execution.artifact.pinnedUpstream) fail("attempt mixes pinned routes");
  }
}

function validateReplacement(artifact: EvidenceEnvelope, prior?: EvidenceEnvelope) {
  if (!artifact.lineage) {
    if (prior) fail("prior evidence supplied without explicit replacement lineage");
    return;
  }
  if (!prior) fail("replacement lineage requires prior evidence");
  if (prior.outcome !== "INCONCLUSIVE") fail("only inconclusive evidence can be replaced");
  if (artifact.lineage.priorArtifactId !== prior.artifactId || artifact.lineage.priorAttemptId !== prior.attemptId) fail("replacement lineage does not match prior evidence");
  if (artifact.campaignId !== prior.campaignId || artifact.modelExecutionId !== prior.modelExecutionId) fail("replacement lineage cannot cross campaign or model execution");
  if (artifact.attemptId === prior.attemptId) fail("replacement must use a new attempt id");
  if (artifact.scenarioId !== prior.scenarioId || artifact.ordinal !== prior.ordinal) {
    fail("replacement must preserve the same scenario and ordinal");
  }
}

function parseRevisionLineage(input: unknown): RevisionLineage {
  const value = record(input, "revisionLineage");
  exactKeys(value, revisionLineageKeys, "revisionLineage");
  literal(value.relation, "SUPERSEDES_REVISION", "revisionLineage.relation");
  return deepFreeze({
    relation: "SUPERSEDES_REVISION",
    priorCampaignId: patterned(value.priorCampaignId, patterns.campaignId, "revisionLineage.priorCampaignId"),
    priorSourceRevision: patterned(value.priorSourceRevision, patterns.revision, "revisionLineage.priorSourceRevision"),
    priorCampaignManifestHash: patterned(value.priorCampaignManifestHash, patterns.hash, "revisionLineage.priorCampaignManifestHash"),
  });
}

function parseMetrics(input: unknown): EvidenceMetrics {
  const value = record(input, "metrics");
  if (!Object.keys(value).length) fail("metrics must contain at least one typed value");
  exactKeys(value, metricKeys, "metrics");
  const parsed: Record<string, number | boolean> = {};
  Object.entries(value).forEach(([key, metric]) => {
    if (key === "coldStart") {
      if (typeof metric !== "boolean") fail("metrics.coldStart must be boolean");
      parsed[key] = metric;
    } else {
      parsed[key] = integer(metric, `metrics.${key}`, 0, key.endsWith("BasisPoints") ? 10_000 : Number.MAX_SAFE_INTEGER);
    }
  });
  return deepFreeze(parsed);
}

function parseAttribution(input: unknown): EvidenceAttribution {
  const value = record(input, "attribution");
  exactKeys(value, attributionKeys, "attribution");
  return deepFreeze({
    requestedModel: patterned(value.requestedModel, patterns.modelId, "attribution.requestedModel"),
    servedModel: patterned(value.servedModel, patterns.modelId, "attribution.servedModel"),
    observedUpstream: patterned(value.observedUpstream, patterns.providerSlug, "attribution.observedUpstream"),
    responseIdHash: patterned(value.responseIdHash, patterns.hash, "attribution.responseIdHash"),
  });
}

function parseReplacementLineage(input: unknown): ReplacementLineage {
  const value = record(input, "lineage");
  exactKeys(value, replacementLineageKeys, "lineage");
  literal(value.relation, "REPLACES_INCONCLUSIVE", "lineage.relation");
  return deepFreeze({
    relation: "REPLACES_INCONCLUSIVE",
    priorArtifactId: patterned(value.priorArtifactId, patterns.artifactId, "lineage.priorArtifactId"),
    priorAttemptId: patterned(value.priorAttemptId, patterns.attemptId, "lineage.priorAttemptId"),
  });
}

function stableJson(input: unknown): string {
  if (input === null || typeof input === "boolean" || typeof input === "number" || typeof input === "string") return JSON.stringify(input);
  if (Array.isArray(input)) return `[${input.map(stableJson).join(",")}]`;
  const value = record(input, "canonical artifact");
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(",")}}`;
}

function deepFreeze<T>(value: T): T {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.values(value as Record<string, unknown>).forEach(deepFreeze);
    Object.freeze(value);
  }
  return value;
}

function record(input: unknown, name: string): Record<string, unknown> {
  if (!input || typeof input !== "object" || Array.isArray(input)) fail(`${name} must be an object`);
  return input as Record<string, unknown>;
}

function array(input: unknown, name: string): unknown[] {
  if (!Array.isArray(input)) fail(`${name} must be an array`);
  return input;
}

function exactKeys(input: Record<string, unknown>, allowed: readonly string[] | ReadonlySet<string>, name: string) {
  const allowedSet = allowed instanceof Set ? allowed : new Set(allowed);
  const unknown = Object.keys(input).filter((key) => !allowedSet.has(key));
  if (unknown.length) fail(`${name} contains unknown or private fields: ${unknown.join(", ")}`);
}

function patterned(input: unknown, pattern: RegExp, name: string): string {
  if (typeof input !== "string" || !pattern.test(input)) fail(`${name} has an invalid or non-opaque value`);
  return input;
}

function timestamp(input: unknown, name: string): string {
  if (typeof input !== "string" || !/^\d{4}-\d{2}-\d{2}T/.test(input) || Number.isNaN(Date.parse(input))) fail(`${name} must be an RFC 3339 timestamp`);
  return input;
}

function integer(input: unknown, name: string, min: number, max: number): number {
  if (typeof input !== "number" || !Number.isSafeInteger(input) || input < min || input > max) fail(`${name} must be an integer from ${min} to ${max}`);
  return input;
}

function literal<const T extends string>(input: unknown, expected: T, name: string): T {
  if (input !== expected) fail(`${name} must be ${expected}`);
  return expected;
}

function oneOf<const T extends readonly string[]>(input: unknown, allowed: T, name: string): T[number] {
  if (typeof input !== "string" || !allowed.includes(input)) fail(`${name} must be one of ${allowed.join(", ")}`);
  return input as T[number];
}

function uniqueStrings(input: unknown, pattern: RegExp, name: string, nonEmpty: boolean): readonly string[] {
  const values = array(input, name).map((item, index) => patterned(item, pattern, `${name}[${index}]`));
  if (nonEmpty && !values.length) fail(`${name} must not be empty`);
  if (new Set(values).size !== values.length) fail(`${name} must contain unique values`);
  return deepFreeze(values);
}

function fail(message: string): never {
  throw new CertificationArtifactError(message);
}
