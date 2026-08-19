// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  search: "",
  listSessions: vi.fn(),
  listReferences: vi.fn(),
  listSessionAgentRuns: vi.fn(),
  createChatAgentRun: vi.fn(),
  getChatAgentRun: vi.fn(),
  pollChatAgentRun: vi.fn(),
  getArtifact: vi.fn(),
  replace: vi.fn(),
  locationAssign: vi.fn(),
}));
const router = { replace: mocks.replace };

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(mocks.search),
  useRouter: () => router,
}));
vi.mock("@/lib/api-client", () => ({
  plotApiClient: {
    listSessions: mocks.listSessions,
    listSourceReferences: mocks.listReferences,
    listSessionAgentRuns: mocks.listSessionAgentRuns,
    createChatAgentRun: mocks.createChatAgentRun,
    getChatAgentRun: mocks.getChatAgentRun,
    getArtifact: mocks.getArtifact,
    saveArtifactVariant: vi.fn(),
  },
}));
vi.mock("@/lib/chat-agent-polling", () => ({
  pollChatAgentRun: mocks.pollChatAgentRun,
  isTerminalChatAgentStatus: (status: string) => ["SUCCEEDED", "FAILED"].includes(status),
}));
vi.mock("@/features/chat/chat-composer", () => ({
  ChatComposer: ({ onSubmit, variant }: { onSubmit: (message: string, ids: string[]) => void; variant?: string }) => (
    <button type="button" onClick={() => onSubmit("Write release notes", ["block-1"])}>
      {variant === "center" ? "Start request" : "Generate again"}
    </button>
  ),
}));
vi.mock("@/features/citations/cited-draft-editor", () => ({ CitedDraftEditor: () => <div>Reviewed artifact</div> }));
vi.mock("@/features/citations/export-dialog", () => ({ ExportDialog: () => null }));
vi.mock("@/features/citations/artifact-history-panel", () => ({ ArtifactHistoryPanel: () => <div>History</div> }));

import { ChatWorkspace } from "./chat-workspace";

