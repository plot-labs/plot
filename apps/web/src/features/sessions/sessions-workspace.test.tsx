// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { StrictMode, type ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  search: "",
  listSessions: vi.fn(),
  listReferences: vi.fn(),
  createSession: vi.fn(),
  createGeneration: vi.fn(),
  updateSession: vi.fn(),
  getGeneration: vi.fn(),
  createAndStream: vi.fn(),
  stream: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(mocks.search) }));
vi.mock("@/lib/api-client", () => ({
  plotApiClient: {
    listSessions: mocks.listSessions,
    listGenerationReferences: mocks.listReferences,
    createSession: mocks.createSession,
    createGeneration: mocks.createGeneration,
    updateSession: mocks.updateSession,
    getGeneration: mocks.getGeneration,
    editSentence: vi.fn(),
  },
}));
vi.mock("@/lib/generation-polling", () => ({
  createAndStreamGeneration: mocks.createAndStream,
  streamGeneration: mocks.stream,
  isTerminalGenerationStatus: (status: string) => ["READY", "NEEDS_REVIEW", "NEEDS_YOUR_CALL", "FAILED"].includes(status),
}));
vi.mock("@/features/sessions/session-composer", () => ({
  SessionComposer: ({ onSubmit, variant }: { onSubmit: (message: string, ids: string[]) => void; variant?: string }) => (
    <button type="button" onClick={() => onSubmit("Write release notes", variant === "center" ? ["block-1"] : ["block-2"])}>
      {variant === "center" ? "Start generation" : "Generate again"}
    </button>
  ),
}));
vi.mock("@/features/sessions/session-thread", () => ({ SessionThread: ({ generationPanel }: { generationPanel: ReactNode }) => <div>Thread{generationPanel}</div> }));
vi.mock("@/features/citations/cited-draft-editor", () => ({ CitedDraftEditor: () => <div>Reviewed draft</div> }));
vi.mock("@/features/citations/export-dialog", () => ({ ExportDialog: () => null }));
vi.mock("@/features/sessions/generation-work-log", () => ({ GenerationWorkLog: () => <div>Generation log</div> }));

import { SessionsWorkspace } from "./sessions-workspace";

const session = { id: "session-1", title: "Release", status: "OPEN", latestGenerationId: "run-1", lastActivityAt: "2026-07-01T00:00:00Z", createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z" };
const reference = { id: "block-1", sourceScopeId: "scope-1", provider: "GITHUB", sourceKind: "PULL_REQUEST", sourceLabel: "PR #1", repositoryLabel: "acme/plot", title: "Ship", body: "Evidence", originalUrl: "https://github.test/1", sourceCreatedAt: null };
const terminalRun = {
  id: "run-1", status: "READY", semanticRewriteAttempt: 0, pollAfterMs: null, failureCode: null,
  evidence: [], sentences: [], artifacts: [], pendingIntervention: null,
  contentPack: { id: "pack-1", generationRunId: "run-1", status: "READY", title: "Release", variant: { id: "variant-1", status: "READY", sentences: [] } },
};

describe("SessionsWorkspace", () => {
  beforeEach(() => {
    mocks.search = "";
    Object.values(mocks).forEach((value) => { if (typeof value === "function" && "mockReset" in value) value.mockReset(); });
    mocks.listSessions.mockResolvedValue([]);
    mocks.listReferences.mockResolvedValue([reference]);
    mocks.updateSession.mockResolvedValue(session);
    window.sessionStorage.clear();
    window.history.replaceState(null, "", "/sessions");
  });

  it("shows only actual sessions and the empty state", async () => {
    render(<SessionsWorkspace />);
    expect(await screen.findByText("No sessions yet. Start with a source-backed request.")).toBeVisible();
    expect(screen.queryByText("July changelog")).not.toBeInTheDocument();
  });

  it("creates a real session before generation and stores the latest generation", async () => {
    mocks.createSession.mockResolvedValue({ ...session, latestGenerationId: null });
    mocks.createGeneration.mockResolvedValue({ ...terminalRun, id: "run-new" });
    const assign = vi.fn();
    Object.defineProperty(window, "location", { configurable: true, value: { ...window.location, assign } });

    render(<SessionsWorkspace />);
    await screen.findByRole("button", { name: "Start generation" });
    fireEvent.click(screen.getByRole("button", { name: "Start generation" }));

    await waitFor(() => expect(mocks.updateSession).toHaveBeenCalledWith("session-1", { latestGenerationId: "run-new" }));
    expect(mocks.createSession.mock.invocationCallOrder[0]).toBeLessThan(mocks.createGeneration.mock.invocationCallOrder[0]!);
    expect(mocks.createGeneration.mock.invocationCallOrder[0]).toBeLessThan(mocks.updateSession.mock.invocationCallOrder[0]!);
    expect(assign).toHaveBeenCalledWith("/sessions?session=session-1&generation=run-new");
  });

  it("keeps a created empty session and shows a retry error when generation cannot start", async () => {
    mocks.createSession.mockResolvedValue({ ...session, latestGenerationId: null });
    mocks.createGeneration.mockRejectedValue(new Error("Source unavailable"));
    render(<SessionsWorkspace />);
    await screen.findByRole("button", { name: "Start generation" });
    fireEvent.click(screen.getByRole("button", { name: "Start generation" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Source unavailable");
    expect(mocks.updateSession).not.toHaveBeenCalled();
  });

  it("rejects a home request when no sources are available", async () => {
    mocks.listReferences.mockResolvedValue([]);
    render(<SessionsWorkspace />);
    await screen.findByText(/Connect and import a source/);
    fireEvent.click(screen.getByRole("button", { name: "Start generation" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Connect and import a source");
    expect(mocks.createSession).not.toHaveBeenCalled();
  });

  it("restores the URL generation and retries a missing session pointer once", async () => {
    mocks.search = "session=session-1&generation=run-1";
    mocks.listSessions.mockResolvedValue([{ ...session, latestGenerationId: null }]);
    mocks.getGeneration.mockResolvedValue(terminalRun);
    window.sessionStorage.setItem("plot.session-pointer-repair:session-1", "run-1");
    render(<SessionsWorkspace />);
    expect(await screen.findByText("Reviewed draft")).toBeVisible();
    expect(mocks.getGeneration).toHaveBeenCalledWith("run-1", expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(mocks.updateSession).toHaveBeenCalledWith("session-1", { latestGenerationId: "run-1" });
    expect(window.sessionStorage.getItem("plot.session-pointer-repair:session-1")).toBeNull();
  });

  it("does not repoint a session from an arbitrary generation URL", async () => {
    mocks.search = "session=session-1&generation=run-1";
    mocks.listSessions.mockResolvedValue([{ ...session, latestGenerationId: null }]);
    mocks.getGeneration.mockResolvedValue(terminalRun);
    render(<SessionsWorkspace />);
    expect(await screen.findByText("Reviewed draft")).toBeVisible();
    expect(mocks.updateSession).not.toHaveBeenCalled();
  });

  it("restarts restoration after StrictMode effect replay", async () => {
    mocks.search = "session=session-1&generation=run-1";
    mocks.listSessions.mockResolvedValue([session]);
    mocks.getGeneration.mockResolvedValue(terminalRun);
    render(<StrictMode><SessionsWorkspace /></StrictMode>);
    expect(await screen.findByText("Reviewed draft")).toBeVisible();
    expect(mocks.getGeneration.mock.calls.length).toBeGreaterThanOrEqual(1);
  });
});
