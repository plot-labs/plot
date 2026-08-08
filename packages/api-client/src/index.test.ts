import { describe, expect, it, vi } from "vitest";

import { PlotApiError, createPlotApiClient } from "./index";

describe("Plot API client", () => {
  it("uses the session contracts with workspace scoping", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json([]))
      .mockResolvedValueOnce(Response.json({ id: "session-1", title: "Release", status: "OPEN", latestGenerationId: null, lastActivityAt: null, createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z" }))
      .mockResolvedValueOnce(Response.json({ id: "session-1", title: "Release", status: "OPEN", latestGenerationId: "run-1", lastActivityAt: "2026-07-01T00:01:00Z", createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:01:00Z" }));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    await client.listSessions();
    await client.createSession({ title: "Release" });
    await client.updateSession("session-1", { latestGenerationId: "run-1" });

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/plot/sessions",
      "/api/plot/sessions",
      "/api/plot/sessions/session-1",
    ]);
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: "POST", body: JSON.stringify({ title: "Release" }) });
    expect(fetcher.mock.calls[2]?.[1]).toMatchObject({ method: "PATCH", body: JSON.stringify({ latestGenerationId: "run-1" }) });
    expect(new Headers(fetcher.mock.calls[2]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
  });

  it("uses the GitHub onboarding contracts with workspace scoping", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json({ installUrl: "https://github.test/install", expiresAt: "2026-07-01T00:00:00Z" }))
      .mockResolvedValueOnce(Response.json([{ id: "connection-1", status: "ACTIVE", repositories: [] }]))
      .mockResolvedValueOnce(Response.json([{ id: null, externalRepositoryId: 42, owner: "acme", name: "plot", displayName: "acme/plot", url: "https://github.com/acme/plot", status: null }]))
      .mockResolvedValueOnce(Response.json({ id: "scope-1", externalRepositoryId: 42, owner: "acme", name: "plot", displayName: "acme/plot", url: "https://github.com/acme/plot", status: "ACTIVE" }))
      .mockResolvedValueOnce(Response.json({ status: "ACTIVE", analysisStatus: "COMPLETED", releaseConvention: "SEMVER_V" }))
      .mockResolvedValueOnce(Response.json({ status: "ACTIVE", analysisStatus: "QUEUED", releaseConvention: null }))
      .mockResolvedValueOnce(Response.json({ sourceScopeId: "scope-1", status: "QUEUED", attemptCount: 0, errorCode: null, nextAttemptAt: null, verifiedAt: null }))
      .mockResolvedValueOnce(Response.json({ id: "import-1", sourceScopeId: "scope-1", status: "COMPLETED" }));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    await client.createGitHubInstallationRequest();
    await client.listGitHubConnections();
    await client.listGitHubRepositories("connection-1");
    await client.connectGitHubRepository("connection-1", 42);
    await client.getGitHubRepositoryMonitoring("scope-1");
    await client.retryGitHubRepositoryMonitoring("scope-1");
    await client.recheckGitHubRepositoryAccess("scope-1", "CHECK_AGAIN");
    await client.importGitHubRepository("scope-1", { from: "2026-06-01T00:00:00.000Z", to: "2026-07-01T00:00:00.000Z" });

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/plot/github/installations/requests",
      "/api/plot/github/connections",
      "/api/plot/github/connections/connection-1/repositories",
      "/api/plot/github/repositories/42",
      "/api/plot/github/repositories/scope-1/monitoring",
      "/api/plot/github/repositories/scope-1/monitoring/retry",
      "/api/plot/github/repositories/scope-1/access-check?trigger=CHECK_AGAIN",
      "/api/plot/github/repositories/scope-1/imports",
    ]);
    expect(fetcher.mock.calls[3]?.[1]).toMatchObject({ method: "PUT", body: JSON.stringify({ connectionId: "connection-1" }) });
    expect(fetcher.mock.calls[5]?.[1]).toMatchObject({ method: "POST" });
    expect(fetcher.mock.calls[6]?.[1]).toMatchObject({ method: "POST" });
    expect(fetcher.mock.calls[7]?.[1]).toMatchObject({ method: "POST", body: JSON.stringify({ from: "2026-06-01T00:00:00.000Z", to: "2026-07-01T00:00:00.000Z" }) });
    expect(new Headers(fetcher.mock.calls[7]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
  });

  it("disconnects a GitHub repository scope", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    await client.disconnectGitHubRepository("scope-1");

    expect(fetcher).toHaveBeenCalledWith("/api/plot/github/repositories/scope-1", expect.objectContaining({ method: "DELETE" }));
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
  });

  it("loads nullable release activity and retries an exact failed request", async () => {
    const activity = {
      id: "request-1",
      sourceScopeId: "scope-1",
      tagName: "v1.2.0",
      status: "FAILED",
      baseSha: "base",
      headSha: "head",
      generationRunId: null,
      artifactId: null,
      errorCode: "GENERATION_FAILED",
      createdAt: "2026-07-30T00:00:00Z",
      updatedAt: "2026-07-30T00:01:00Z",
    };
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(Response.json({ ...activity, status: "QUEUED", errorCode: null }));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    await expect(client.getGitHubReleaseActivity("scope-1")).resolves.toBeNull();
    await expect(client.retryGitHubReleaseDraft("scope-1", "request-1")).resolves.toMatchObject({
      id: "request-1",
      status: "QUEUED",
      artifactId: null,
    });

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/plot/github/repositories/scope-1/release-activity",
      "/api/plot/github/repositories/scope-1/release-activity/request-1/retry",
    ]);
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: "POST" });
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
  });

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
            provider: "GITHUB",
            sourceKind: "message",
            sourceLabel: "launch-room",
            originalUrl: "https://github.com/acme/plot/pull/1",
            snapshotExcerpt: "Shipped",
            contentHash: "hash",
          },
        ],
        sentences: [],
        artifact: null,
      }),
    );
    const client = createPlotApiClient({ fetch: fetcher });
    const controller = new AbortController();

    const result = await client.createGeneration(
      { sourceScopeId: "scope-1", writingBlockIds: ["block-1"], instruction: "Notes", workSessionId: "session-1" },
      "key-1",
      { signal: controller.signal },
    );

    expect(result.evidence[0]?.provider).toBe("GITHUB");
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/plot/generations",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ sourceScopeId: "scope-1", writingBlockIds: ["block-1"], instruction: "Notes", workSessionId: "session-1" }),
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
			details: { warnings: [{ key: "warning-1", sentenceNumber: 2, excerpt: "A claim" }] },
        },
        { status: 409 },
      ),
    );
    const client = createPlotApiClient({ fetch: fetcher });

		await expect(client.exportArtifactVariant("variant-1", { expectedRevisionNumber: 3, includeSources: false, acknowledgeUnresolved: false, disposition: "COPY" })).rejects.toMatchObject<PlotApiError>({
			code: "EXPORT_CONFIRMATION_REQUIRED",
			status: 409,
			details: { warnings: [{ key: "warning-1", sentenceNumber: 2, excerpt: "A claim" }] },
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

	it("loads ordered session generations and content-only artifact history", async () => {
		const fetcher = vi.fn<typeof fetch>()
			.mockResolvedValueOnce(Response.json([{ id: "run-1", status: "READY", instruction: "Changelog", createdAt: "2026-08-01T00:00:00Z", completedAt: "2026-08-01T00:01:00Z", failureCode: null, artifact: null }]))
			.mockResolvedValueOnce(Response.json([{ position: 0, createdAt: "2026-08-01T00:01:00Z", cause: "Edited by you" }]))
			.mockResolvedValueOnce(Response.json({ createdAt: "2026-08-01T00:01:00Z", cause: "Edited by you", readOnly: true, artifact: { id: "artifact-1" } }));
		const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

		await client.listSessionGenerations("session-1");
		await client.listArtifactHistory("variant-1");
		await client.getArtifactHistoryAt("variant-1", 0);

		expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
			"/api/plot/sessions/session-1/generations",
			"/api/plot/artifact-variants/variant-1/history",
			"/api/plot/artifact-variants/variant-1/history/at/0",
		]);
		expect(new Headers(fetcher.mock.calls[2]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
	});

	it("loads and saves the whole artifact with revision-bound source export inputs", async () => {
		const fetcher = vi.fn<typeof fetch>()
			.mockResolvedValueOnce(Response.json({ id: "pack-1", variant: { id: "variant-1", revisionNumber: 3, sentences: [], sources: [] } }))
			.mockResolvedValueOnce(Response.json({ id: "pack-1", variant: { id: "variant-1", revisionNumber: 4, sentences: [], sources: [] } }))
			.mockResolvedValueOnce(Response.json({
				exportId: "export-1",
				artifactRevisionId: "artifact-4",
				artifactRevisionNumber: 4,
				disposition: "DOWNLOAD",
				filename: "draft.md",
				mediaType: "text/markdown;charset=UTF-8",
				text: "Draft",
				unresolvedCount: 0,
				warningAcknowledged: false,
				includeSources: true,
			}));
		const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });
		const controller = new AbortController();
		const lexicalContent = { root: { children: [] } };
		const statements = [{ id: "sentence-1", orderIndex: 0, body: "Draft" }];

		await client.getArtifactVariant("variant-1", { signal: controller.signal });
		await client.saveArtifactVariant("variant-1", { expectedRevisionNumber: 3, lexicalContent, statements });
		await client.exportArtifactVariant("variant-1", {
			expectedRevisionNumber: 4,
			includeSources: true,
			acknowledgeUnresolved: false,
			acknowledgedWarningKeys: [],
			disposition: "DOWNLOAD",
		});

		expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
			"/api/plot/artifact-variants/variant-1",
			"/api/plot/artifact-variants/variant-1",
			"/api/plot/artifact-variants/variant-1/exports",
		]);
		expect(fetcher.mock.calls[1]?.[1]).toMatchObject({
			method: "PATCH",
			body: JSON.stringify({ expectedRevisionNumber: 3, lexicalContent, statements }),
		});
		expect(fetcher.mock.calls[2]?.[1]).toMatchObject({
			method: "POST",
			body: JSON.stringify({
				expectedRevisionNumber: 4,
				includeSources: true,
				acknowledgeUnresolved: false,
				acknowledgedWarningKeys: [],
				disposition: "DOWNLOAD",
			}),
		});
		expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
	});

	it("forwards edit and export contracts without provider fields", async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => Response.json({ ok: true }));
    const client = createPlotApiClient({ fetch: fetcher });

    await client.editSentence("variant", "sentence", { expectedRevisionNumber: 2, body: "Edited" });
    await client.exportArtifactVariant("variant", { expectedRevisionNumber: 2, includeSources: false, acknowledgeUnresolved: true, disposition: "DOWNLOAD" });

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/plot/artifact-variants/variant/sentences/sentence",
      "/api/plot/artifact-variants/variant/exports",
    ]);
  });

  it("resolves the workspace ID for each request", async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => Response.json({}));
    let workspaceId = "stale-workspace";
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: () => workspaceId });

    await client.getGeneration("run-1");
    workspaceId = "resolved-workspace";
    await client.getGeneration("run-2");

    expect(fetcher.mock.calls.map(([, init]) => new Headers(init?.headers).get("X-Plot-Workspace-Id"))).toEqual([
      "stale-workspace",
      "resolved-workspace",
    ]);
  });
});
