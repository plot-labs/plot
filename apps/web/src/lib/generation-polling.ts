import type { GenerationRun, PlotApiClient } from "@plot/api-client";

const terminalStatuses = new Set<GenerationRun["status"]>([
  "READY",
  "NEEDS_REVIEW",
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
  initialRun?: GenerationRun;
}

export async function pollGeneration(
  client: PlotApiClient,
  runId: string,
  options: PollingOptions = {},
): Promise<GenerationRun> {
  const { initialDelay, maxDelay } = resolvePollingDelays(options);
  let fallbackDelay = initialDelay;
  let initialRun = options.initialRun;

  while (true) {
    throwIfAborted(options.signal);
    const run = initialRun ?? await client.getGeneration(runId, { signal: options.signal });
    initialRun = undefined;
    options.onUpdate?.(run);
    if (isTerminalGenerationStatus(run.status)) return run;
    const delayMs = boundedDelay(run.pollAfterMs ?? fallbackDelay, maxDelay);
    await abortableDelay(delayMs, options.signal);
    fallbackDelay = Math.min(fallbackDelay * 2, maxDelay);
  }
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
