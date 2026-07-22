import { describe, expect, it, vi } from "vitest";

import { PlotApiError, createPlotApiClient } from "./index";

describe("Plot API client", () => {
  it("hydrates provider-neutral generation references from connected source scopes", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json([{ id: "connection-1", installationId: 1, status: "ACTIVE", repositories: [
        { id: "scope-1", externalRepositoryId: 42, owner: "acme", name: "plot", displayName: "acme/plot", url: "https://github.com/acme/plot", status: "ACTIVE" },
      ] }]))
      .mockResolvedValueOnce(Response.json({ items: [
        { id: "block-1", sourceOrigin: "GITHUB", sourceKind: "PULL_REQUEST", title: "Clarify recovery", body: "Recovery copy", url: "https://github.com/acme/plot/pull/184", canonicalUrl: null, sourceCreatedAt: "2026-07-03T00:00:00Z", status: "ACTIVE" },
      ], page: 0, size: 100, totalItems: 1, totalPages: 1 }));
    const client = createPlotApiClient({ fetch: fetcher });

    await expect(client.listGenerationReferences()).resolves.toEqual([
      expect.objectContaining({ id: "block-1", sourceScopeId: "scope-1", provider: "GITHUB", sourceLabel: "Clarify recovery", repositoryLabel: "acme/plot" }),
    ]);
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/plot/github/connections",
      "/api/plot/blocks?sourceScopeId=scope-1&page=0&size=100",
    ]);
  });

  it("loads every writing-block page for a source scope", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json([{ status: "ACTIVE", repositories: [{ id: "scope-1", displayName: "acme/plot", status: "ACTIVE" }] }]))
      .mockResolvedValueOnce(Response.json({ items: [{ id: "block-1", sourceKind: "PULL_REQUEST", title: "First", body: "A", url: null, canonicalUrl: null, sourceCreatedAt: null, status: "ACTIVE" }], page: 0, size: 100, totalItems: 2, totalPages: 2 }))
      .mockResolvedValueOnce(Response.json({ items: [{ id: "block-2", sourceKind: "PULL_REQUEST", title: "Second", body: "B", url: null, canonicalUrl: null, sourceCreatedAt: null, status: "ACTIVE" }], page: 1, size: 100, totalItems: 2, totalPages: 2 }));
    const client = createPlotApiClient({ fetch: fetcher });

    await expect(client.listGenerationReferences()).resolves.toEqual([
      expect.objectContaining({ id: "block-1" }),
      expect.objectContaining({ id: "block-2" }),
    ]);
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it("serializes provider-neutral generation requests once and preserves abort", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({
        id: "run-1",
        status: "QUEUED",
        semanticRewriteAttempt: 0,
        pollAfterMs: 500,
        failureCode: null,
        evidence: [
          {
            id: "e-1",
            provider: "SLACK",
            sourceKind: "message",
            sourceLabel: "launch-room",
            originalUrl: "https://slack.test/archive/1",
            snapshotExcerpt: "Shipped",
            contentHash: "hash",
          },
        ],
        sentences: [],
        pendingIntervention: null,
        contentPack: null,
      }),
    );
    const client = createPlotApiClient({ fetch: fetcher });
    const controller = new AbortController();

    const result = await client.createGeneration(
      { sourceScopeId: "scope-1", writingBlockIds: ["block-1"], instruction: "Notes" },
      "key-1",
      { signal: controller.signal },
    );

    expect(result.evidence[0]?.provider).toBe("SLACK");
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/plot/generations",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ sourceScopeId: "scope-1", writingBlockIds: ["block-1"], instruction: "Notes" }),
        signal: controller.signal,
        headers: expect.objectContaining({ "Idempotency-Key": "key-1" }),
      }),
    );
  });

  it("preserves stable structured errors and details", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json(
        {
          error: "EXPORT_CONFIRMATION_REQUIRED",
          message: "Confirm export",
          details: { sentenceIds: ["sentence-1"] },
        },
        { status: 409 },
      ),
    );
    const client = createPlotApiClient({ fetch: fetcher });

    await expect(client.exportVariant("variant-1", { acknowledgeUnresolved: false, disposition: "COPY" })).rejects.toMatchObject<PlotApiError>({
      code: "EXPORT_CONFIRMATION_REQUIRED",
      status: 409,
      details: { sentenceIds: ["sentence-1"] },
    });

		const modelClient = createPlotApiClient({
			fetch: vi.fn<typeof fetch>().mockResolvedValue(
				Response.json({ error: "MODEL_NOT_CONFIGURED", message: "Configure a model" }, { status: 503 }),
			),
		});
		await expect(modelClient.getGeneration("run-1")).rejects.toMatchObject<PlotApiError>({
			code: "MODEL_NOT_CONFIGURED",
			status: 503,
		});
  });

  it("forwards edit and export contracts without provider fields", async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => Response.json({ ok: true }));
    const client = createPlotApiClient({ fetch: fetcher });

    await client.editSentence("variant", "sentence", { expectedRevisionNumber: 2, body: "Edited" });
    await client.exportVariant("variant", { acknowledgeUnresolved: true, disposition: "DOWNLOAD" });

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/plot/content-variants/variant/sentences/sentence",
      "/api/plot/content-variants/variant/exports",
    ]);
  });

  it("parses checkpoint events from the SSE stream", async () => {
    const encoder = new TextEncoder();
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode('event: checkpoint\ndata: {"runId":"run-1","runStatus":"REVIEWING","sequence":2}\n\n'));
          controller.close();
        },
      }),
      { headers: { "Content-Type": "text/event-stream" } },
    ));
    const client = createPlotApiClient({ fetch: fetcher, baseUrl: "/api/plot" });
    const events: unknown[] = [];

    await client.subscribeGenerationEvents("run-1", { onEvent: (event) => events.push(event) });

    expect(events).toEqual([{ runId: "run-1", runStatus: "REVIEWING", sequence: 2 }]);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/plot/generations/run-1/events",
      expect.objectContaining({ headers: expect.objectContaining({ Accept: "text/event-stream" }) }),
    );
  });

  it("resolves the workspace ID for each request and event stream", async () => {
    const encoder = new TextEncoder();
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (input) => {
      if (String(input).endsWith("/events")) {
        return new Response(new ReadableStream({
          start(controller) {
            controller.enqueue(encoder.encode('event: checkpoint\ndata: {"runId":"run-1","runStatus":"WRITING","sequence":1}\n\n'));
            controller.close();
          },
        }));
      }
      return Response.json({});
    });
    let workspaceId = "stale-workspace";
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: () => workspaceId });

    await client.getGeneration("run-1");
    workspaceId = "resolved-workspace";
    await client.getGeneration("run-2");
    await client.subscribeGenerationEvents!("run-1", { onEvent: () => undefined });

    expect(fetcher.mock.calls.map(([, init]) => new Headers(init?.headers).get("X-Plot-Workspace-Id"))).toEqual([
      "stale-workspace",
      "resolved-workspace",
      "resolved-workspace",
    ]);
  });

  it("buffers split events and rejects invalid event payloads", async () => {
    const encoder = new TextEncoder();
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode("event: checkpoint\ndata: {\"runId\":\"run-1\",\"runStatus\":\"WR"));
          controller.enqueue(encoder.encode("ITING\",\"sequence\":3}\n\n"));
          controller.close();
        },
      }),
      { headers: { "Content-Type": "text/event-stream" } },
    ));
    const client = createPlotApiClient({ fetch: fetcher });
    const events: unknown[] = [];

    await client.subscribeGenerationEvents!("run-1", { onEvent: (event) => events.push(event) });
    expect(events).toEqual([{ runId: "run-1", runStatus: "WRITING", sequence: 3 }]);

    const invalidClient = createPlotApiClient({
      fetch: vi.fn<typeof fetch>().mockResolvedValue(new Response(
        "event: checkpoint\ndata: {\"runId\":\"run-1\",\"runStatus\":\"UNKNOWN\",\"sequence\":1}\n\n",
        { headers: { "Content-Type": "text/event-stream" } },
      )),
    });
    await expect(invalidClient.subscribeGenerationEvents!("run-1", { onEvent: () => undefined })).rejects.toMatchObject<PlotApiError>({
      code: "INVALID_EVENT_STREAM",
    });

    const malformedClient = createPlotApiClient({
      fetch: vi.fn<typeof fetch>().mockResolvedValue(new Response(
        "event: checkpoint\ndata: {not-json}\n\n",
        { headers: { "Content-Type": "text/event-stream" } },
      )),
    });
    await expect(malformedClient.subscribeGenerationEvents!("run-1", { onEvent: () => undefined })).rejects.toMatchObject<PlotApiError>({
      code: "INVALID_EVENT_STREAM",
    });
  });
});
