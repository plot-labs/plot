import type { CreateGenerationInput, GenerationRun, PlotApiClient } from "@plot/api-client";

const terminalStatuses = new Set<GenerationRun["status"]>([
  "READY",
  "NEEDS_REVIEW",
  "NEEDS_YOUR_CALL",
  "FAILED",
]);

export interface PollingOptions {
  signal?: AbortSignal;
  initialDelayMs?: number;
  maxDelayMs?: number;
  onUpdate?: (run: GenerationRun) => void;
}

export async function pollGeneration(
  client: PlotApiClient,
  runId: string,
  options: PollingOptions = {},
): Promise<GenerationRun> {
  const initialDelay = Math.max(1, options.initialDelayMs ?? 500);
  const maxDelay = Math.max(initialDelay, options.maxDelayMs ?? 4_000);
  let fallbackDelay = initialDelay;

  while (true) {
    throwIfAborted(options.signal);
    const run = await client.getGeneration(runId, { signal: options.signal });
    options.onUpdate?.(run);
    if (terminalStatuses.has(run.status)) return run;
    const delayMs = Math.max(1, Math.min(run.pollAfterMs ?? fallbackDelay, maxDelay));
    await abortableDelay(delayMs, options.signal);
    fallbackDelay = Math.min(fallbackDelay * 2, maxDelay);
  }
}

export async function createAndPollGeneration(
  client: PlotApiClient,
  input: CreateGenerationInput,
  idempotencyKey: string,
  options: PollingOptions = {},
): Promise<GenerationRun> {
  const accepted = await client.createGeneration(input, idempotencyKey, { signal: options.signal });
  options.onUpdate?.(accepted);
  if (terminalStatuses.has(accepted.status)) return accepted;
  await abortableDelay(
    Math.max(1, Math.min(accepted.pollAfterMs ?? options.initialDelayMs ?? 500, options.maxDelayMs ?? 4_000)),
    options.signal,
  );
  return pollGeneration(client, accepted.id, options);
}

function abortableDelay(ms: number, signal?: AbortSignal): Promise<void> {
  throwIfAborted(signal);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(done, ms);
    signal?.addEventListener("abort", aborted, { once: true });

    function cleanup() {
      clearTimeout(timer);
      signal?.removeEventListener("abort", aborted);
    }
    function done() {
      cleanup();
      resolve();
    }
    function aborted() {
      cleanup();
      reject(abortError());
    }
  });
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw abortError();
}

function abortError(): DOMException {
  return new DOMException("Generation polling was aborted", "AbortError");
}
