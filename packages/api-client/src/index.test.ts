import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";


import type { PlotApiClient, WorkspaceSummary } from "./index";
import { PlotApiError, createPlotApiClient, fetchPublicChangelog, fetchPublicChangelogEntry } from "./index";

function workspaceSummary(overrides: Partial<WorkspaceSummary> = {}): WorkspaceSummary {
  return {
    id: "workspace-1",
    name: "Personal",
    slug: "personal",
    status: "ACTIVE",
    logoUrl: null,
    organizationId: null,
    publicCitationsEnabled: true,
    plan: "founding",
    entitlementStatus: "active",
    accessMode: "full",
    capabilities: {
      generate: true,
      edit: true,
      publish: true,
      export: true,
      configure: true,
      unpublish: true,
    },
    trialEndsAt: "2026-09-01T00:00:00Z",
    role: "OWNER",
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-17T00:00:00Z",
    ...overrides,
  };
}

describe("Plot API client", () => {
  it("creates a workspace with workspace-independent authentication", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(workspaceSummary({
      id: "workspace-2",
      name: "Product",
      slug: "workspace-12345678",
    })));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    await client.createWorkspace({ name: "Product" });

    expect(fetcher).toHaveBeenCalledWith("/api/plot/workspaces", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ name: "Product" }),
    }));
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
  });

  it("reads and updates a workspace profile with workspace scoping", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json(workspaceSummary()))
      .mockResolvedValueOnce(Response.json(workspaceSummary({ name: "Product", logoUrl: "data:image/png;base64,abc" })));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    await client.getWorkspace("workspace-1");
    await client.updateWorkspace("workspace-1", {
      name: "Product",
      logoUrl: "data:image/png;base64,abc",
      publicCitationsEnabled: false,
    });

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/plot/workspaces/workspace-1",
      "/api/plot/workspaces/workspace-1",
    ]);
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject({
      method: "PATCH",
      body: JSON.stringify({
        name: "Product",
        logoUrl: "data:image/png;base64,abc",
        publicCitationsEnabled: false,
      }),
    });
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
  });

  it("uses the routine automation contracts with workspace scoping", async () => {
    const routine = {
      id: "routine-1",
      name: "Weekly release note",
      sourceScopeId: "scope-1",
      sourceLabel: "acme/plot",
      instruction: "Draft a release note",
      cadence: "WEEKLY",
      enabled: true,
      lastRunAt: null,
      nextRunAt: "2026-08-16T00:00:00Z",
      lastRunStatus: null,
      lastErrorCode: null,
      contextSourceScopeIds: [],
      latestExecution: null,
      createdAt: "2026-08-09T00:00:00Z",
      updatedAt: "2026-08-09T00:00:00Z",
    };
    const agentRun = {
      id: "agent-run-1",
      routineExecutionId: "execution-1",
      routineId: "routine-1",
      chatId: "chat-1",
      status: "SUCCEEDED",
      failureCode: null,
      artifactId: "artifact-1",
      startedAt: "2026-08-09T00:01:00Z",
      finishedAt: "2026-08-09T00:02:00Z",
      steps: [{
        sequence: 1,
        kind: "ARTIFACT_HANDOFF",
        status: "SUCCEEDED",
        toolName: null,
        failureCode: null,
        artifactId: "artifact-1",
        startedAt: "2026-08-09T00:01:00Z",
        finishedAt: "2026-08-09T00:02:00Z",
      }],
    };
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json([routine]))
      .mockResolvedValueOnce(Response.json(routine))
      .mockResolvedValueOnce(Response.json(routine))
      .mockResolvedValueOnce(Response.json({ ...routine, enabled: false }))
      .mockResolvedValueOnce(Response.json({ ...routine, latestExecution: { id: "execution-1", status: "DISPATCHED", chatId: "chat-1", agentRunId: "agent-run-1", agentRunStatus: "QUEUED", artifactId: null, errorCode: null, startedAt: null, finishedAt: null, releaseRequestId: null } }))
      .mockResolvedValueOnce(Response.json(agentRun));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    await client.listRoutines();
    await client.getRoutine("routine-1");
    await client.createRoutine({ name: routine.name, sourceScopeId: routine.sourceScopeId, contextSourceScopeIds: ["scope-2"], instruction: routine.instruction, cadence: "WEEKLY" });
    await client.updateRoutine("routine-1", { enabled: false });
    await client.runRoutineNow("routine-1", "manual-request-1");
    await client.getRoutineAgentRun("routine-1", "agent-run-1");

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/plot/routines",
      "/api/plot/routines/routine-1",
      "/api/plot/routines",
      "/api/plot/routines/routine-1",
      "/api/plot/routines/routine-1/run",
      "/api/plot/routines/routine-1/agent-runs/agent-run-1",
    ]);
    expect(fetcher.mock.calls[2]?.[1]).toMatchObject({
      method: "POST",
      body: JSON.stringify({ name: routine.name, sourceScopeId: routine.sourceScopeId, contextSourceScopeIds: ["scope-2"], instruction: routine.instruction, cadence: "WEEKLY" }),
    });
    expect(fetcher.mock.calls[3]?.[1]).toMatchObject({ method: "PATCH", body: JSON.stringify({ enabled: false }) });
    expect(fetcher.mock.calls[4]?.[1]).toMatchObject({ method: "POST" });
    expect(new Headers(fetcher.mock.calls[4]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
    expect(new Headers(fetcher.mock.calls[4]?.[1]?.headers).get("Idempotency-Key")).toBe("manual-request-1");
    expect(agentRun.steps).toHaveLength(1);
  });

  it("uses the session list contract with workspace scoping", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json([]));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    await client.listSessions();

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual(["/api/plot/sessions"]);
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
  });

  it("adopts a Chat Agent run with a stable idempotency key", async () => {
    const response = {
      id: "agent-run-1",
      chatId: "session-1",
      status: "QUEUED",
      failureCode: null,
      artifactId: null,
      createdAt: "2026-08-12T00:00:00Z",
      updatedAt: "2026-08-12T00:00:00Z",
    };
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json(response, { status: 202 }))
      .mockResolvedValueOnce(Response.json(response));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    await client.createChatAgentRun({ instruction: "Explore the source", writingBlockIds: ["block-1"] }, "chat-request-1");
    await client.getChatAgentRun("agent-run-1");

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/plot/agent-runs",
      "/api/plot/agent-runs/agent-run-1",
    ]);
    expect(fetcher.mock.calls[0]?.[1]).toMatchObject({
      method: "POST",
      body: JSON.stringify({ instruction: "Explore the source", writingBlockIds: ["block-1"] }),
    });
		expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("Idempotency-Key")).toBe("chat-request-1");
		expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
  });

  it("syncs an existing GitHub App installation", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json({
        connectionId: "connection-1",
        installationId: 77,
        repositories: [],
      }));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    await client.syncGitHubInstallation();

    expect(fetcher).toHaveBeenCalledWith(
      "/api/plot/github/installations/sync",
      expect.objectContaining({ method: "POST" }),
    );
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
  });

  it("starts product GitHub OAuth with a safe return path", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json({
      authorizationUrl: "https://github.com/login/oauth/authorize?state=opaque-state",
      expiresAt: "2026-09-11T00:15:00Z",
    }));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    await client.startGitHubProductOAuth("/settings/integrations");

    expect(fetcher).toHaveBeenCalledWith(
      "/api/plot/github/oauth/start?returnTo=%2Fsettings%2Fintegrations",
      expect.objectContaining({ method: "POST" }),
    );
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
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
      artifactId: null,
      errorCode: "AGENT_RUN_FAILED",
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

  it("loads a release request by id and posts an explicit range", async () => {
    const activity = {
      id: "request-1",
      sourceScopeId: "scope-1",
      tagName: "v1.2.0",
      status: "NEEDS_RANGE",
      baseSha: null,
      headSha: "a".repeat(40),
      artifactId: null,
      errorCode: null,
      createdAt: "2026-07-30T00:00:00Z",
      updatedAt: "2026-07-30T00:01:00Z",
    };
    const range = { baseSha: "B".repeat(40), headSha: "A".repeat(40) };
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json(activity))
      .mockResolvedValueOnce(Response.json({ ...activity, status: "QUEUED", baseSha: "b".repeat(40) }, { status: 202 }));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    await expect(client.getGitHubReleaseActivityById("scope-1", "request-1")).resolves.toMatchObject({
      id: "request-1",
      status: "NEEDS_RANGE",
    });
    await expect(client.selectGitHubReleaseRange("scope-1", "request-1", range)).resolves.toMatchObject({
      status: "QUEUED",
      baseSha: "b".repeat(40),
    });

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/plot/github/repositories/scope-1/release-activity/request-1",
      "/api/plot/github/repositories/scope-1/release-activity/request-1/range",
    ]);
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject({
      method: "POST",
      body: JSON.stringify({ baseSha: "b".repeat(40), headSha: "a".repeat(40) }),
    });
  });

  it("hydrates source references from connected source scopes", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json([{ id: "connection-1", installationId: 1, status: "ACTIVE", repositories: [
        { id: "scope-1", externalRepositoryId: 42, owner: "acme", name: "plot", displayName: "acme/plot", url: "https://github.com/acme/plot", status: "ACTIVE" },
      ] }]))
      .mockResolvedValueOnce(Response.json({ items: [
        { id: "block-1", sourceOrigin: "GITHUB", sourceKind: "PULL_REQUEST", title: "Clarify recovery", body: "Recovery copy", url: "https://github.com/acme/plot/pull/184", canonicalUrl: null, sourceCreatedAt: "2026-07-03T00:00:00Z", status: "ACTIVE" },
      ], page: 0, size: 100, totalItems: 1, totalPages: 1 }));
    const client = createPlotApiClient({ fetch: fetcher });

    await expect(client.listSourceReferences()).resolves.toEqual([
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

    await expect(client.listSourceReferences()).resolves.toEqual([
      expect.objectContaining({ id: "block-1" }),
      expect.objectContaining({ id: "block-2" }),
    ]);
    expect(fetcher).toHaveBeenCalledTimes(3);
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
		await expect(modelClient.getChatAgentRun("agent-run-1")).rejects.toMatchObject<PlotApiError>({
			code: "MODEL_NOT_CONFIGURED",
			status: 503,
		});
	});

	it("loads ordered Chat Agent runs and content-only artifact history", async () => {
		const fetcher = vi.fn<typeof fetch>()
			.mockResolvedValueOnce(Response.json([{ id: "agent-1", chatId: "session-1", instruction: "Changelog", status: "SUCCEEDED", failureCode: null, artifactId: "artifact-1", artifact: { id: "artifact-1", status: "READY", title: "Changelog", updatedAt: "2026-08-01T00:01:00Z" }, createdAt: "2026-08-01T00:00:00Z", updatedAt: "2026-08-01T00:01:00Z" }]))
			.mockResolvedValueOnce(Response.json([{ position: 0, createdAt: "2026-08-01T00:01:00Z", cause: "Edited by you" }]))
			.mockResolvedValueOnce(Response.json({ createdAt: "2026-08-01T00:01:00Z", cause: "Edited by you", readOnly: true, artifact: { id: "artifact-1" } }));
		const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

		await client.listSessionAgentRuns("session-1");
		await client.listArtifactHistory("variant-1");
		await client.getArtifactHistoryAt("variant-1", 0);

		expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
			"/api/plot/sessions/session-1/agent-runs",
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

  it("replicates an artifact to another content type", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json({
        id: "agent-run-replicated",
        chatId: "chat-replicated",
        instruction: "Write a concise launch announcement",
        contentType: "LAUNCH_ANNOUNCEMENT",
        status: "QUEUED",
        createdAt: "2026-09-08T00:00:00Z",
        updatedAt: "2026-09-08T00:00:00Z",
      }));
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });

    const run = await client.replicateArtifact(
      "artifact-1",
      { contentType: "LAUNCH_ANNOUNCEMENT", instruction: "Write a concise launch announcement" },
      "idemp-rep-1",
    );

    expect(run.id).toBe("agent-run-replicated");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/plot/artifacts/artifact-1/replicate",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Idempotency-Key": "idemp-rep-1",
          "X-Plot-Workspace-Id": "workspace-1",
        }),
        body: JSON.stringify({ contentType: "LAUNCH_ANNOUNCEMENT", instruction: "Write a concise launch announcement" }),
      }),
    );
  });


  it("resolves the workspace ID for each request", async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => Response.json({}));
    let workspaceId = "stale-workspace";
    const client = createPlotApiClient({ fetch: fetcher, workspaceId: () => workspaceId });

		await client.getChatAgentRun("agent-1");
		workspaceId = "resolved-workspace";
		await client.getChatAgentRun("agent-2");

    expect(fetcher.mock.calls.map(([, init]) => new Headers(init?.headers).get("X-Plot-Workspace-Id"))).toEqual([
      "stale-workspace",
      "resolved-workspace",
    ]);
  });
  it("matches the canonical transport manifest", async () => {
    const manifest = loadContractManifest();
    expect(manifest.version).toBe(1);
    expect(manifest.cases.length).toBeGreaterThanOrEqual(10);
    expect(manifest.cases.map((entry) => entry.surface)).toEqual(
      expect.arrayContaining(["workspace", "github", "routine", "chat", "artifact", "changelog"]),
    );

    for (const entry of manifest.cases) {
      const success = entry.successFixture ? readContractFixture(entry.successFixture) : null;
      const error = entry.errorFixture ? readContractFixture(entry.errorFixture) : null;
      const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
        entry.errorStatus
          ? Response.json(error, { status: entry.errorStatus })
          : Response.json(success, { status: entry.successStatus }),
      );
      const client = createPlotApiClient({ fetch: fetcher, workspaceId: "018fd000-0000-7000-8000-000000000002" });
      const publicBaseUrl = "http://api.test";

      if (entry.errorStatus) {

        const errorRecord = error && typeof error === "object" ? error as Record<string, unknown> : {};
        await expect(invokeContractCase(client, entry, publicBaseUrl, fetcher)).rejects.toMatchObject({
          status: entry.errorStatus,
          code: errorRecord.error,
          message: errorRecord.message,
          details: errorRecord.details ?? null,
          resourceId: errorRecord.resourceId ?? null,
        });
      } else {
        await expect(invokeContractCase(client, entry, publicBaseUrl, fetcher)).resolves.toEqual(success);
      }

      const [url, init] = fetcher.mock.calls[0] ?? [];
      if (entry.surface === "changelog") {
        expect(String(url)).toBe(`${publicBaseUrl}/api${entry.route}`);
      } else {
        expect(String(url)).toBe(`/api/plot${entry.route}`);
      }
      expect(init?.method ?? "GET").toBe(entry.method);
      const headers = new Headers(init?.headers);
      for (const requiredHeader of entry.requiredHeaders) {
        expect(headers.has(requiredHeader), `${entry.id} requires ${requiredHeader}`).toBe(true);
      }
      if (entry.requestFixture) {
        expect(init?.body).toBe(JSON.stringify(readContractFixture(entry.requestFixture)));
      } else {
        expect(init?.body).toBeUndefined();
      }
    }
  });

});
type ContractCase = {
  surface: string;
  id: string;
  clientMethod: string;
  args: unknown[];
  method: string;
  route: string;
  successStatus?: number;
  errorStatus?: number;
  requiredHeaders: string[];
  requestFixture: string | null;
  successFixture: string | null;
  errorFixture: string | null;
};

