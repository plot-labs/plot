// @vitest-environment jsdom

import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { GenerationRun } from "@plot/api-client";
import { GenerationWorkLog } from "./generation-work-log";

const run: GenerationRun = {
  id: "run-1",
  status: "READY",
  semanticRewriteAttempt: 1,
  pollAfterMs: null,
  failureCode: null,
  evidence: [
    {
      id: "evidence-1",
      provider: "GITHUB",
      sourceKind: "PULL_REQUEST",
      sourceLabel: "PR #184",
      originalUrl: "https://github.test/pr/184",
      snapshotExcerpt: "Auth copy improvements",
      contentHash: "hash",
    },
  ],
  sentences: [
    { id: "sentence-1", revisionId: "revision-1", revisionNumber: 1, orderIndex: 0, body: "Supported claim.", origin: "GENERATED", verdict: "SUPPORTED", reason: null, citations: [] },
    { id: "sentence-2", revisionId: "revision-2", revisionNumber: 2, orderIndex: 1, body: "Rewritten claim.", origin: "REWRITTEN", verdict: "SUPPORTED", reason: null, citations: [] },
  ],
  artifacts: [
    { kind: "WRITER_OUTPUT", sequence: 0, sentenceIds: ["sentence-1", "sentence-2"], reviews: [], detail: null },
    { kind: "REVIEWER_OUTPUT", sequence: 1, sentenceIds: ["sentence-1", "sentence-2"], reviews: [
      { sentenceId: "sentence-1", verdict: "SUPPORTED", evidenceIds: ["evidence-1"], reason: null },
      { sentenceId: "sentence-2", verdict: "NEEDS_SUPPORT", evidenceIds: [], reason: "Needs a source" },
    ], detail: null },
    { kind: "REWRITER_OUTPUT", sequence: 2, sentenceIds: ["sentence-2"], reviews: [], detail: null },
  ],
  pendingIntervention: null,
  contentPack: null,
  timing: {
    createdAt: "2026-07-21T10:00:00Z",
    startedAt: "2026-07-21T10:00:01Z",
    finishedAt: "2026-07-21T10:01:13Z",
    steps: [
      { kind: "WRITER", sequence: 0, status: "SUCCEEDED", startedAt: "2026-07-21T10:00:01Z", finishedAt: "2026-07-21T10:00:05Z", durationMs: 4000, failureCode: null },
      { kind: "REVIEWER", sequence: 1, status: "SUCCEEDED", startedAt: "2026-07-21T10:00:05Z", finishedAt: "2026-07-21T10:01:00Z", durationMs: 55000, failureCode: null },
      { kind: "REWRITER", sequence: 2, status: "SUCCEEDED", startedAt: "2026-07-21T10:01:00Z", finishedAt: "2026-07-21T10:01:13Z", durationMs: 13000, failureCode: null },
    ],
    model: { modelName: "gpt-5-mini", totalTokens: 12403, totalLatencyMs: 72000 },
  },
};

describe("GenerationWorkLog", () => {
  it("renders grounded execution steps and model summary", () => {
    render(<GenerationWorkLog run={run} />);

    expect(screen.getByText("Worked for 1m 12s")).toBeVisible();
    expect(screen.getByText("Loaded 1 reference")).toBeVisible();
    expect(screen.getByText("Drafted 2 sentences · 4s")).toBeVisible();
    expect(screen.getByText("Checked source support — 1/2 supported · 55s")).toBeVisible();
    expect(screen.getByText("Rewrote 1 sentence (attempt 1) · 13s")).toBeVisible();
    expect(screen.getByText("gpt-5-mini · 12.4k tokens")).toBeVisible();
  });

  it("does not present a failed run as successful", () => {
    render(<GenerationWorkLog run={{ ...run, status: "FAILED", failureCode: "MODEL_TIMEOUT" }} />);

    expect(screen.getByText("Generation failed after 1m 12s")).toBeVisible();
    expect(screen.queryByText("Worked for 1m 12s")).not.toBeInTheDocument();
  });
});
