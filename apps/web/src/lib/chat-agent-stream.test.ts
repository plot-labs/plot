// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { PlotApiError, type ChatAgentRun, type ChatRunSnapshot, type PlotApiClient } from "@plot/api-client";
import { watchChatAgentRun } from "./chat-agent-stream";
const queued: ChatAgentRun = { id: "run-1", chatId: "chat-1", instruction: "Write", status: "QUEUED", responseText: null, failureCode: null, artifactId: null, artifact: null, createdAt: "", updatedAt: "" };
const snapshot: ChatRunSnapshot = { runId: queued.id, epoch: 1, revision: 1, phase: "WRITING", responseText: "Hello", draftParagraphs: ["Draft"], status: "RUNNING", artifactId: "artifact-1", failureCode: null };

describe("Chat stream restoration", () => {
  it("reconnects after EOF, ignores stale revisions/epochs and continues past artifact handoff", async () => {
    const final = { ...queued, status: "SUCCEEDED" as const, artifactId: "artifact-1", responseText: "Final" };
    const streamChatAgentRun = vi.fn()
      .mockImplementationOnce(async (_id, options) => { options.onSnapshot(snapshot); options.onSnapshot({ ...snapshot, revision: 2 }); })
      .mockImplementationOnce(async (_id, options) => {
        options.onSnapshot(snapshot);
        options.onSnapshot({ ...snapshot, epoch: 2, revision: 1, draftParagraphs: [] });
        options.onSnapshot({ ...snapshot, epoch: 1, revision: 99 });
        options.onSnapshot({ ...snapshot, epoch: 2, revision: 2, phase: "COMPLETE", status: "SUCCEEDED" });
      });
    const getChatAgentRun = vi.fn().mockResolvedValue(final);
    const progress: ChatRunSnapshot[] = [];
    const result = await watchChatAgentRun({ streamChatAgentRun, getChatAgentRun } as unknown as PlotApiClient, queued.id,
      { initialRun: queued, initialDelayMs: 1, onProgress: (value) => progress.push(value) });
    expect(result).toEqual(final);
    expect(streamChatAgentRun).toHaveBeenCalledTimes(2);
    expect(progress.map((v) => [v.epoch, v.revision])).toEqual([[1, 1], [1, 2], [2, 1], [2, 2]]);
    expect(getChatAgentRun).toHaveBeenCalledTimes(1);
  });

  it("retains terminal preview while retrying canonical read failure", async () => {
    const final = { ...queued, status: "SUCCEEDED" as const };
    const streamChatAgentRun = vi.fn(async (_id, options) => options.onSnapshot({ ...snapshot, phase: "COMPLETE", status: "SUCCEEDED" }));
    const getChatAgentRun = vi.fn().mockRejectedValueOnce(new PlotApiError(503, "UNAVAILABLE", "Retry")).mockResolvedValueOnce(final);
    await expect(watchChatAgentRun({ streamChatAgentRun, getChatAgentRun } as unknown as PlotApiClient, queued.id, { initialRun: queued, initialDelayMs: 1 })).resolves.toEqual(final);
    expect(streamChatAgentRun).toHaveBeenCalledTimes(1);
    expect(getChatAgentRun).toHaveBeenCalledTimes(2);
  });

  it.each([401, 403, 404])("stops on permanent access status %s", async (status) => {
    const streamChatAgentRun = vi.fn().mockRejectedValue(new PlotApiError(status, "DENIED", "Denied"));
    await expect(watchChatAgentRun({ streamChatAgentRun } as unknown as PlotApiClient, queued.id, { initialRun: queued, initialDelayMs: 1 })).rejects.toMatchObject({ status });
    expect(streamChatAgentRun).toHaveBeenCalledTimes(1);
  });

  it("aborts reconnection and ignores late callbacks from a closed subscription", async () => {
    const controller = new AbortController();
    const onProgress = vi.fn();
    const streamChatAgentRun = vi.fn(async (_id, options) => { controller.abort(); options.onSnapshot(snapshot); });
    await expect(watchChatAgentRun({ streamChatAgentRun } as unknown as PlotApiClient, queued.id, { initialRun: queued, signal: controller.signal, onProgress })).rejects.toMatchObject({ name: "AbortError" });
    expect(onProgress).not.toHaveBeenCalled();
    expect(streamChatAgentRun).toHaveBeenCalledTimes(1);
  });
  it("ignores callbacks from a connection that has already ended", async () => {
    let oldCallback!: (value: ChatRunSnapshot) => void;
    const onProgress = vi.fn();
    const streamChatAgentRun = vi.fn()
      .mockImplementationOnce(async (_id, options) => { oldCallback = options.onSnapshot; options.onSnapshot(snapshot); })
      .mockImplementationOnce(async (_id, options) => {
        oldCallback({ ...snapshot, epoch: 99, revision: 99, responseText: "Obsolete connection" });
        options.onSnapshot({ ...snapshot, revision: 2, phase: "COMPLETE", status: "SUCCEEDED" });
      });
    await watchChatAgentRun({ streamChatAgentRun, getChatAgentRun: vi.fn().mockResolvedValue({ ...queued, status: "SUCCEEDED" }) } as unknown as PlotApiClient, queued.id, { initialRun: queued, initialDelayMs: 1, onProgress });
    expect(onProgress.mock.calls.map(([value]) => value.responseText)).toEqual(["Hello", "Hello"]);
  });

});
