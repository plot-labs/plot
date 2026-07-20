import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

import {
  ARTIFACT_SCHEMA_ID,
  ARTIFACT_SCHEMA_VERSION,
  CertificationArtifactError,
  parseEvidenceEnvelope,
  sealCampaignManifest,
  sealModelExecutionManifest,
  validateArtifactBundle,
} from "./certification-artifact";

const schema = JSON.parse(
  readFileSync(fileURLToPath(new URL("../../../../docs/specs/production-generation-certification-artifacts.schema.json", import.meta.url)), "utf8"),
) as {
  $id: string;
  "x-plot-schema-version": string;
  examples: unknown[];
  $defs: { metrics: { properties: Record<string, unknown> } };
};

const example = (artifactType: string) =>
  structuredClone(
    schema.examples.find((candidate) => (candidate as { artifactType?: string }).artifactType === artifactType),
  ) as Record<string, unknown>;

describe("certification artifact contract", () => {
  it("accepts the same shared schema examples as Kotlin", () => {
    expect(schema.$id).toBe(ARTIFACT_SCHEMA_ID);
    expect(schema["x-plot-schema-version"]).toBe(ARTIFACT_SCHEMA_VERSION);
    const campaign = sealCampaignManifest(example("CAMPAIGN_MANIFEST"));
    const execution = sealModelExecutionManifest(example("MODEL_EXECUTION_MANIFEST"), campaign);
    const evidence = parseEvidenceEnvelope(example("EVIDENCE_ENVELOPE"), campaign, execution);

    expect(execution.artifact.servedModel).toBe("openai/gpt-5.4-nano-2026-06-01");
    expect(execution.artifact.scenarioIds).toEqual(["real-github-journey"]);
    expect(evidence.outcome).toBe("PASS");
    expect(campaign.hash).toMatch(/^sha256:[a-f0-9]{64}$/);
  });

  it("keeps scored basis-point metrics aligned with the shared schema", () => {
    const basisPointKeys = [
      "citationPrecisionBasisPoints", "citationRecallBasisPoints", "supportedClaimRecallBasisPoints",
      "unsupportedClaimRecallBasisPoints", "conflictRecallBasisPoints", "notRequiredFalsePositiveBasisPoints",
    ];
    expect(Object.keys(schema.$defs.metrics.properties)).toEqual(expect.arrayContaining(basisPointKeys));
    const campaign = sealCampaignManifest(example("CAMPAIGN_MANIFEST"));
    const execution = sealModelExecutionManifest(example("MODEL_EXECUTION_MANIFEST"), campaign);
    const evidence = example("EVIDENCE_ENVELOPE");
    evidence.metrics = Object.fromEntries(basisPointKeys.map((key) => [key, 10_000]));
    expect(parseEvidenceEnvelope(evidence, campaign, execution).metrics).toMatchObject(
      Object.fromEntries(basisPointKeys.map((key) => [key, 10_000])),
    );
    (evidence.metrics as Record<string, number>).citationPrecisionBasisPoints = 10_001;
    expect(() => parseEvidenceEnvelope(evidence, campaign, execution)).toThrow(/citationPrecisionBasisPoints/);
  });

  it("rejects unknown private fields and a wrong manifest hash", () => {
    const campaignInput = example("CAMPAIGN_MANIFEST");
    campaignInput.privateUrl = "https://private.example/repo";
    expect(() => sealCampaignManifest(campaignInput)).toThrow(CertificationArtifactError);

    const campaign = sealCampaignManifest(example("CAMPAIGN_MANIFEST"));
    const execution = sealModelExecutionManifest(example("MODEL_EXECUTION_MANIFEST"), campaign);
    const evidence = example("EVIDENCE_ENVELOPE");
    evidence.campaignManifestHash = `sha256:${"0".repeat(64)}`;
    expect(() => parseEvidenceEnvelope(evidence, campaign, execution)).toThrow(/campaign manifest hash/i);
  });

  it("requires and seals the content-free source snapshot set hash", () => {
    const missing = example("CAMPAIGN_MANIFEST");
    delete missing.sourceSnapshotSetHash;
    expect(() => sealCampaignManifest(missing)).toThrow(/sourceSnapshotSetHash/);

    const original = sealCampaignManifest(example("CAMPAIGN_MANIFEST"));
    const changed = example("CAMPAIGN_MANIFEST");
    changed.sourceSnapshotSetHash = `sha256:${"9".repeat(64)}`;
    expect(sealCampaignManifest(changed).hash).not.toBe(original.hash);
  });

  it("detects duplicate artifact identity", () => {
    const campaign = sealCampaignManifest(example("CAMPAIGN_MANIFEST"));
    const execution = sealModelExecutionManifest(example("MODEL_EXECUTION_MANIFEST"), campaign);
    const evidence = parseEvidenceEnvelope(example("EVIDENCE_ENVELOPE"), campaign, execution);
    expect(() => validateArtifactBundle(campaign, [execution], [evidence, evidence])).toThrow(/duplicate artifact/i);
    expect(() => validateArtifactBundle(campaign, [execution], [{ ...evidence, campaignId: "campaign-bbbbbbbbbbbbbbbb" }])).toThrow(/campaign/i);
    expect(() => validateArtifactBundle(campaign, [execution], [{ ...evidence, campaignManifestHash: `sha256:${"0".repeat(64)}` }])).toThrow(/campaign/i);
    expect(() => validateArtifactBundle(campaign, [execution], [{ ...evidence, modelExecutionId: "model-execution-bbbbbbbbbbbbbbbb" }])).toThrow(/model execution/i);
  });

  it("rejects evidence attribution that drifts from the sealed served model", () => {
    const campaign = sealCampaignManifest(example("CAMPAIGN_MANIFEST"));
    const execution = sealModelExecutionManifest(example("MODEL_EXECUTION_MANIFEST"), campaign);
    const evidence = example("EVIDENCE_ENVELOPE");
    (evidence.attribution as Record<string, unknown>).servedModel = "openai/gpt-5.4-nano-2026-06-02";
    expect(() => parseEvidenceEnvelope(evidence, campaign, execution)).toThrow(/served model/i);
  });

  it("replaces an inconclusive attempt only with a new id at the same scenario ordinal", () => {
    const campaign = sealCampaignManifest(example("CAMPAIGN_MANIFEST"));
    const executionInput = example("MODEL_EXECUTION_MANIFEST");
    executionInput.scenarioIds = ["real-github-journey", "unsupported-claim"];
    const execution = sealModelExecutionManifest(executionInput, campaign);
    const priorInput = example("EVIDENCE_ENVELOPE");
    priorInput.modelExecutionManifestHash = execution.hash;
    priorInput.outcome = "INCONCLUSIVE";
    const prior = parseEvidenceEnvelope(priorInput, campaign, execution);
    const replacementInput = example("EVIDENCE_ENVELOPE");
    replacementInput.modelExecutionManifestHash = execution.hash;
    replacementInput.artifactId = "artifact-dddddddddddddddd";
    replacementInput.attemptId = "attempt-bbbbbbbbbbbbbbbb";
    replacementInput.lineage = {
      relation: "REPLACES_INCONCLUSIVE",
      priorArtifactId: prior.artifactId,
      priorAttemptId: prior.attemptId,
    };

    expect(parseEvidenceEnvelope(replacementInput, campaign, execution, prior).ordinal).toBe(1);
    replacementInput.scenarioId = "unsupported-claim";
    expect(() => parseEvidenceEnvelope(replacementInput, campaign, execution, prior)).toThrow(/same scenario/i);
    replacementInput.scenarioId = "real-github-journey";
    replacementInput.ordinal = 2;
    expect(() => parseEvidenceEnvelope(replacementInput, campaign, execution, prior)).toThrow(/same scenario and ordinal/i);
  });
});
