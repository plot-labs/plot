import { afterEach, describe, expect, it, vi } from "vitest";

import type { GenerationRun, PlotApiClient } from "@plot/api-client";
import { createAndPollGeneration, createAndStreamGeneration, pollGeneration, streamGeneration } from "./generation-polling";

const run = (status: GenerationRun["status"], pollAfterMs: number | null = 10): GenerationRun => ({
  id: "run-1",
  status,
  semanticRewriteAttempt: 0,
  pollAfterMs,
  failureCode: null,
  evidence: [],
  sentences: [],
  artifacts: [],
  pendingIntervention: null,
  contentPack: null,
});

describe("generation polling", () => {
  afterEach(() => vi.useRealTimers());

  it.each(["READY", "NEEDS_REVIEW", "NEEDS_YOUR_CALL", "FAILED"] as const)("stops at %s", async (terminal) => {
    vi.useFakeTimers();
    const client = { getGeneration: vi.fn().mockResolvedValue(run(terminal)) } as unknown as PlotApiClient;
    const resultPromise = pollGeneration(client, "run-1", { initialDelayMs: 5, maxDelayMs: 20 });
    await vi.runAllTimersAsync();
    await expect(resultPromise).resolves.toMatchObject({ status: terminal });
    expect(client.getGeneration).toHaveBeenCalledTimes(1);
  });

  it("uses bounded backoff and aborts disposal without another request", async () => {
    vi.useFakeTimers();
    const client = { getGeneration: vi.fn().mockResolvedValue(run("WRITING", null)) } as unknown as PlotApiClient;
    const controller = new AbortController();
    const result = pollGeneration(client, "run-1", { signal: controller.signal, initialDelayMs: 5, maxDelayMs: 10 });
    const aborted = expect(result).rejects.toMatchObject({ name: "AbortError" });
    await vi.advanceTimersByTimeAsync(6);
    controller.abort();
    await vi.runAllTimersAsync();

    await aborted;
    expect(client.getGeneration).toHaveBeenCalledTimes(2);
  });

  it("creates once then polls the accepted run", async () => {
    vi.useFakeTimers();
    const client = {
      createGeneration: vi.fn().mockResolvedValue(run("QUEUED", 5)),
      getGeneration: vi.fn().mockResolvedValue(run("READY", null)),
    } as unknown as PlotApiClient;
    const onUpdate = vi.fn<(value: GenerationRun) => void>();
    const result = createAndPollGeneration(client, { sourceScopeId: "scope", writingBlockIds: ["block"] }, "key", {
      initialDelayMs: 5,
      maxDelayMs: 10,
      onUpdate,
    });
    await vi.runAllTimersAsync();

    await expect(result).resolves.toMatchObject({ status: "READY" });
    expect(client.createGeneration).toHaveBeenCalledTimes(1);
    expect(client.getGeneration).toHaveBeenCalledTimes(1);
    expect(onUpdate.mock.calls.map(([value]) => value.status)).toEqual(["QUEUED", "READY"]);
  });

  it("normalizes the initial and maximum delay before the first poll", async () => {
    vi.useFakeTimers();
    const client = {
      createGeneration: vi.fn().mockResolvedValue(run("QUEUED", 50)),
      getGeneration: vi.fn().mockResolvedValue(run("READY", null)),
    } as unknown as PlotApiClient;

    const result = createAndPollGeneration(client, { sourceScopeId: "scope", writingBlockIds: ["block"] }, "key", {
      initialDelayMs: 10,
      maxDelayMs: 5,
    });
    await vi.advanceTimersByTimeAsync(9);
    expect(client.getGeneration).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);

    await expect(result).resolves.toMatchObject({ status: "READY" });
  });

  it("refetches the run after an SSE checkpoint and returns the terminal state", async () => {
    const writing = run("WRITING", null);
    const ready = run("READY", null);
    const client = {
      getGeneration: vi.fn().mockResolvedValueOnce(writing).mockResolvedValueOnce(ready),
      subscribeGenerationEvents: vi.fn(async (_id: string, options: { onEvent: (event: { runId: string; runStatus: "READY"; sequence: number }) => void }) => options.onEvent({ runId: "run-1", runStatus: "READY", sequence: 1 })),
    } as unknown as PlotApiClient;
    const onUpdate = vi.fn<(value: GenerationRun) => void>();

    await expect(streamGeneration(client, "run-1", { onUpdate })).resolves.toMatchObject({ status: "READY" });

    expect(client.subscribeGenerationEvents).toHaveBeenCalledWith("run-1", expect.objectContaining({ onEvent: expect.any(Function) }));
    expect(onUpdate.mock.calls.map(([value]) => value.status)).toEqual(["WRITING", "READY"]);
  });

  it("creates once then streams the accepted run", async () => {
    const accepted = run("QUEUED", null);
    const ready = run("READY", null);
    const client = {
      createGeneration: vi.fn().mockResolvedValue(accepted),
      getGeneration: vi.fn().mockResolvedValue(ready),
      subscribeGenerationEvents: vi.fn().mockResolvedValue(undefined),
    } as unknown as PlotApiClient;
    const onUpdate = vi.fn<(value: GenerationRun) => void>();

    await expect(createAndStreamGeneration(client, { sourceScopeId: "scope", writingBlockIds: ["block"] }, "key", { onUpdate }))
      .resolves.toMatchObject({ status: "READY" });

    expect(client.createGeneration).toHaveBeenCalledWith({ sourceScopeId: "scope", writingBlockIds: ["block"] }, "key", expect.any(Object));
    expect(onUpdate.mock.calls.map(([value]) => value.status)).toEqual(["QUEUED", "READY"]);
  });

  it("falls back to polling when the event stream fails", async () => {
    vi.useFakeTimers();
    const client = {
      getGeneration: vi.fn().mockResolvedValueOnce(run("WRITING", null)).mockResolvedValueOnce(run("READY", null)),
      subscribeGenerationEvents: vi.fn().mockRejectedValue(new Error("stream unavailable")),
    } as unknown as PlotApiClient;

    const resultPromise = streamGeneration(client, "run-1", { initialDelayMs: 1, maxDelayMs: 1 });
    await vi.runAllTimersAsync();

    await expect(resultPromise).resolves.toMatchObject({ status: "READY" });
    expect(client.getGeneration).toHaveBeenCalledTimes(2);
  });
});
