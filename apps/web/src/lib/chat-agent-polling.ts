import type { ChatAgentRun, PlotApiClient } from "@plot/api-client";

const terminalStatuses = new Set<ChatAgentRun["status"]>(["SUCCEEDED", "FAILED"]);

export function isTerminalChatAgentStatus(status: ChatAgentRun["status"]): boolean {
  return terminalStatuses.has(status);
}

export interface ChatAgentPollingOptions {
  signal?: AbortSignal;
  initialDelayMs?: number;
  maxDelayMs?: number;
  initialRun?: ChatAgentRun;
  onUpdate?: (run: ChatAgentRun) => void;
}

export async function pollChatAgentRun(
  client: PlotApiClient,
  runId: string,
  options: ChatAgentPollingOptions = {},
): Promise<ChatAgentRun> {
  const initialDelay = Math.max(1, options.initialDelayMs ?? 500);
  const maxDelay = Math.max(initialDelay, options.maxDelayMs ?? 4_000);
  let fallbackDelay = initialDelay;
  let initialRun = options.initialRun;

  while (true) {
    throwIfAborted(options.signal);
    const run = initialRun ?? await client.getChatAgentRun(runId, { signal: options.signal });
    initialRun = undefined;
    options.onUpdate?.(run);
    if (run.generationRunId || isTerminalChatAgentStatus(run.status)) return run;
    await abortableDelay(Math.min(fallbackDelay, maxDelay), options.signal);
    fallbackDelay = Math.min(fallbackDelay * 2, maxDelay);
  }
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
  return new DOMException("Chat Agent polling was aborted", "AbortError");
}
