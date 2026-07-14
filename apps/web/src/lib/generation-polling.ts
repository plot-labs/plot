import type { CreateGenerationInput, GenerationRun, PlotApiClient } from "@plot/api-client";

const terminalStatuses = new Set<GenerationRun["status"]>([
  "READY",
  "NEEDS_REVIEW",
  "NEEDS_YOUR_CALL",
  "FAILED",
]);

export function isTerminalGenerationStatus(status: GenerationRun["status"]): boolean {
  return terminalStatuses.has(status);
}

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
  const { initialDelay, maxDelay } = resolvePollingDelays(options);
  let fallbackDelay = initialDelay;

  while (true) {
    throwIfAborted(options.signal);
    const run = await client.getGeneration(runId, { signal: options.signal });
    options.onUpdate?.(run);
    if (isTerminalGenerationStatus(run.status)) return run;
    const delayMs = boundedDelay(run.pollAfterMs ?? fallbackDelay, maxDelay);
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
  if (isTerminalGenerationStatus(accepted.status)) return accepted;
  const { initialDelay, maxDelay } = resolvePollingDelays(options);
  await abortableDelay(boundedDelay(accepted.pollAfterMs ?? initialDelay, maxDelay), options.signal);
  return pollGeneration(client, accepted.id, options);
}

function resolvePollingDelays(options: PollingOptions) {
  const initialDelay = Math.max(1, options.initialDelayMs ?? 500);
  return { initialDelay, maxDelay: Math.max(initialDelay, options.maxDelayMs ?? 4_000) };
}

function boundedDelay(delayMs: number, maxDelay: number): number {
  return Math.max(1, Math.min(delayMs, maxDelay));
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
