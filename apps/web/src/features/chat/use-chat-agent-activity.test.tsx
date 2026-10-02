// @vitest-environment jsdom
import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ChatAgentRun, ChatRunStreamOptions, ChatTurn } from "@plot/api-client";
const mocks = vi.hoisted(() => ({ workspace: "workspace-1", getChatAgentRun: vi.fn(), streamChatAgentRun: vi.fn(), listChatTurns: vi.fn(), listSessionAgentRuns: vi.fn(), createChatAgentRun: vi.fn() }));
vi.mock("@/lib/api-client", () => ({ getSelectedWorkspaceId: () => mocks.workspace, plotApiClient: mocks }));
import { useChatAgentActivity } from "./use-chat-agent-activity";
const run: ChatAgentRun = { id: "run-1", chatId: "chat-1", instruction: "Write", status: "RUNNING", responseText: null, failureCode: null, artifactId: null, artifact: null, createdAt: "2026-07-01T00:01:00Z", updatedAt: "2026-07-01T00:01:00Z" };
const props = () => ({ chatId: "chat-1", requestedAgentId: "run-1", requestedArtifactId: null, onAgentArtifact: vi.fn(), onAdmitted: vi.fn() });
describe("Chat subscription ownership", () => {
  beforeEach(() => {
    mocks.workspace = "workspace-1";
    for (const value of Object.values(mocks)) if (typeof value === "function") value.mockReset();
    mocks.listChatTurns.mockResolvedValue([]);
    mocks.listSessionAgentRuns.mockResolvedValue([run]);
    mocks.getChatAgentRun.mockResolvedValue(run);
    mocks.streamChatAgentRun.mockImplementation(() => new Promise(() => undefined));
  });
  it("retries an initial offline run read and restores the same run when online", async () => {
    mocks.getChatAgentRun.mockRejectedValueOnce(new TypeError("Offline")).mockResolvedValue(run);
    const callbacks = props();
    const { result, unmount } = renderHook(() => useChatAgentActivity(callbacks));
    await waitFor(() => expect(mocks.streamChatAgentRun).toHaveBeenCalledTimes(1), { timeout: 2000 });
    expect(result.current.agentRun?.id).toBe("run-1");
    expect(mocks.getChatAgentRun).toHaveBeenCalledTimes(2);
    unmount();
  });
  it("retries terminal turn history failure before clearing busy state", async () => {
    const callbacks = props();
    const final = { ...run, status: "SUCCEEDED" as const, responseText: "Final" };
    mocks.getChatAgentRun.mockResolvedValueOnce(run).mockResolvedValue(final);
    mocks.streamChatAgentRun.mockImplementation(async (_id, options: ChatRunStreamOptions) => options.onSnapshot({ runId: run.id, epoch: 1, revision: 1, phase: "COMPLETE", responseText: "Final", draftParagraphs: [], status: "SUCCEEDED", artifactId: null, failureCode: null }));
    mocks.listChatTurns.mockResolvedValueOnce([]).mockRejectedValueOnce(new TypeError("Offline final history")).mockResolvedValue([]);
    const { result, unmount } = renderHook(() => useChatAgentActivity(callbacks));
    await waitFor(() => expect(mocks.listChatTurns).toHaveBeenCalledTimes(3), { timeout: 2000 });
    await waitFor(() => expect(result.current.agentBusy).toBe(false));
    expect(result.current.agentRun?.responseText).toBe("Final");
    unmount();
  });
  it("selects a newly admitted follow-up after the user chose a historical version", async () => {
    const previous = { ...run, status: "SUCCEEDED" as const, responseText: "Previous" };
    const next = { ...run, id: "run-2", instruction: "Follow up" };
    const turn = (id: string, item: ChatAgentRun): ChatTurn => ({
      id, workSessionId: "chat-1", turnIndex: id === "turn-1" ? 0 : 1, userMessage: item.instruction,
      createdAt: item.createdAt, updatedAt: item.updatedAt, selectedVersionId: `version-${id}`,
      versions: [{ id: `version-${id}`, turnId: id, versionIndex: 0, lineageParentVersionId: null,
        agentRunId: item.id, status: item.status, instruction: item.instruction, responseText: item.responseText,
        failureCode: null, artifactId: null, artifact: null, createdAt: item.createdAt, updatedAt: item.updatedAt,
        retryEligibility: { eligible: false, reason: "NOT_LATEST_TURN" } }],
    });
    mocks.listChatTurns.mockResolvedValue([turn("turn-1", previous)]);
    mocks.listSessionAgentRuns.mockResolvedValue([previous]);
    mocks.getChatAgentRun.mockImplementation(async (id) => id === next.id ? next : previous);
    mocks.createChatAgentRun.mockResolvedValue(next);
    const callbacks = props();
    const { result, rerender, unmount } = renderHook((value) => useChatAgentActivity(value), { initialProps: callbacks });
    await waitFor(() => expect(result.current.agentBusy).toBe(false));
    await waitFor(() => expect(result.current.turns).toHaveLength(1));
    act(() => result.current.selectVersion("turn-1", "version-turn-1"));
    mocks.listChatTurns.mockResolvedValue([turn("turn-1", previous), turn("turn-2", next)]);
    await act(async () => result.current.submitMessage("Follow up"));
    rerender({ ...callbacks, requestedAgentId: next.id });
    await waitFor(() => expect(result.current.selectedActivity?.id).toBe(next.id));
    unmount();
  });

  it("follows a different same-chat URL after an admitted run completed", async () => {
    const previous = { ...run, status: "SUCCEEDED" as const };
    const admitted = { ...previous, id: "run-2" };
    const destination = { ...run, id: "run-3" };
    mocks.listSessionAgentRuns.mockResolvedValue([previous]);
    mocks.getChatAgentRun.mockImplementation(async (id) => id === destination.id ? destination : id === admitted.id ? admitted : previous);
    mocks.createChatAgentRun.mockResolvedValue(admitted);
    const callbacks = props();
    const { result, rerender, unmount } = renderHook((value) => useChatAgentActivity(value), { initialProps: callbacks });
    await waitFor(() => expect(result.current.agentBusy).toBe(false));
    await act(async () => result.current.submitMessage("Follow up"));
    rerender({ ...callbacks, requestedAgentId: admitted.id });
    await waitFor(() => expect(result.current.agentRun?.id).toBe(admitted.id));
    await waitFor(() => expect(result.current.agentBusy).toBe(false));
    rerender({ ...callbacks, requestedAgentId: destination.id });
    await waitFor(() => expect(result.current.agentRun?.id).toBe(destination.id));
    unmount();
  });

  it("clears a URL version selection when navigating to a URL without a version", async () => {
    const previous = { ...run, status: "SUCCEEDED" as const };
    const destination = { ...previous, id: "run-2" };
    mocks.listSessionAgentRuns.mockResolvedValue([previous, destination]);
    mocks.getChatAgentRun.mockImplementation(async (id) => id === destination.id ? destination : previous);
    const callbacks = { ...props(), requestedVersionId: "run-1" as string | null };
    const { result, rerender, unmount } = renderHook((value) => useChatAgentActivity(value), { initialProps: callbacks });
    await waitFor(() => expect(result.current.selectedActivity?.id).toBe(previous.id));
    rerender({ ...callbacks, requestedAgentId: destination.id, requestedVersionId: null });
    await waitFor(() => expect(result.current.selectedActivity?.id).toBe(destination.id));
    expect(result.current.selectedVersionId).toBeNull();
    unmount();
  });

  it("rejects initial RUNNING history that resolves after terminal history", async () => {
    const final = { ...run, status: "SUCCEEDED" as const, responseText: "Final" };
    let finishInitialRuns!: (items: ChatAgentRun[]) => void;
    mocks.listSessionAgentRuns.mockImplementation(() => new Promise((resolve) => { finishInitialRuns = resolve; }));
    const turn = (status: "RUNNING" | "SUCCEEDED"): ChatTurn => ({
      id: "turn-1", workSessionId: "chat-1", turnIndex: 0, userMessage: "Write", createdAt: run.createdAt, updatedAt: run.updatedAt,
      selectedVersionId: "version-1", versions: [{ id: "version-1", turnId: "turn-1", versionIndex: 0, lineageParentVersionId: null,
        agentRunId: run.id, status, instruction: "Write", responseText: status === "SUCCEEDED" ? "Final" : null,
        failureCode: null, artifactId: null, artifact: null, createdAt: run.createdAt, updatedAt: run.updatedAt,
        retryEligibility: { eligible: false, reason: "NOT_FAILED" } }],
    });
    mocks.listChatTurns.mockResolvedValueOnce([turn("RUNNING")]).mockResolvedValue([turn("SUCCEEDED")]);
    mocks.getChatAgentRun.mockResolvedValueOnce(run).mockResolvedValue(final);
    mocks.streamChatAgentRun.mockImplementation(async (_id, options: ChatRunStreamOptions) => options.onSnapshot({ runId: run.id, epoch: 1, revision: 1, phase: "COMPLETE", responseText: "Final", draftParagraphs: [], status: "SUCCEEDED", artifactId: null, failureCode: null }));
    const callbacks = props();
    const { result, unmount } = renderHook(() => useChatAgentActivity(callbacks));
    await waitFor(() => expect(result.current.turns[0]?.versions[0]?.status).toBe("SUCCEEDED"));
    await act(async () => finishInitialRuns([run]));
    expect(result.current.turns[0]?.versions[0]?.status).toBe("SUCCEEDED");
    expect(result.current.isPendingRun).toBe(false);
    unmount();
  });

  it("ignores progress immediately after workspace changes, even before React cleanup", async () => {
    let options!: ChatRunStreamOptions;
    mocks.streamChatAgentRun.mockImplementation((_id, value) => { options = value; return new Promise(() => undefined); });
    const callbacks = props();
    const { result, unmount } = renderHook(() => useChatAgentActivity(callbacks));
    await waitFor(() => expect(mocks.streamChatAgentRun).toHaveBeenCalled());
    mocks.workspace = "workspace-2";
    await act(async () => options.onSnapshot({ runId: run.id, epoch: 1, revision: 1, phase: "WRITING", responseText: "Old workspace text", draftParagraphs: ["Private draft"], status: "RUNNING", artifactId: null, failureCode: null }));
    expect(result.current.progress).toBeNull();
    expect(callbacks.onAgentArtifact).not.toHaveBeenCalled();
    unmount();
  });
});
