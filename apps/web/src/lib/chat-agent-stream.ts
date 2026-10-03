import { PlotApiError, type ChatAgentRun, type ChatRunSnapshot, type PlotApiClient } from "@plot/api-client";

export function isTerminalChatAgentStatus(status: ChatAgentRun["status"]): boolean {
  return status === "SUCCEEDED" || status === "FAILED";
}

export interface ChatAgentStreamOptions {
  signal?: AbortSignal;
  initialDelayMs?: number;
  maxDelayMs?: number;
  initialRun?: ChatAgentRun;
  onUpdate?: (run: ChatAgentRun) => void;
  onProgress?: (snapshot: ChatRunSnapshot) => void;
}

export async function watchChatAgentRun(client: PlotApiClient, runId: string, options: ChatAgentStreamOptions = {}): Promise<ChatAgentRun> {
  const initialDelay = Math.max(1, options.initialDelayMs ?? 500);
  const maxDelay = Math.max(initialDelay, options.maxDelayMs ?? 4_000);
  let delay = initialDelay;
  let latest: ChatRunSnapshot | null = null;
  let run = options.initialRun;
  let terminal = Boolean(run && isTerminalChatAgentStatus(run.status));
  if (run) options.onUpdate?.(run);
  while (true) {
    throwIfAborted(options.signal);
    try {
      if (!run || terminal) {
        run = await client.getChatAgentRun(runId, { signal: options.signal });
        throwIfAborted(options.signal);
        options.onUpdate?.(run);
        if (isTerminalChatAgentStatus(run.status)) return run;
        if (terminal) {
          await abortableDelay(delay, options.signal);
          delay = Math.min(delay * 2, maxDelay);
          continue;
        }
      }
      let connectionOpen = true;
      try {
        await client.streamChatAgentRun(runId, {
          signal: options.signal,
          onSnapshot: (snapshot) => {
            if (!connectionOpen || options.signal?.aborted || snapshot.runId !== runId ||
              (latest && (snapshot.epoch < latest.epoch || (snapshot.epoch === latest.epoch && snapshot.revision <= latest.revision)))) return;
            latest = snapshot;
            terminal = isTerminalChatAgentStatus(snapshot.status);
            delay = initialDelay;
            options.onProgress?.(snapshot);
          },
        });
      } finally {
        connectionOpen = false;
      }
      throwIfAborted(options.signal);
      if (terminal) continue;
    } catch (error) {
      throwIfAborted(options.signal);
      if (error instanceof PlotApiError && [401, 403, 404].includes(error.status)) throw error;
      if (error instanceof DOMException && error.name === "AbortError") throw error;
    }
    await abortableDelay(delay, options.signal);
    delay = Math.min(delay * 2, maxDelay);
  }
}

export function abortableDelay(ms: number, signal?: AbortSignal): Promise<void> {
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
  return new DOMException("Chat Agent stream was aborted", "AbortError");
}
