import { describe, expect, it, vi } from "vitest";
import { createPlotApiClient, PlotApiError } from "./index";

const snapshot = { runId: "run-1", epoch: 1, revision: 2, phase: "WRITING", responseText: "안녕 🌱", draftParagraphs: ["초안"], status: "RUNNING", artifactId: null, failureCode: null };
function response(text: string, size = 1) {
  const bytes = new TextEncoder().encode(text);
  return new Response(new ReadableStream({ start(controller) {
    for (let i = 0; i < bytes.length; i += size) controller.enqueue(bytes.slice(i, i + size));
    controller.close();
  } }), { headers: { "Content-Type": "text/event-stream;charset=UTF-8" } });
}

describe("Chat run stream", () => {
  it("decodes fragmented UTF-8, CRLF, comments and multiple snapshots with workspace scope", async () => {
    const final = { ...snapshot, revision: 3, phase: "COMPLETE", status: "SUCCEEDED" };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response(`: heartbeat\r\n\r\nevent: snapshot\r\ndata: ${JSON.stringify({ ...snapshot, privateField: "hidden" })}\r\n\r\nevent: snapshot\ndata: ${JSON.stringify(final)}\n\n`));
    const received: unknown[] = [];
    await createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" }).streamChatAgentRun("run-1", { onSnapshot: (value) => received.push(value) });
    expect(received).toEqual([snapshot, final]);
    expect(fetcher).toHaveBeenCalledWith("/api/plot/agent-runs/run-1/stream", expect.objectContaining({ cache: "no-store" }));
    const headers = new Headers(fetcher.mock.calls[0]?.[1]?.headers);
    expect(headers.get("accept")).toBe("text/event-stream");
    expect(headers.get("X-Plot-Workspace-Id")).toBe("workspace-1");
  });

  it("rejects wrong run, malformed data and incomplete EOF without exposing an invalid snapshot", async () => {
    for (const data of [JSON.stringify({ ...snapshot, runId: "other" }), JSON.stringify({ ...snapshot, revision: -1 }), "{bad"]) {
      const onSnapshot = vi.fn();
      const client = createPlotApiClient({ fetch: vi.fn().mockResolvedValue(response(`event: snapshot\ndata: ${data}\n\n`)) });
      await expect(client.streamChatAgentRun("run-1", { onSnapshot })).rejects.toBeInstanceOf(PlotApiError);
      expect(onSnapshot).not.toHaveBeenCalled();
    }
    const client = createPlotApiClient({ fetch: vi.fn().mockResolvedValue(response(`event: snapshot\ndata: ${JSON.stringify(snapshot)}`)) });
    await expect(client.streamChatAgentRun("run-1", { onSnapshot: vi.fn() })).rejects.toBeInstanceOf(PlotApiError);
  });

  it("maps initial HTTP and in-stream access errors and rejects non-stream responses", async () => {
    for (const [result, status] of [
      [Response.json({ error: "FORBIDDEN", message: "Denied" }, { status: 403 }), 403],
      [response('event: error\ndata: {"code":"ACCESS_DENIED"}\n\n'), 403],
      [Response.json(snapshot), 503],
    ] as const) {
      const client = createPlotApiClient({ fetch: vi.fn().mockResolvedValue(result) });
      await expect(client.streamChatAgentRun("run-1", { onSnapshot: vi.fn() })).rejects.toMatchObject({ status });
    }
  });

  it("cancels a stalled reader on abort and stops reading after terminal snapshot", async () => {
    const cancelled = vi.fn();
    const controller = new AbortController();
    const stalled = new Response(new ReadableStream({ cancel: cancelled }), { headers: { "Content-Type": "text/event-stream" } });
    const client = createPlotApiClient({ fetch: vi.fn().mockResolvedValue(stalled) });
    const pending = client.streamChatAgentRun("run-1", { signal: controller.signal, onSnapshot: vi.fn() });
    await Promise.resolve();
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(cancelled).toHaveBeenCalledTimes(1);
    const terminal = { ...snapshot, phase: "FAILED", status: "FAILED" };
    const body = new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode(`event: snapshot\ndata: ${JSON.stringify(terminal)}\n\n`)); }, cancel: cancelled });
    await createPlotApiClient({ fetch: vi.fn().mockResolvedValue(new Response(body, { headers: { "Content-Type": "text/event-stream" } })) }).streamChatAgentRun("run-1", { onSnapshot: vi.fn() });
    expect(cancelled).toHaveBeenCalledTimes(2);
  });
  it("accepts the real initial queued snapshot before the worker has persisted an epoch", async () => {
    const queued = { ...snapshot, epoch: 0, revision: 0, phase: "QUEUED", status: "QUEUED", responseText: "", draftParagraphs: [] };
    const onSnapshot = vi.fn();
    await createPlotApiClient({ fetch: vi.fn().mockResolvedValue(response(`event: snapshot\ndata: ${JSON.stringify(queued)}\n\n`)) }).streamChatAgentRun("run-1", { onSnapshot });
    expect(onSnapshot).toHaveBeenCalledWith(queued);
  });

});