type ContractManifest = {
  version: number;
  cases: ContractCase[];
};

const contractRoot = new URL("../../../contracts/plot-api/v1/", import.meta.url);

function loadContractManifest(): ContractManifest {
  return JSON.parse(readFileSync(new URL("manifest.json", contractRoot), "utf8")) as ContractManifest;
}

function readContractFixture(path: string): unknown {
  return JSON.parse(readFileSync(new URL(path, contractRoot), "utf8")) as unknown;
}

function invokeContractCase(
  client: PlotApiClient,
  entry: ContractCase,
  publicBaseUrl: string,
  fetcher: typeof fetch,
): Promise<unknown> {
  if (entry.clientMethod === "fetchPublicChangelog") {
    return fetchPublicChangelog(String(entry.args[0]), { baseUrl: publicBaseUrl, fetch: fetcher });
  }
  if (entry.clientMethod === "fetchPublicChangelogEntry") {
    return fetchPublicChangelogEntry(String(entry.args[0]), String(entry.args[1]), { baseUrl: publicBaseUrl, fetch: fetcher });
  }
  const method = client[entry.clientMethod as keyof PlotApiClient];
  if (typeof method !== "function") throw new Error(`Unknown PlotApiClient method ${entry.clientMethod}`);
  return (method as unknown as (...args: unknown[]) => Promise<unknown>)(...entry.args);
}

