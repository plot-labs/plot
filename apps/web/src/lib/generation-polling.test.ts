import { afterEach, describe, expect, it, vi } from "vitest";

import type { GenerationRun, PlotApiClient } from "@plot/api-client";
import { pollGeneration } from "./generation-polling";

const run = (status: GenerationRun["status"], pollAfterMs: number | null = 10): GenerationRun => ({
  id: "run-1",
  status,
  semanticRewriteAttempt: 0,
  pollAfterMs,
  failureCode: null,
  evidence: [],
  sentences: [],
  artifacts: [],
  artifact: null,
});

describe("generation polling", () => {
  afterEach(() => vi.useRealTimers());

  it.each(["READY", "NEEDS_REVIEW", "FAILED"] as const)("stops at %s", async (terminal) => {
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

});
