import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";

import schema from "../../../../docs/specs/production-generation-certification-artifacts.schema.json";
import { canonicalArtifactHash } from "./certification-artifact";
import {
  BrowserCertificationError,
  browserFailureOutcome,
  loadBrowserCertificationConfig,
  writeBrowserObservation,
} from "./certification-manifest";

const schemaExamples = schema.examples as Array<Record<string, unknown>>;

function artifact(type: string): Record<string, unknown> {
  return structuredClone(schemaExamples.find((value) => value.artifactType === type)!);
}

function fixtureEnvironment(
  overrides: Readonly<Record<string, string | undefined>> = {},
): Readonly<Record<string, string | undefined>> {
  const root = mkdtempSync(path.join(tmpdir(), "plot-browser-cert-"));
  const campaign = artifact("CAMPAIGN_MANIFEST");
  const execution = artifact("MODEL_EXECUTION_MANIFEST");
  execution.campaignManifestHash = canonicalArtifactHash(campaign);
  const campaignPath = path.join(root, "campaign.json");
  const executionPath = path.join(root, "execution.json");
  writeFileSync(campaignPath, JSON.stringify(campaign));
  writeFileSync(executionPath, JSON.stringify(execution));
  return {
    PLOT_CERTIFICATION_MODE: "real-source",
    PLOT_CERTIFICATION_BASE_URL: "http://127.0.0.1:3000",
    PLOT_CERTIFICATION_OUTPUT_ROOT: root,
    PLOT_CERTIFICATION_CAMPAIGN_MANIFEST: campaignPath,
    PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH: canonicalArtifactHash(campaign),
    PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST: executionPath,
    PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH: canonicalArtifactHash(execution),
    PLOT_CERTIFICATION_ATTEMPT_ID: "attempt-aaaaaaaaaaaaaaaa",
    PLOT_CERTIFICATION_SCENARIO_ID: "real-github-journey",
    PLOT_CERTIFICATION_ATTEMPT_ORDINAL: "1",
    PLOT_CERTIFICATION_WRITING_BLOCK_IDS: "00000000-0000-4000-8000-000000000001",
    ...overrides,
  };
}

describe("browser certification manifest", () => {
  it("loads sealed manifests and derives the attributable idempotency key", () => {
    const config = loadBrowserCertificationConfig(fixtureEnvironment({
      PLOT_CERTIFICATION_WRITING_BLOCK_IDS: [
        "00000000-0000-4000-8000-000000000001",
        "00000000-0000-4000-8000-000000000002",
      ].join(","),
    }));
    expect(config.baseUrl).toBe("http://127.0.0.1:3000");
    expect(config.idempotencyKey).toBe("namespace-bbbbbbbbbbbbbbbb:attempt-aaaaaaaaaaaaaaaa");
    expect(config.writingBlockIds).toEqual([
      "00000000-0000-4000-8000-000000000001",
      "00000000-0000-4000-8000-000000000002",
    ]);
  });

  it.each([
    { PLOT_CERTIFICATION_BASE_URL: "https://example.com" },
    { PLOT_CERTIFICATION_BASE_URL: "http://localhost:3000" },
    { PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH: `sha256:${"0".repeat(64)}` },
    { PLOT_CERTIFICATION_SCENARIO_ID: "unsealed-scenario" },
    { PLOT_CERTIFICATION_ATTEMPT_ORDINAL: "4" },
    { OPENROUTER_API_KEY: "present-but-never-read" },
  ])("fails closed for unsealed or unsafe input", (override) => {
    expect(() => loadBrowserCertificationConfig(fixtureEnvironment(override))).toThrow(BrowserCertificationError);
  });

  it("writes one immutable U9 observation that never claims PASS", () => {
    const config = loadBrowserCertificationConfig(fixtureEnvironment());
    const written = writeBrowserObservation(config, {
      outcome: "INCONCLUSIVE",
      metrics: { latencyMs: 12, citationCount: 1, exportEventCount: 2 },
      codes: ["BROWSER_CONTRACT_OBSERVED", "PENDING_AUDIT_RECONCILIATION"],
      recordedAt: "2026-07-16T00:03:00Z",
    });

    expect(written.envelope).toMatchObject({
      evidenceType: "BROWSER_OBSERVATION",
      outcome: "INCONCLUSIVE",
      attemptId: config.attemptId,
      modelExecutionManifestHash: config.execution.hash,
    });
    const persisted = JSON.parse(readFileSync(written.path, "utf8")) as Record<string, unknown>;
    expect(persisted).not.toHaveProperty("generationRunId");
    expect(persisted).not.toHaveProperty("sourceLabel");
    expect(() => writeBrowserObservation(config, {
      outcome: "HARD_GATE_FAIL",
      metrics: { latencyMs: 12 },
      codes: ["BROWSER_CONTRACT_FAILED"],
    })).toThrow(/WRITE_REJECTED/);
  });

  it("rejects PASS and arbitrary code channels before writing", () => {
    const config = loadBrowserCertificationConfig(fixtureEnvironment());
    expect(() => writeBrowserObservation(config, {
      outcome: "PASS",
      metrics: { latencyMs: 1 },
      codes: ["BROWSER_CONTRACT_OBSERVED"],
    } as never)).toThrow(/CODES_REJECTED/);
    expect(() => writeBrowserObservation(config, {
      outcome: "INCONCLUSIVE",
      metrics: { latencyMs: 1 },
      codes: ["PRIVATE_VALUE_CHANNEL"],
    } as never)).toThrow(/CODES_REJECTED/);
    expect(() => writeBrowserObservation(config, {
      outcome: "HARD_GATE_FAIL",
      metrics: { latencyMs: 1 },
      codes: ["BROWSER_CONTRACT_OBSERVED", "PENDING_AUDIT_RECONCILIATION"],
    })).toThrow(/CODES_REJECTED/);
  });

	it("classifies observed product failures as hard gates and pre-navigation setup failures as inconclusive", () => {
    expect(browserFailureOutcome({
      error: new Error("missing UI"),
      productContractStarted: true,
      externalRequestObserved: false,
    })).toBe("HARD_GATE_FAIL");
    expect(browserFailureOutcome({
      error: new Error("browser failed to launch"),
      productContractStarted: false,
      externalRequestObserved: false,
    })).toBe("INCONCLUSIVE");
		expect(browserFailureOutcome({
			error: new Error("navigation aborted"),
      productContractStarted: false,
      externalRequestObserved: true,
		})).toBe("HARD_GATE_FAIL");
		expect(browserFailureOutcome({
			error: new Error("Target page, context or browser has been closed"),
			productContractStarted: true,
			externalRequestObserved: false,
		})).toBe("INCONCLUSIVE");
	});
});
