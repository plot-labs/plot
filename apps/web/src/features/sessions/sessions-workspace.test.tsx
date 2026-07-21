// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { StrictMode, type ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  search: "session=session-1",
  replace: vi.fn(),
  listReferences: vi.fn(),
  getGeneration: vi.fn(),
  createAndStream: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mocks.replace }),
  useSearchParams: () => new URLSearchParams(mocks.search),
}));
vi.mock("@/lib/api-client", () => ({
  getSessionsWorkspace: () => ({
    sessions: [{ id: "session-1", title: "Release", subtitle: "Release", updatedAt: "Now", messages: [], draftIds: [], referenceIds: [] }],
    drafts: [],
    references: [],
  }),
  getSelectedDocument: () => null,
  createDemoAgentReply: () => ({ id: "demo", role: "assistant", author: "Plot", timestamp: "Now", content: "Demo" }),
  plotApiClient: { listGenerationReferences: mocks.listReferences, getGeneration: mocks.getGeneration },
}));
vi.mock("@/lib/generation-polling", () => ({
  createAndStreamGeneration: mocks.createAndStream,
  streamGeneration: vi.fn(),
  isTerminalGenerationStatus: () => true,
}));
vi.mock("@/features/sessions/session-composer", () => ({
  SessionComposer: ({ onSubmit }: { onSubmit: (message: string, ids: string[]) => void }) => (
    <button type="button" onClick={() => onSubmit("Write release notes", ["block-1"])}>Generate</button>
  ),
}));
vi.mock("@/features/sessions/session-thread", () => ({ SessionThread: ({ generationPanel }: { generationPanel: ReactNode }) => <div>Thread{generationPanel}</div> }));
vi.mock("@/features/sessions/session-side-panel", () => ({ SessionSidePanel: () => null }));
vi.mock("@/features/citations/cited-draft-editor", () => ({ CitedDraftEditor: () => <div>Reviewed draft</div> }));
vi.mock("@/features/citations/export-dialog", () => ({ ExportDialog: () => null }));

import { SessionsWorkspace } from "./sessions-workspace";

describe("SessionsWorkspace generation orchestration", () => {
  beforeEach(() => {
    mocks.search = "session=session-1";
    mocks.replace.mockReset();
    mocks.listReferences.mockReset();
    mocks.getGeneration.mockReset();
    mocks.createAndStream.mockReset();
    window.history.replaceState(null, "", "/sessions?session=session-1");
  });

  it("discovers a real reference, creates once, and renders the terminal pack", async () => {
    mocks.search = "session=session-1";
    mocks.listReferences.mockResolvedValue([{ id: "block-1", sourceScopeId: "scope-1", provider: "GITHUB", sourceKind: "PULL_REQUEST", sourceLabel: "PR #1", repositoryLabel: "acme/plot", title: "Ship", body: "Evidence", originalUrl: "https://github.test/1", sourceCreatedAt: null }]);
    const terminalRun = {
      id: "run-1", status: "READY", semanticRewriteAttempt: 0, pollAfterMs: null, failureCode: null,
      evidence: [], sentences: [], artifacts: [], pendingIntervention: null,
      contentPack: { id: "pack-1", generationRunId: "run-1", status: "READY", title: "Release", variant: { id: "variant-1", status: "READY", sentences: [] } },
    };
    mocks.createAndStream.mockImplementation(async (_client, _input, _key, options) => {
      options.onUpdate(terminalRun);
      return terminalRun;
    });

    render(<SessionsWorkspace />);
    await waitFor(() => expect(mocks.listReferences).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: "Generate" }));

    await waitFor(() => expect(mocks.createAndStream).toHaveBeenCalledTimes(1));
    expect(mocks.createAndStream.mock.calls[0]?.[1]).toEqual({ sourceScopeId: "scope-1", writingBlockIds: ["block-1"], instruction: "Write release notes" });
    expect(new URLSearchParams(window.location.search).get("generation")).toBe("run-1");
    expect(await screen.findByText("Reviewed draft")).toBeVisible();
  });

  it("does not restore the old conflict decision form for a paused legacy generation", async () => {
    mocks.search = "session=session-1&generation=run-paused";
    mocks.listReferences.mockResolvedValue([]);
    mocks.getGeneration.mockResolvedValue({
      id: "run-paused", status: "NEEDS_YOUR_CALL", semanticRewriteAttempt: 0, pollAfterMs: null, failureCode: null,
      evidence: [], sentences: [], artifacts: [],
      pendingIntervention: { id: "intervention-1", sentenceId: "sentence-1", version: 1, reason: "Conflict", evidenceIds: [] },
      contentPack: null,
    });

    render(<SessionsWorkspace />);

    await waitFor(() => expect(mocks.getGeneration).toHaveBeenCalledWith("run-paused", expect.objectContaining({ signal: expect.any(AbortSignal) })));
    expect(await screen.findByRole("alert")).toHaveTextContent("predates automatic conflict handling");
    expect(screen.queryByRole("heading", { name: "Needs your call" })).not.toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Generation status: Needs your call" })).toBeVisible();
  });

  it("restarts URL restoration after the StrictMode effect replay", async () => {
    mocks.search = "session=session-1&generation=run-paused";
    mocks.listReferences.mockResolvedValue([]);
    mocks.getGeneration.mockResolvedValue({
      id: "run-paused", status: "NEEDS_YOUR_CALL", semanticRewriteAttempt: 0, pollAfterMs: null, failureCode: null,
      evidence: [], sentences: [], artifacts: [],
      pendingIntervention: { id: "intervention-1", sentenceId: "sentence-1", version: 1, reason: "Conflict", evidenceIds: [] },
      contentPack: null,
    });

    render(<StrictMode><SessionsWorkspace /></StrictMode>);

    expect(await screen.findByRole("alert")).toHaveTextContent("predates automatic conflict handling");
    expect(screen.queryByRole("heading", { name: "Needs your call" })).not.toBeInTheDocument();
    expect(mocks.getGeneration).toHaveBeenCalledTimes(2);
  });

  it("shows an explicit reviewer failure while preserving the partial draft", async () => {
    mocks.search = "session=session-1&generation=run-review-failed";
    mocks.listReferences.mockResolvedValue([]);
    mocks.getGeneration.mockResolvedValue({
      id: "run-review-failed", status: "NEEDS_REVIEW", semanticRewriteAttempt: 1, pollAfterMs: null,
      failureCode: "MALFORMED_OUTPUT", evidence: [], sentences: [], artifacts: [], pendingIntervention: null,
      contentPack: {
        id: "pack-failed", generationRunId: "run-review-failed", status: "NEEDS_REVIEW", title: "Partial draft",
        variant: { id: "variant-failed", status: "NEEDS_REVIEW", sentences: [] },
      },
    });

    render(<SessionsWorkspace />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Review failed (MALFORMED_OUTPUT)");
    expect(screen.getByText("Reviewed draft")).toBeVisible();
  });
});
