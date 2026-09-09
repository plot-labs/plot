// @vitest-environment jsdom
import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import type { ChatAgentRun } from "@plot/api-client";
import { useChatAgentActivity } from "./use-chat-agent-activity";

const mocks = vi.hoisted(() => ({ timeline: vi.fn(), poll: vi.fn(), runs: vi.fn(), run: vi.fn() }));
vi.mock("@/lib/api-client", () => ({ plotApiClient: {
  listSessionAgentRuns: mocks.runs, getChatAgentRun: mocks.run, getSessionTimeline: mocks.timeline,
} }));
vi.mock("@/lib/chat-agent-polling", () => ({ pollChatAgentRun: mocks.poll, isTerminalChatAgentStatus: () => false }));

const queued = { id: "agent-1", chatId: "chat-1", status: "QUEUED", artifactId: null } as ChatAgentRun;
const item = (status: string) => ({ id: "agent-1", agentRunId: "agent-1", status });
let update: (run: ChatAgentRun) => void;
const onAgentArtifact = vi.fn();
const onAdmitted = vi.fn();
function mount() {
  return renderHook(({ chatId }) => useChatAgentActivity({
    chatId, requestedAgentId: chatId === "chat-1" ? "agent-1" : null, requestedArtifactId: null,
    references: [], sourceError: "", onAgentArtifact, onAdmitted,
  }), { initialProps: { chatId: "chat-1" } });
}
beforeEach(() => {
  vi.resetAllMocks();
  mocks.runs.mockResolvedValue([queued]);
  mocks.run.mockResolvedValue(queued);
  mocks.timeline.mockResolvedValue([item("QUEUED")]);
  mocks.poll.mockImplementation((_client, _id, options) => {
    update = options.onUpdate;
    return new Promise(() => {});
  });
});

test("refreshes server timeline during polling, including retry changes while still running", async () => {
  const { result } = mount();
  await waitFor(() => expect(result.current.selectedTimelineItem?.status).toBe("QUEUED"));
  for (const status of ["RUNNING", "RETRY_SCHEDULED", "FAILED"]) {
    mocks.timeline.mockResolvedValue([item(status)]);
    act(() => update({ ...queued, status: status === "FAILED" ? "FAILED" : "RUNNING" }));
    await waitFor(() => expect(result.current.selectedTimelineItem?.status).toBe(status));
  }
});

test("ignores older timeline responses that arrive after a newer polling refresh", async () => {
  const { result } = mount();
  await waitFor(() => expect(result.current.selectedTimelineItem?.status).toBe("QUEUED"));
  let resolveOlder!: (value: ReturnType<typeof item>[]) => void;
  mocks.timeline.mockImplementationOnce(() => new Promise(resolve => { resolveOlder = resolve; }));
  act(() => update({ ...queued, status: "RUNNING" }));
  mocks.timeline.mockResolvedValue([item("FAILED")]);
  act(() => update({ ...queued, status: "FAILED" }));
  await waitFor(() => expect(result.current.selectedTimelineItem?.status).toBe("FAILED"));
  await act(async () => resolveOlder([item("RUNNING")]));
  expect(result.current.selectedTimelineItem?.status).toBe("FAILED");
});

test("ignores pending timeline responses after switching chats", async () => {
  const { result, rerender } = mount();
  await waitFor(() => expect(result.current.selectedTimelineItem?.status).toBe("QUEUED"));
  let resolveOlder!: (value: ReturnType<typeof item>[]) => void;
  mocks.timeline.mockImplementationOnce(() => new Promise(resolve => { resolveOlder = resolve; }));
  act(() => update({ ...queued, status: "RUNNING" }));
  mocks.timeline.mockResolvedValue([]);
  rerender({ chatId: "chat-2" });
  await waitFor(() => expect(result.current.timeline).toEqual([]));
  await act(async () => resolveOlder([item("RUNNING")]));
  expect(result.current.timeline).toEqual([]);
});