it("scopes autonomy reads and versioned decisions to the selected workspace", async () => {
  const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => Response.json({ items: [] }));
  const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });
  const controller = new AbortController();
  await client.getAutonomyHome({ signal: controller.signal });
  await client.getActivity({ cursor: "cur-1", limit: 10, highWaterMark: "2026-09-13T10:00:00Z" }, { signal: controller.signal });
  await client.dismissOpportunity("opportunity-1", 7);
  await client.restoreOpportunity("opportunity-1", 8);
  expect(fetcher.mock.calls[0]?.[0]).toBe("/api/plot/autonomy/home");
  expect(fetcher.mock.calls[0]?.[1]?.signal).toBe(controller.signal);
  expect(fetcher.mock.calls[1]?.[0]).toBe("/api/plot/autonomy/activity?cursor=cur-1&limit=10&highWaterMark=2026-09-13T10%3A00%3A00Z");
  expect(fetcher.mock.calls[1]?.[1]?.signal).toBe(controller.signal);
  expect(fetcher.mock.calls[2]?.[0]).toBe("/api/plot/autonomy/opportunities/opportunity-1/dismiss");
  expect(fetcher.mock.calls[2]?.[1]?.body).toBe(JSON.stringify({ expectedVersion: 7 }));
  expect(fetcher.mock.calls[3]?.[0]).toBe("/api/plot/autonomy/opportunities/opportunity-1/restore");
  expect(fetcher.mock.calls[3]?.[1]?.body).toBe(JSON.stringify({ expectedVersion: 8 }));
  for (const [, init] of fetcher.mock.calls) {
    expect(new Headers(init?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
    expect(init?.cache).toBe("no-store");
  }
});

