// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  search: "",
  listSessions: vi.fn(),
  listReferences: vi.fn(),
  listSessionAgentRuns: vi.fn(),
  listChatTurns: vi.fn(),
  retryChatResponse: vi.fn(),
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
    listChatTurns: mocks.listChatTurns,
    retryChatResponse: mocks.retryChatResponse,
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
  ChatComposer: ({ onSubmit, variant, busy }: { onSubmit: (message: string, ids: string[], skillIds: string[]) => void; variant?: string; busy?: boolean }) => (
	<button type="button" disabled={busy} onClick={() => onSubmit("Write release notes", ["block-1"], ["skill-1"])}>
      {variant === "center" ? "Start request" : "Generate again"}
    </button>
  ),
}));
vi.mock("@/features/citations/tiptap-draft-editor", () => ({ TiptapDraftEditor: () => <div>Reviewed artifact</div> }));
vi.mock("@/features/citations/export-dialog", () => ({ ExportDialog: ({ presentation }: { presentation?: string }) => presentation === "copy" ? <button type="button" aria-label="Copy artifact">Copy</button> : null }));
vi.mock("@/features/citations/artifact-history-panel", () => ({ ArtifactHistoryPanel: () => <div>History</div> }));

import { ChatWorkspace } from "./chat-workspace";

