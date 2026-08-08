// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  search: "",
  listSessions: vi.fn(),
  listReferences: vi.fn(),
  listSessionGenerations: vi.fn(),
  createSession: vi.fn(),
  createGeneration: vi.fn(),
  getGeneration: vi.fn(),
  replace: vi.fn(),
  locationAssign: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(mocks.search),
  useRouter: () => ({ replace: mocks.replace }),
}));
vi.mock("@/lib/api-client", () => ({
  plotApiClient: {
    listSessions: mocks.listSessions,
    listGenerationReferences: mocks.listReferences,
    listSessionGenerations: mocks.listSessionGenerations,
    createSession: mocks.createSession,
    createGeneration: mocks.createGeneration,
    getGeneration: mocks.getGeneration,
    saveArtifactVariant: vi.fn(),
  },
}));
vi.mock("@/lib/generation-polling", () => ({
  pollGeneration: mocks.getGeneration,
  isTerminalGenerationStatus: (status: string) => ["READY", "NEEDS_REVIEW", "FAILED"].includes(status),
}));
vi.mock("@/features/chat/chat-composer", () => ({
  ChatComposer: ({ onSubmit, variant }: { onSubmit: (message: string, ids: string[]) => void; variant?: string }) => (
    <button type="button" onClick={() => onSubmit("Write release notes", ["block-1"])}>
      {variant === "center" ? "Start generation" : "Generate again"}
    </button>
  ),
}));
vi.mock("@/features/citations/cited-draft-editor", () => ({ CitedDraftEditor: () => <div>Reviewed artifact</div> }));
vi.mock("@/features/citations/export-dialog", () => ({ ExportDialog: () => null }));
vi.mock("@/features/citations/artifact-history-panel", () => ({ ArtifactHistoryPanel: () => <div>History</div> }));
vi.mock("@/features/chat/generation-work-log", () => ({ GenerationWorkLog: () => <div>Generation log</div> }));

import { ChatWorkspace } from "./chat-workspace";

