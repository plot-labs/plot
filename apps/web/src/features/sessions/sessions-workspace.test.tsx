// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  listReferences: vi.fn(),
  createAndPoll: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams("session=session-1") }));
vi.mock("@/lib/api-client", () => ({
  getSessionsWorkspace: () => ({
    sessions: [{ id: "session-1", title: "Release", subtitle: "Release", updatedAt: "Now", messages: [], draftIds: [], referenceIds: [] }],
    drafts: [],
    references: [],
  }),
  getSelectedDocument: () => null,
  createDemoAgentReply: () => ({ id: "demo", role: "assistant", author: "Plot", timestamp: "Now", content: "Demo" }),
  plotApiClient: { listGenerationReferences: mocks.listReferences },
}));
vi.mock("@/lib/generation-polling", () => ({
  createAndPollGeneration: mocks.createAndPoll,
  pollGeneration: vi.fn(),
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
vi.mock("@/features/citations/intervention-panel", () => ({ InterventionPanel: () => null }));

import { SessionsWorkspace } from "./sessions-workspace";

describe("SessionsWorkspace generation orchestration", () => {
  it("discovers a real reference, creates once, and renders the terminal pack", async () => {
    mocks.listReferences.mockResolvedValue([{ id: "block-1", sourceScopeId: "scope-1", provider: "GITHUB", sourceKind: "PULL_REQUEST", sourceLabel: "PR #1", repositoryLabel: "acme/plot", title: "Ship", body: "Evidence", originalUrl: "https://github.test/1", sourceCreatedAt: null }]);
    mocks.createAndPoll.mockResolvedValue({
      id: "run-1", status: "READY", semanticRewriteAttempt: 0, pollAfterMs: null, failureCode: null,
      evidence: [], sentences: [], artifacts: [], pendingIntervention: null,
      contentPack: { id: "pack-1", generationRunId: "run-1", status: "READY", title: "Release", variant: { id: "variant-1", status: "READY", sentences: [] } },
    });

    render(<SessionsWorkspace />);
    await waitFor(() => expect(mocks.listReferences).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: "Generate" }));

    await waitFor(() => expect(mocks.createAndPoll).toHaveBeenCalledTimes(1));
    expect(mocks.createAndPoll.mock.calls[0]?.[1]).toEqual({ sourceScopeId: "scope-1", writingBlockIds: ["block-1"], instruction: "Write release notes" });
    expect(await screen.findByText("Reviewed draft")).toBeVisible();
  });
});
