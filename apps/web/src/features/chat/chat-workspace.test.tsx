// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  search: "",
  listSessions: vi.fn(),
  listReferences: vi.fn(),
  listSessionGenerations: vi.fn(),
  createChatAgentRun: vi.fn(),
  createGeneration: vi.fn(),
  getChatAgentRun: vi.fn(),
  pollChatAgentRun: vi.fn(),
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
    createChatAgentRun: mocks.createChatAgentRun,
    createGeneration: mocks.createGeneration,
    getChatAgentRun: mocks.getChatAgentRun,
    getGeneration: mocks.getGeneration,
    saveArtifactVariant: vi.fn(),
  },
}));
vi.mock("@/lib/generation-polling", () => ({
  pollGeneration: mocks.getGeneration,
  isTerminalGenerationStatus: (status: string) => ["READY", "NEEDS_REVIEW", "FAILED"].includes(status),
}));
vi.mock("@/lib/chat-agent-polling", () => ({
  pollChatAgentRun: mocks.pollChatAgentRun,
  isTerminalChatAgentStatus: (status: string) => ["SUCCEEDED", "FAILED"].includes(status),
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
    mocks.pollChatAgentRun.mockImplementation(async (_client: unknown, id: string, options: { onUpdate?: (run: unknown) => void }) => {
      const next = await mocks.getChatAgentRun(id);
      options.onUpdate?.(next);
      return next;
    });
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

  it("admits one Chat Agent request and navigates to the returned Chat without a Generation call", async () => {
    mocks.createChatAgentRun.mockResolvedValue({ id: "agent-new", chatId: "chat-new", status: "QUEUED", failureCode: null, generationRunId: null, artifactId: null, createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z" });

    render(<ChatWorkspace />);
    await screen.findByRole("button", { name: "Start generation" });
    fireEvent.click(screen.getByRole("button", { name: "Start generation" }));

    await waitFor(() => expect(mocks.createChatAgentRun).toHaveBeenCalledWith({
      writingBlockIds: ["block-1"],
      instruction: "Write release notes",
    }, expect.any(String)));
    expect(mocks.createGeneration).not.toHaveBeenCalled();
    expect(mocks.createChatAgentRun).toHaveBeenCalledTimes(1);
    expect(mocks.locationAssign).toHaveBeenCalledWith("/chat?chat=chat-new&agent=agent-new");
    expect(window.sessionStorage.length).toBe(0);
  });

  it("shows a retry error when Chat Agent admission cannot start", async () => {
    mocks.createChatAgentRun.mockRejectedValue(new Error("Source unavailable"));
    render(<ChatWorkspace />);
    await screen.findByRole("button", { name: "Start generation" });
    fireEvent.click(screen.getByRole("button", { name: "Start generation" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Source unavailable");
  });

  it("reuses the pending idempotency key after an admission response is lost", async () => {
    const admitted = { id: "agent-retry", chatId: "chat-retry", status: "QUEUED", failureCode: null, generationRunId: null, artifactId: null, createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z" };
    mocks.createChatAgentRun.mockRejectedValueOnce(new TypeError("Network request failed")).mockResolvedValueOnce(admitted);
    render(<ChatWorkspace />);
    await waitFor(() => expect(screen.queryByText("Loading sources…")).not.toBeInTheDocument());
    const start = screen.getByRole("button", { name: "Start generation" });

    fireEvent.click(start);
    await screen.findByRole("alert");
    fireEvent.click(start);

    await waitFor(() => expect(mocks.createChatAgentRun).toHaveBeenCalledTimes(2));
    expect(mocks.createChatAgentRun.mock.calls[0]![1]).toBe(mocks.createChatAgentRun.mock.calls[1]![1]);
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

  it("starts a follow-up Agent request with the active Chat linkage and no pointer update", async () => {
    mocks.search = "chat=chat-1&generation=run-1";
    mocks.listSessions.mockResolvedValue([chat]);
    mocks.listSessionGenerations.mockResolvedValue([{ id: "run-1", status: "READY", instruction: "Release notes", createdAt: "2026-07-01T00:01:00Z", completedAt: "2026-07-01T00:02:00Z", failureCode: null, artifact: artifactSummary }]);
    mocks.getGeneration.mockResolvedValue(terminalRun);
    mocks.createChatAgentRun.mockResolvedValue({ id: "agent-2", chatId: "chat-1", status: "QUEUED", failureCode: null, generationRunId: null, artifactId: null, createdAt: "2026-07-01T00:03:00Z", updatedAt: "2026-07-01T00:03:00Z" });
    render(<ChatWorkspace />);
    expect(await screen.findByText("Reviewed artifact")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "Generate again" }));
    await waitFor(() => expect(mocks.createChatAgentRun).toHaveBeenCalledWith(expect.objectContaining({ workSessionId: "chat-1", writingBlockIds: ["block-1"] }), expect.any(String), expect.any(Object)));
    expect(mocks.createChatAgentRun).toHaveBeenCalledTimes(1);
    expect(mocks.replace).toHaveBeenCalledWith("/chat?chat=chat-1&agent=agent-2", { scroll: false });
    expect(window.sessionStorage.length).toBe(0);
  });

  it("shows queued Agent activity and routes to the linked Generation after handoff", async () => {
    mocks.search = "chat=chat-1&agent=agent-1";
    mocks.listSessions.mockResolvedValue([chat]);
    mocks.listSessionGenerations.mockResolvedValue([]);
    mocks.getChatAgentRun
      .mockResolvedValueOnce({ id: "agent-1", chatId: "chat-1", status: "QUEUED", failureCode: null, generationRunId: null, artifactId: null, createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z" })
      .mockResolvedValueOnce({ id: "agent-1", chatId: "chat-1", status: "SUCCEEDED", failureCode: null, generationRunId: "run-2", artifactId: null, createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:01:00Z" });
    render(<ChatWorkspace />);

    expect(await screen.findByRole("region", { name: "Agent request details" })).toBeVisible();
    await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith("/chat?chat=chat-1&generation=run-2", { scroll: false }));
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

  it("returns to the chat home when the workspace changes", async () => {
    mocks.search = "chat=chat-1&generation=run-1";
    mocks.listSessions.mockResolvedValue([chat]);
    mocks.listSessionGenerations.mockResolvedValue([]);
    mocks.getGeneration.mockResolvedValue(terminalRun);
    render(<ChatWorkspace />);
    await screen.findByText("Reviewed artifact");

    window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: "workspace-2" } }));

    await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith("/chat", { scroll: false }));
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