const chat = { id: "chat-1", title: "Release", status: "OPEN", lastActivityAt: "2026-07-01T00:00:00Z", createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z" };
const reference = { id: "block-1", sourceScopeId: "scope-1", provider: "GITHUB", sourceKind: "PULL_REQUEST", sourceLabel: "PR #1", repositoryLabel: "acme/plot", title: "Ship", body: "Evidence", originalUrl: "https://github.test/1", sourceCreatedAt: null };
const artifactSummary = { id: "artifact-1", status: "READY", title: "Release", updatedAt: "2026-07-01T00:02:00Z" };
const artifact = {
  id: "artifact-1", status: "READY", title: "Release", contentType: "ARTIFACT",
  variant: { id: "variant-1", status: "READY", revisionId: "artifact-revision-1", revisionNumber: 1, lexicalContent: { root: { children: [], type: "root", version: 1 } }, sentences: [], sources: [] },
};

function agentRun(overrides: Record<string, unknown> = {}) {
  return {
    id: "agent-1", chatId: "chat-1", instruction: "Release notes",
    status: "QUEUED", failureCode: null, responseText: null,
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
    mocks.listChatTurns.mockResolvedValue([]);
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
      writingBlockIds: ["block-1"], instruction: "Write release notes", skillIds: ["skill-1"],
    }, expect.any(String)));
    expect(mocks.createChatAgentRun).toHaveBeenCalledTimes(1);
    expect(mocks.locationAssign).toHaveBeenCalledWith("/chat?chat=chat-new&agent=agent-new");
    expect(window.sessionStorage.length).toBe(0);
  });

  it("uses the prompt and selected skills without fixed classification controls", async () => {
    mocks.search = "chat=chat-1&agent=agent-1";
    mocks.listSessions.mockResolvedValue([chat]);
    const succeeded = agentRun({ status: "SUCCEEDED", artifactId: "artifact-1", artifact: artifactSummary });
    mocks.listSessionAgentRuns.mockResolvedValue([succeeded]);
    mocks.getChatAgentRun.mockResolvedValue(succeeded);
    mocks.createChatAgentRun.mockResolvedValue(agentRun({ id: "agent-followup", chatId: "chat-1" }));
    render(<ChatWorkspace />);
    await waitFor(() => expect(screen.queryByText("Loading sources…")).not.toBeInTheDocument());
    expect(screen.queryByLabelText("Purpose (recommended)")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Content type" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Launch announcement" })).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "Generate again" }));

    await waitFor(() => expect(mocks.createChatAgentRun).toHaveBeenCalledWith(
      {
        instruction: "Write release notes",
        workSessionId: "chat-1",
        writingBlockIds: ["block-1"],
        skillIds: ["skill-1"],
      },
      expect.any(String),
      expect.any(Object),
    ));
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

  it("opens the artifact panel when the chat URL includes an artifact query", async () => {
    mocks.search = "chat=chat-1&agent=agent-1&artifact=artifact-1";
    mocks.listSessions.mockResolvedValue([chat]);
    const succeeded = agentRun({ status: "SUCCEEDED", artifactId: "artifact-1", artifact: artifactSummary });
    mocks.listSessionAgentRuns.mockResolvedValue([succeeded]);
    mocks.getChatAgentRun.mockResolvedValue(succeeded);

    render(<ChatWorkspace />);
    expect(await screen.findByRole("complementary", { name: "Artifact document panel" })).toBeVisible();
    expect(screen.getByText("Reviewed artifact")).toBeVisible();
  });

	it("loads Chat Agent activity and renders its Artifact", async () => {
    mocks.search = "chat=chat-1&agent=agent-1";
    mocks.listSessions.mockResolvedValue([chat]);
    const succeeded = agentRun({ status: "SUCCEEDED", artifactId: "artifact-1", artifact: artifactSummary });
    mocks.listSessionAgentRuns.mockResolvedValue([succeeded]);
    mocks.getChatAgentRun.mockResolvedValue(succeeded);

    render(<ChatWorkspace />);
    const openArtifact = await screen.findByText("Open artifact");
    const agentResponse = screen.getByRole("region", { name: "Agent request details" });
    expect(agentResponse).toContainElement(openArtifact);
    const responseTime = agentResponse.querySelector("time");
    expect(responseTime).not.toBeNull();
    expect(openArtifact.compareDocumentPosition(responseTime!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByText("Reviewed artifact")).not.toBeInTheDocument();
    fireEvent.click(openArtifact);
    const artifactPanel = screen.getByRole("complementary", { name: "Artifact document panel" });
    const artifactBody = within(artifactPanel).getByRole("region", { name: "Artifact document body" });
    const artifactTitle = within(artifactPanel).getByText("Release", { exact: true });
    expect(artifactPanel).toBeVisible();
    expect(artifactBody).not.toContainElement(artifactTitle);
    expect(screen.getByRole("separator", { name: "Resize artifact document" })).toBeInTheDocument();
    expect(screen.getByText("Reviewed artifact")).toBeVisible();
    expect(artifactBody).toContainElement(screen.getByText("Reviewed artifact"));
    expect(within(artifactPanel).getByRole("button", { name: "Copy artifact" })).toBeVisible();
    fireEvent.click(within(artifactPanel).getByRole("button", { name: "Artifact history" }));
    expect(screen.getByRole("complementary", { name: "Artifact history drawer" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Close artifact history" }));
    expect(screen.queryByRole("complementary", { name: "Artifact history drawer" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tabpanel", { name: "Assistant panel" })).not.toBeInTheDocument();
    await waitFor(() => expect(document.querySelectorAll("time")).toHaveLength(1));
		expect(screen.getByText("Plot")).toBeVisible();
	});

	it("renders a completed assistant text response without an artifact", async () => {
		mocks.search = "chat=chat-1";
		mocks.listSessions.mockResolvedValue([chat]);
		mocks.listChatTurns.mockResolvedValue([{
			id: "turn-1",
			workSessionId: "chat-1",
			turnIndex: 0,
			userMessage: "Summarize what Plot does",
			selectedVersionId: "ver-1",
			createdAt: "2026-07-01T00:00:00Z",
			updatedAt: "2026-07-01T00:01:00Z",
			versions: [{
				id: "ver-1",
				turnId: "turn-1",
				versionIndex: 0,
				agentRunId: "agent-1",
				status: "SUCCEEDED",
				failureCode: null,
				instruction: "Summarize what Plot does",
				responseText: "Plot turns connected product evidence into useful content and can also answer questions directly.",
				artifactId: null,
				artifact: null,
				lineageParentVersionId: null,
				retryEligibility: { eligible: true, reason: null },
				createdAt: "2026-07-01T00:01:00Z",
				updatedAt: "2026-07-01T00:01:00Z",
			}],
		}]);

		render(<ChatWorkspace />);

		expect(await screen.findByText("Plot turns connected product evidence into useful content and can also answer questions directly.")).toBeVisible();
		expect(screen.queryByText("Open artifact")).not.toBeInTheDocument();
	});

	it("unlocks follow-up input after a restored Agent run finishes", async () => {
		mocks.search = "chat=chat-1&agent=agent-1";
		mocks.listSessions.mockResolvedValue([chat]);
		const queued = agentRun();
		const succeeded = agentRun({
			status: "SUCCEEDED",
			responseText: "The restored response is ready.",
		});
		const queuedTurn = {
			id: "turn-1",
			workSessionId: "chat-1",
			turnIndex: 0,
			userMessage: "Help me plan this",
			selectedVersionId: "version-1",
			createdAt: queued.createdAt,
			updatedAt: queued.updatedAt,
			versions: [{
				id: "version-1",
				turnId: "turn-1",
				versionIndex: 0,
				agentRunId: queued.id,
				status: queued.status,
				instruction: queued.instruction,
				failureCode: null,
				responseText: null,
				artifactId: null,
				artifact: null,
				lineageParentVersionId: null,
				retryEligibility: { eligible: false, reason: "RUN_NOT_TERMINAL" },
				createdAt: queued.createdAt,
				updatedAt: queued.updatedAt,
			}],
		};
		mocks.listChatTurns
			.mockResolvedValueOnce([queuedTurn])
			.mockResolvedValueOnce([{
				...queuedTurn,
				versions: [{
					...queuedTurn.versions[0],
					status: "SUCCEEDED",
					responseText: "The restored response is ready.",
					retryEligibility: { eligible: true, reason: null },
				}],
			}]);
		mocks.getChatAgentRun.mockResolvedValueOnce(queued).mockResolvedValueOnce(succeeded);

		render(<ChatWorkspace />);

		expect(await screen.findByText("The restored response is ready.")).toBeVisible();
		await waitFor(() => expect(mocks.listChatTurns).toHaveBeenCalledTimes(2));
		expect(screen.getByRole("button", { name: "Generate again" })).toBeEnabled();
	});

  it("restores generated activity when History opens a session without an Agent query", async () => {
    mocks.search = "chat=chat-1";
    mocks.listSessions.mockResolvedValue([chat]);
    mocks.listChatTurns.mockRejectedValue(new Error("Response history unavailable"));
    const succeeded = agentRun({ status: "SUCCEEDED", artifactId: "artifact-1", artifact: artifactSummary });
    mocks.listSessionAgentRuns.mockResolvedValue([succeeded]);

    render(<ChatWorkspace />);
    expect(await screen.findByText("Open artifact")).toBeVisible();
    expect(screen.queryByText("Reviewed artifact")).not.toBeInTheDocument();
		expect(screen.getByText("Plot")).toBeVisible();
    expect(mocks.getChatAgentRun).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Retry response" })).not.toBeInTheDocument();
  });

  it("starts a follow-up Agent request with the active Chat linkage", async () => {
    mocks.search = "chat=chat-1&agent=agent-1";
    mocks.listSessions.mockResolvedValue([chat]);
    const succeeded = agentRun({ status: "SUCCEEDED", artifactId: "artifact-1", artifact: artifactSummary });
    mocks.listSessionAgentRuns.mockResolvedValue([succeeded]);
    mocks.getChatAgentRun.mockResolvedValue(succeeded);
    mocks.createChatAgentRun.mockResolvedValue(agentRun({ id: "agent-2", status: "QUEUED", artifactId: null, artifact: null }));

    render(<ChatWorkspace />);
    await screen.findByText("Open artifact");
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
    await screen.findByText("Open artifact");

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
    await screen.findByText("Open artifact");

    const historyTrigger = screen.getAllByRole("tab", { name: "History" }).find((element) => element.getAttribute("aria-controls") === "mobile-chat-history-panel");
    expect(historyTrigger).toBeDefined();
    fireEvent.click(historyTrigger!);
    expect(await screen.findByRole("tabpanel", { name: "History panel" })).toBeVisible();
    fireEvent.click(historyTrigger!);
    await waitFor(() => expect(document.activeElement).toBe(historyTrigger));
  });

  it("allows switching between response versions", async () => {
    mocks.search = "chat=chat-1";
    mocks.listSessions.mockResolvedValue([chat]);
    const version1 = {
      id: "ver-1",
      versionIndex: 0,
      agentRunId: "agent-1",
      status: "SUCCEEDED" as const,
      instructionSnapshot: "Release notes v1",
      content: "Changelog version 1",
      failureCode: null,
      failureDetails: null,
      lineageParentVersionId: null,
      artifactId: null,
      artifactTitle: null,
      artifactVariantId: null,
      artifactRevisionId: null,
      createdAt: "2026-07-01T00:01:00Z",
      updatedAt: "2026-07-01T00:01:00Z",
      retryEligibility: { eligible: false, reason: "NOT_LATEST_VERSION" },
    };
    const version2 = {
      id: "ver-2",
      versionIndex: 1,
      agentRunId: "agent-2",
      status: "SUCCEEDED" as const,
      instructionSnapshot: "Release notes v2",
      content: "Changelog version 2",
      failureCode: null,
      failureDetails: null,
      lineageParentVersionId: "ver-1",
      artifactId: "artifact-1",
      artifactTitle: "Release v2",
      artifactVariantId: "variant-1",
      artifactRevisionId: "rev-1",
      createdAt: "2026-07-01T00:02:00Z",
      updatedAt: "2026-07-01T00:02:00Z",
      retryEligibility: { eligible: true, reason: null },
    };
    mocks.listChatTurns.mockResolvedValue([
      {
        id: "turn-1",
        turnIndex: 0,
        userMessage: "Write release notes",
        createdAt: "2026-07-01T00:00:00Z",
        selectedVersionId: "ver-2",
        versions: [version1, version2],
      },
    ]);

    render(<ChatWorkspace />);
    expect(await screen.findByText("Response 2 of 2")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry response" })).toBeInTheDocument();

    // Click Previous response version
    fireEvent.click(screen.getByRole("button", { name: "Previous response version" }));
    expect(await screen.findByText("Response 1 of 2")).toBeInTheDocument();
    // Version 1 is not latest, so Retry button is not offered
    expect(screen.queryByRole("button", { name: "Retry response" })).not.toBeInTheDocument();
  });

  it("admits Retry for eligible latest response", async () => {
    mocks.search = "chat=chat-1";
    mocks.listSessions.mockResolvedValue([chat]);
    const terminalFailedVersion = {
      id: "ver-failed",
      versionIndex: 0,
      agentRunId: "agent-failed",
      status: "FAILED" as const,
      instructionSnapshot: "Release notes draft",
      content: null,
      failureCode: "MODEL_TIMEOUT",
      failureDetails: "Exhausted retries",
      lineageParentVersionId: null,
      artifactId: null,
      artifactTitle: null,
      artifactVariantId: null,
      artifactRevisionId: null,
      createdAt: "2026-07-01T00:01:00Z",
      updatedAt: "2026-07-01T00:01:00Z",
      retryEligibility: { eligible: true, reason: null },
    };
    mocks.listChatTurns.mockResolvedValue([
      {
        id: "turn-1",
        turnIndex: 0,
        userMessage: "Write release notes",
        createdAt: "2026-07-01T00:00:00Z",
        selectedVersionId: "ver-failed",
        versions: [terminalFailedVersion],
      },
    ]);
    const retriedQueuedVersion = {
      ...terminalFailedVersion,
      id: "ver-retried",
      versionIndex: 1,
      lineageParentVersionId: "ver-failed",
      agentRunId: "agent-retried",
      status: "QUEUED" as const,
      failureCode: null,
      failureDetails: null,
      retryEligibility: { eligible: false, reason: "RUN_NOT_TERMINAL" },
    };
    mocks.retryChatResponse.mockResolvedValue(retriedQueuedVersion);
    mocks.getChatAgentRun.mockResolvedValue(agentRun({ id: "agent-retried", status: "SUCCEEDED" }));

    render(<ChatWorkspace />);
    const retryBtn = await screen.findByRole("button", { name: "Retry response" });
    fireEvent.click(retryBtn);

    await waitFor(() => expect(mocks.retryChatResponse).toHaveBeenCalledWith("ver-failed", expect.any(String), expect.anything()));
  });

  it("keeps the Retry idempotency key when reconciliation finds only the target retry version", async () => {
    mocks.search = "chat=chat-1";
    mocks.listSessions.mockResolvedValue([chat]);
    const retriedTarget = {
      id: "ver-target",
      versionIndex: 1,
      lineageParentVersionId: "ver-original",
      agentRunId: "agent-target",
      status: "FAILED" as const,
      instruction: "Release notes draft",
      failureCode: "MODEL_TIMEOUT",
      artifactId: null,
      artifact: null,
      createdAt: "2026-07-01T00:01:00Z",
      updatedAt: "2026-07-01T00:01:00Z",
      retryEligibility: { eligible: true, reason: null },
    };
    const turns = [{
      id: "turn-1",
      workSessionId: "chat-1",
      turnIndex: 0,
      userMessage: "Write release notes",
      selectedVersionId: "ver-target",
      createdAt: "2026-07-01T00:00:00Z",
      updatedAt: "2026-07-01T00:01:00Z",
      versions: [retriedTarget],
    }];
    mocks.listChatTurns.mockResolvedValue(turns);
    mocks.retryChatResponse.mockRejectedValue(new TypeError("Network request failed"));

    render(<ChatWorkspace />);
    const retry = await screen.findByRole("button", { name: "Retry response" });
    fireEvent.click(retry);
    await waitFor(() => expect(mocks.retryChatResponse).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole("alert")).toHaveTextContent("Network request failed");

    fireEvent.click(screen.getByRole("button", { name: "Retry response" }));
    await waitFor(() => expect(mocks.retryChatResponse).toHaveBeenCalledTimes(2));
    expect(mocks.retryChatResponse.mock.calls[1]?.[1]).toBe(mocks.retryChatResponse.mock.calls[0]?.[1]);
  });
});