it("queries chat turns, response versions, and retry eligibility", async () => {
  const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => Response.json({ eligible: true }));
  const client = createPlotApiClient({ fetch: fetcher, workspaceId: "workspace-1" });
  const controller = new AbortController();
  await client.listChatTurns("session-1", { selectedVersionId: "ver-1", signal: controller.signal });
  await client.getChatResponseVersion("ver-1", { signal: controller.signal });
  await client.getRetryEligibility("ver-1", { signal: controller.signal });
  await client.retryChatResponse("ver-1", "idem-retry-1", { signal: controller.signal });
  expect(fetcher.mock.calls[0]?.[0]).toBe("/api/plot/sessions/session-1/turns?selectedVersionId=ver-1");
  expect(fetcher.mock.calls[0]?.[1]?.signal).toBe(controller.signal);
  expect(fetcher.mock.calls[1]?.[0]).toBe("/api/plot/agent-runs/versions/ver-1");
  expect(fetcher.mock.calls[1]?.[1]?.signal).toBe(controller.signal);
  expect(fetcher.mock.calls[2]?.[0]).toBe("/api/plot/agent-runs/versions/ver-1/eligibility");
  expect(fetcher.mock.calls[2]?.[1]?.signal).toBe(controller.signal);
  expect(fetcher.mock.calls[3]?.[0]).toBe("/api/plot/agent-runs/versions/ver-1/retry");
  expect(fetcher.mock.calls[3]?.[1]?.method).toBe("POST");
  expect(new Headers(fetcher.mock.calls[3]?.[1]?.headers).get("Idempotency-Key")).toBe("idem-retry-1");
  for (const [, init] of fetcher.mock.calls) {
    expect(new Headers(init?.headers).get("X-Plot-Workspace-Id")).toBe("workspace-1");
    expect(init?.cache).toBe("no-store");
  }
});