const chat = { id: "chat-1", title: "Release", status: "OPEN", latestGenerationId: "run-1", lastActivityAt: "2026-07-01T00:00:00Z", createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z" };
const reference = { id: "block-1", sourceScopeId: "scope-1", provider: "GITHUB", sourceKind: "PULL_REQUEST", sourceLabel: "PR #1", repositoryLabel: "acme/plot", title: "Ship", body: "Evidence", originalUrl: "https://github.test/1", sourceCreatedAt: null };
const artifactSummary = { id: "artifact-1", generationRunId: "run-1", status: "READY", title: "Release" };
const terminalRun = {
  id: "run-1", status: "READY", semanticRewriteAttempt: 0, pollAfterMs: null, failureCode: null,
  evidence: [], sentences: [], artifacts: [], workSessionId: "chat-1",
  artifact: { id: "artifact-1", generationRunId: "run-1", status: "READY", title: "Release", variant: { id: "variant-1", status: "READY", revisionId: "artifact-revision-1", revisionNumber: 1, lexicalContent: { root: { children: [], type: "root", version: 1 } }, sentences: [], sources: [] } },
};

describe("ChatWorkspace", () => {
  beforeEach(() => {
    mocks.search = "";
    Object.values(mocks).forEach((value) => { if (typeof value === "function" && "mockReset" in value) value.mockReset(); });
    mocks.listSessions.mockResolvedValue([]);
    mocks.listReferences.mockResolvedValue([reference]);
    mocks.listSessionGenerations.mockResolvedValue([]);
    mocks.replace.mockImplementation(() => undefined);
    window.sessionStorage.clear();
    Object.defineProperty(window, "location", { configurable: true, value: { ...window.location, assign: mocks.locationAssign } });
  });

  it("keeps the chat home focused on starting a request", async () => {
    render(<ChatWorkspace />);
    await screen.findByRole("button", { name: "Start generation" });
    expect(screen.getByRole("button", { name: "Start generation" })).toBeVisible();
    expect(screen.queryByText("No chats yet. Start with a source-backed request.")).not.toBeInTheDocument();
  });

  it("creates a chat-owned generation without browser pointer repair", async () => {
    mocks.createSession.mockResolvedValue({ ...chat, latestGenerationId: null });
    mocks.createGeneration.mockResolvedValue({ ...terminalRun, id: "run-new", artifact: null });

    render(<ChatWorkspace />);
    await screen.findByRole("button", { name: "Start generation" });
    fireEvent.click(screen.getByRole("button", { name: "Start generation" }));

    await waitFor(() => expect(mocks.createGeneration).toHaveBeenCalledWith({
      sourceScopeId: "scope-1",
      writingBlockIds: ["block-1"],
      instruction: "Write release notes",
      workSessionId: "chat-1",
    }, expect.any(String)));
    expect(mocks.locationAssign).toHaveBeenCalledWith("/chat?chat=chat-1&generation=run-new");
    expect(window.sessionStorage.length).toBe(0);
  });

  it("keeps a created empty chat and shows a retry error when generation cannot start", async () => {
    mocks.createSession.mockResolvedValue({ ...chat, latestGenerationId: null });
    mocks.createGeneration.mockRejectedValue(new Error("Source unavailable"));
    render(<ChatWorkspace />);
    await screen.findByRole("button", { name: "Start generation" });
    fireEvent.click(screen.getByRole("button", { name: "Start generation" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Source unavailable");
  });

  it("shows all chat generations while rendering only artifacts for completed activity", async () => {
    mocks.search = "chat=chat-1&generation=run-1";
    mocks.listSessions.mockResolvedValue([chat]);
    mocks.listSessionGenerations.mockResolvedValue([
      { id: "run-queued", status: "QUEUED", instruction: "Customer update", createdAt: "2026-07-01T00:00:00Z", completedAt: null, failureCode: null, artifact: null },
      { id: "run-1", status: "READY", instruction: "Release notes", createdAt: "2026-07-01T00:01:00Z", completedAt: "2026-07-01T00:02:00Z", failureCode: null, artifact: artifactSummary },
      { id: "run-failed", status: "FAILED", instruction: "Internal note", createdAt: "2026-07-01T00:03:00Z", completedAt: "2026-07-01T00:04:00Z", failureCode: "SOURCE_UNAVAILABLE", artifact: null },
    ]);
    mocks.getGeneration.mockResolvedValue(terminalRun);
    render(<ChatWorkspace />);

    expect(await screen.findByText("Reviewed artifact")).toBeVisible();
    expect(screen.getAllByRole("button", { name: /Customer update/ })[0]).toBeVisible();
    expect(screen.getAllByRole("button", { name: /Internal note/ })[0]).toHaveTextContent("No artifact produced");
    expect(screen.getAllByText("1 artifact")[0]).toBeVisible();
  });

  it("starts a follow-up generation with the active chat linkage and no pointer update", async () => {
    mocks.search = "chat=chat-1&generation=run-1";
    mocks.listSessions.mockResolvedValue([chat]);
    mocks.listSessionGenerations.mockResolvedValue([{ id: "run-1", status: "READY", instruction: "Release notes", createdAt: "2026-07-01T00:01:00Z", completedAt: "2026-07-01T00:02:00Z", failureCode: null, artifact: artifactSummary }]);
    mocks.getGeneration.mockResolvedValue(terminalRun);
    mocks.createGeneration.mockResolvedValue({ ...terminalRun, id: "run-2", artifact: null });
    render(<ChatWorkspace />);
    expect(await screen.findByText("Reviewed artifact")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "Generate again" }));
    await waitFor(() => expect(mocks.createGeneration).toHaveBeenCalledWith(expect.objectContaining({ workSessionId: "chat-1" }), expect.any(String), expect.any(Object)));
    expect(mocks.replace).toHaveBeenCalledWith("/chat?chat=chat-1&generation=run-2", { scroll: false });
    expect(window.sessionStorage.length).toBe(0);
  });

  it("does not render a generation from a different chat", async () => {
    mocks.search = "chat=chat-1&generation=run-other";
    mocks.listSessions.mockResolvedValue([chat]);
    mocks.listSessionGenerations.mockResolvedValue([]);
    mocks.getGeneration.mockResolvedValue({ ...terminalRun, id: "run-other", workSessionId: "chat-2" });
    render(<ChatWorkspace />);
    expect(await screen.findByRole("alert")).toHaveTextContent("not part of this chat");
    expect(screen.queryByText("Reviewed artifact")).not.toBeInTheDocument();
  });

  it("opens the mobile History panel and restores focus when it closes", async () => {
    mocks.search = "chat=chat-1&generation=run-1";
    mocks.listSessions.mockResolvedValue([chat]);
    mocks.listSessionGenerations.mockResolvedValue([{ id: "run-1", status: "READY", instruction: "Release notes", createdAt: "2026-07-01T00:01:00Z", completedAt: "2026-07-01T00:02:00Z", failureCode: null, artifact: artifactSummary }]);
    mocks.getGeneration.mockResolvedValue(terminalRun);
    render(<ChatWorkspace />);
    await screen.findByText("Reviewed artifact");

    const historyTrigger = screen.getAllByRole("tab", { name: "History" }).find((element) => element.getAttribute("aria-controls") === "mobile-chat-history-panel");
    expect(historyTrigger).toBeDefined();
    fireEvent.click(historyTrigger!);
    expect(await screen.findByRole("tabpanel", { name: "History panel" })).toBeVisible();
    fireEvent.click(historyTrigger!);
    await waitFor(() => expect(document.activeElement).toBe(historyTrigger));
  });
});