const chat = { id: "chat-1", title: "Release", status: "OPEN", lastActivityAt: "2026-07-01T00:00:00Z", createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z" };
const reference = { id: "block-1", sourceScopeId: "scope-1", provider: "GITHUB", sourceKind: "PULL_REQUEST", sourceLabel: "PR #1", repositoryLabel: "acme/plot", title: "Ship", body: "Evidence", originalUrl: "https://github.test/1", sourceCreatedAt: null };
const artifactSummary = { id: "artifact-1", status: "READY", title: "Release", updatedAt: "2026-07-01T00:02:00Z" };
const artifact = {
  id: "artifact-1", status: "READY", title: "Release",
  variant: { id: "variant-1", status: "READY", revisionId: "artifact-revision-1", revisionNumber: 1, lexicalContent: { root: { children: [], type: "root", version: 1 } }, sentences: [], sources: [] },
};

function agentRun(overrides: Record<string, unknown> = {}) {
  return {
    id: "agent-1", chatId: "chat-1", instruction: "Release notes", status: "QUEUED", failureCode: null,
    artifactId: null, artifact: null, createdAt: "2026-07-01T00:01:00Z", updatedAt: "2026-07-01T00:01:00Z", ...overrides,
  };
}

describe("ChatWorkspace", () => {
  beforeEach(() => {
    mocks.search = "";
    Object.values(mocks).forEach((value) => { if (typeof value === "function" && "mockReset" in value) value.mockReset(); });
    mocks.listSessions.mockResolvedValue([]);
    mocks.listReferences.mockResolvedValue([reference]);
    mocks.listSessionAgentRuns.mockResolvedValue([]);
    mocks.pollChatAgentRun.mockImplementation(async (_client: unknown, id: string, options: { onUpdate?: (run: unknown) => void }) => {
      const next = await mocks.getChatAgentRun(id);
      options.onUpdate?.(next);
      return next;
    });
    mocks.getArtifact.mockResolvedValue(artifact);
    mocks.replace.mockImplementation(() => undefined);
    window.sessionStorage.clear();
    Object.defineProperty(window, "location", { configurable: true, value: { ...window.location, assign: mocks.locationAssign } });
  });

  it("keeps the Chat home focused on starting an Agent request", async () => {
    render(<ChatWorkspace />);
    await screen.findByRole("button", { name: "Start request" });
    expect(screen.queryByText("No chats yet. Start with a source-backed request.")).not.toBeInTheDocument();
  });

  it("admits one Chat Agent request and navigates without a direct artifact workflow call", async () => {
    mocks.createChatAgentRun.mockResolvedValue(agentRun({ id: "agent-new", chatId: "chat-new" }));
    render(<ChatWorkspace />);
    await waitFor(() => expect(screen.queryByText("Loading sources…")).not.toBeInTheDocument());
    fireEvent.click(await screen.findByRole("button", { name: "Start request" }));

    await waitFor(() => expect(mocks.createChatAgentRun).toHaveBeenCalledWith({
      writingBlockIds: ["block-1"], instruction: "Write release notes",
    }, expect.any(String)));
    expect(mocks.createChatAgentRun).toHaveBeenCalledTimes(1);
    expect(mocks.locationAssign).toHaveBeenCalledWith("/chat?chat=chat-new&agent=agent-new");
    expect(window.sessionStorage.length).toBe(0);
  });

  it("reuses the pending idempotency key after an admission response is lost", async () => {
    mocks.createChatAgentRun.mockRejectedValueOnce(new TypeError("Network request failed")).mockResolvedValueOnce(agentRun({ id: "agent-retry", chatId: "chat-retry" }));
    render(<ChatWorkspace />);
    await waitFor(() => expect(screen.queryByText("Loading sources…")).not.toBeInTheDocument());
    const start = await screen.findByRole("button", { name: "Start request" });
    fireEvent.click(start);
    await waitFor(() => expect(mocks.createChatAgentRun).toHaveBeenCalledTimes(1));
    fireEvent.click(start);

    await waitFor(() => expect(mocks.createChatAgentRun).toHaveBeenCalledTimes(2));
    expect(mocks.createChatAgentRun.mock.calls[0]![1]).toBe(mocks.createChatAgentRun.mock.calls[1]![1]);
  });

  it("loads Chat Agent activity and renders its Artifact", async () => {
    mocks.search = "chat=chat-1&agent=agent-1";
    mocks.listSessions.mockResolvedValue([chat]);
    const succeeded = agentRun({ status: "SUCCEEDED", artifactId: "artifact-1", artifact: artifactSummary });
    mocks.listSessionAgentRuns.mockResolvedValue([succeeded]);
    mocks.getChatAgentRun.mockResolvedValue(succeeded);

    render(<ChatWorkspace />);
    expect(await screen.findByText("Reviewed artifact")).toBeVisible();
    expect(screen.getByRole("button", { name: /ReleaseArtifact available/ })).toBeVisible();
    await waitFor(() => expect(document.querySelectorAll("time")).toHaveLength(1));
    expect(screen.getByText("Source agent")).toBeVisible();
  });

  it("restores generated activity when History opens a session without an Agent query", async () => {
    mocks.search = "chat=chat-1";
    mocks.listSessions.mockResolvedValue([chat]);
    const succeeded = agentRun({ status: "SUCCEEDED", artifactId: "artifact-1", artifact: artifactSummary });
    mocks.listSessionAgentRuns.mockResolvedValue([succeeded]);

    render(<ChatWorkspace />);
    expect(await screen.findByText("Reviewed artifact")).toBeVisible();
    expect(screen.getByText("Source agent")).toBeVisible();
    expect(mocks.getChatAgentRun).not.toHaveBeenCalled();
  });

  it("starts a follow-up Agent request with the active Chat linkage", async () => {
    mocks.search = "chat=chat-1&agent=agent-1";
    mocks.listSessions.mockResolvedValue([chat]);
    const succeeded = agentRun({ status: "SUCCEEDED", artifactId: "artifact-1", artifact: artifactSummary });
    mocks.listSessionAgentRuns.mockResolvedValue([succeeded]);
    mocks.getChatAgentRun.mockResolvedValue(succeeded);
    mocks.createChatAgentRun.mockResolvedValue(agentRun({ id: "agent-2", status: "QUEUED", artifactId: null, artifact: null }));

    render(<ChatWorkspace />);
    await screen.findByText("Reviewed artifact");
    await waitFor(() => expect(screen.queryByText("Loading sources…")).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Generate again" }));

    await waitFor(() => expect(mocks.createChatAgentRun).toHaveBeenCalled());
    expect(mocks.createChatAgentRun).toHaveBeenCalledWith(expect.objectContaining({ workSessionId: "chat-1", writingBlockIds: ["block-1"] }), expect.any(String), expect.any(Object));
    expect(mocks.replace).toHaveBeenCalledWith("/chat?chat=chat-1&agent=agent-2", { scroll: false });
  });

  it("returns to the Chat home when the workspace changes", async () => {
    mocks.search = "chat=chat-1&agent=agent-1";
    mocks.listSessions.mockResolvedValue([chat]);
    const succeeded = agentRun({ status: "SUCCEEDED", artifactId: "artifact-1", artifact: artifactSummary });
    mocks.listSessionAgentRuns.mockResolvedValue([succeeded]);
    mocks.getChatAgentRun.mockResolvedValue(succeeded);
    render(<ChatWorkspace />);
    await screen.findByText("Reviewed artifact");

    window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: "workspace-2" } }));
    await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith("/chat", { scroll: false }));
  });

  it("opens the mobile History panel and restores focus when it closes", async () => {
    mocks.search = "chat=chat-1&agent=agent-1";
    mocks.listSessions.mockResolvedValue([chat]);
    const succeeded = agentRun({ status: "SUCCEEDED", artifactId: "artifact-1", artifact: artifactSummary });
    mocks.listSessionAgentRuns.mockResolvedValue([succeeded]);
    mocks.getChatAgentRun.mockResolvedValue(succeeded);
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
