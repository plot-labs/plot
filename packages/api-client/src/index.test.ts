import { describe, expect, it, vi } from "vitest";

import { PlotApiError, createPlotApiClient } from "./index";

describe("Plot API client", () => {
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

  it("forwards edit resolve and export contracts without provider fields", async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => Response.json({ ok: true }));
    const client = createPlotApiClient({ fetch: fetcher });

    await client.editSentence("variant", "sentence", { expectedRevisionNumber: 2, body: "Edited" });
    await client.resolveConflict("run", "intervention", { expectedVersion: 1, action: "PREFER_SOURCE", preferredEvidenceId: "e-1" });
    await client.exportVariant("variant", { acknowledgeUnresolved: true, disposition: "DOWNLOAD" });

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/plot/content-variants/variant/sentences/sentence",
      "/api/plot/generations/run/interventions/intervention/resolution",
      "/api/plot/content-variants/variant/exports",
    ]);
  });
});
