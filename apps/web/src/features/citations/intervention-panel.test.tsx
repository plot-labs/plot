// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { InterventionPanel } from "./intervention-panel";
import type { GenerationRun } from "@plot/api-client";

const run: GenerationRun = {
  id: "run-1",
  status: "NEEDS_YOUR_CALL",
  semanticRewriteAttempt: 1,
  pollAfterMs: null,
  failureCode: null,
  sentences: [],
  artifacts: [],
  contentPack: null,
  evidence: [
    { id: "ev-1", provider: "GITHUB", sourceKind: "PULL_REQUEST", sourceLabel: "PR #184", originalUrl: "https://github.com/a/b/pull/184", snapshotExcerpt: "assisted drafting", contentHash: "a" },
    { id: "ev-2", provider: "SLACK", sourceKind: "MESSAGE", sourceLabel: "#launch", originalUrl: "https://example.slack.com/archives/1", snapshotExcerpt: "automatic preparation", contentHash: "b" },
  ],
  pendingIntervention: {
    id: "intervention-1",
    sentenceId: "sentence-1",
    version: 3,
    reason: "Sources disagree about automation.",
    evidenceIds: ["ev-1", "ev-2"],
  },
};

describe("InterventionPanel", () => {
  it.each([
    ["Omit this claim", { expectedVersion: 3, action: "OMIT_CLAIM" }],
    ["Prefer a source", { expectedVersion: 3, action: "PREFER_SOURCE", preferredEvidenceId: "ev-1" }],
  ])("submits the %s resolution", async (label, expected) => {
    const onResolve = vi.fn().mockResolvedValue({ ...run, status: "REWRITING", pendingIntervention: null });
    render(<InterventionPanel run={run} onResolve={onResolve} />);
    fireEvent.click(screen.getByRole("radio", { name: label }));
    if (label === "Prefer a source") fireEvent.click(screen.getByRole("radio", { name: /GitHub · PR #184/i }));
    fireEvent.click(screen.getByRole("button", { name: /resolve and continue/i }));
    await waitFor(() => expect(onResolve).toHaveBeenCalledWith(expected));
  });

  it("submits exact user wording once and disables duplicate resolution", async () => {
    let release!: (value: GenerationRun) => void;
    const pending = new Promise<GenerationRun>((resolve) => { release = resolve; });
    const onResolve = vi.fn().mockReturnValue(pending);
    render(<InterventionPanel run={run} onResolve={onResolve} />);

    expect(screen.getByRole("heading", { name: "Needs your call" })).toBeVisible();
    fireEvent.click(screen.getByRole("radio", { name: /provide exact wording/i }));
    fireEvent.change(screen.getByRole("textbox", { name: /exact sentence wording/i }), {
      target: { value: "Plot assists teams in preparing release updates." },
    });
    const submit = screen.getByRole("button", { name: /resolve and continue/i });
    fireEvent.click(submit);
    fireEvent.click(submit);

    expect(onResolve).toHaveBeenCalledTimes(1);
    expect(onResolve).toHaveBeenCalledWith({
      expectedVersion: 3,
      action: "PROVIDE_WORDING",
      providedWording: "Plot assists teams in preparing release updates.",
    });
    expect(submit).toBeDisabled();
    release({ ...run, status: "REWRITING", pendingIntervention: null });
    await waitFor(() => expect(screen.getByText(/resolution accepted/i)).toBeVisible());
  });
});
