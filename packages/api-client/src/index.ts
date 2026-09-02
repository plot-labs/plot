export type SourceProvider = "GITHUB";
export type SentenceOrigin = "GENERATED" | "REWRITTEN" | "USER_MODIFIED";

export interface SourceReference {
  id: string;
  sourceScopeId: string;
  provider: SourceProvider;
  sourceKind: string;
  sourceLabel: string;
  repositoryLabel: string;
  title: string | null;
  body: string | null;
  originalUrl: string | null;
  sourceCreatedAt: string | null;
}

export interface ContentCitation {
  evidenceId: string;
  provider: SourceProvider;
  sourceLabel: string;
  originalUrl: string;
}

export interface ContentSource {
  evidenceId: string;
  provider: SourceProvider;
  sourceLabel: string;
  originalUrl: string;
  statementIds: string[];
}

export interface ContentStatementInput {
  id: string | null;
  orderIndex: number;
  body: string;
}

export interface ContentSentence {
  id: string;
  revisionId: string;
  revisionNumber: number;
  orderIndex: number;
  body: string;
  origin: SentenceOrigin;
  citations: ContentCitation[];
}

export interface Artifact {
  id: string;
  status: string;
  title: string | null;
  variant: {
    id: string;
    status: string;
    revisionId: string;
    revisionNumber: number;
    lexicalContent: Record<string, unknown>;
    sentences: ContentSentence[];
    sources: ContentSource[];
  };
}

export interface ArtifactSummary {
  id: string;
  status: string;
  title: string | null;
  updatedAt: string;
}
export interface ArtifactPage { items: ArtifactSummary[]; page: number; size: number; totalItems: number; totalPages: number }

export interface ExportWarning {
  key: string;
  sentenceNumber: number;
  excerpt: string;
}

export interface ContentExport {
  exportId: string;
  artifactRevisionId: string;
  artifactRevisionNumber: number;
  disposition: "COPY" | "DOWNLOAD";
  filename: string;
  mediaType: string;
  text: string;
  unresolvedCount: number;
  warningAcknowledged: boolean;
  includeSources: boolean;
}

export interface PublishContentVariantResult {
  entryId: string;
  entrySlug: string;
  publicPath: string;
  publishedAt: string;
}

export interface PublicChangelogEntrySummary {
  id: string;
  entrySlug: string;
  title: string;
  tagName: string | null;
  publishedAt: string;
}

export interface PublicChangelog {
  workspaceSlug: string;
  workspaceName: string;
  logoUrl: string | null;
  entries: PublicChangelogEntrySummary[];
}

export interface PublicChangelogCitation {
  provider: string;
  sourceLabel: string;
  originalUrl: string;
}

export interface PublicChangelogSentence {
  orderIndex: number;
  body: string;
  citations: PublicChangelogCitation[];
}

export interface PublicChangelogEntry extends PublicChangelogEntrySummary {
  bodyMarkdown: string;
  workspaceSlug: string;
  workspaceName: string;
  logoUrl: string | null;
  sentences: PublicChangelogSentence[];
}

export interface RequestOptions { signal?: AbortSignal }

export interface GitHubInstallationRequest {
  installUrl: string;
  expiresAt: string;
}

export type GitHubRepositoryMonitoringStatus = "ACTIVE" | "DISABLED";
export type GitHubRepositoryAnalysisStatus = "QUEUED" | "ANALYZING" | "COMPLETED" | "FAILED";
export type GitHubReleaseConvention = "SEMVER_V" | "SEMVER" | "PREFIXED" | "MIXED" | "NO_TAGS";
export type GitHubReleaseSampleSource = "RELEASES" | "TAGS";
export type GitHubConnectionStatusReason =
  | "AUTH_EXPIRED"
  | "INSTALLATION_SUSPENDED"
  | "INSTALLATION_UNINSTALLED"
  | "PROVIDER_VERIFICATION_FAILED";
export type GitHubRepositoryStatusReason =
  | "GRANT_REMOVED"
  | "REPOSITORY_TRANSFERRED"
  | "REPOSITORY_DELETED"
  | "USER_DISCONNECTED"
  | "PROVIDER_VERIFICATION_FAILED";
export type GitHubAccessCheckStatus = "QUEUED" | "CHECKING" | "VERIFIED" | "FAILED";
export type GitHubAccessCheckTrigger = "RETRY" | "CHECK_AGAIN";

export interface GitHubRepositoryMonitoring {
  status: GitHubRepositoryMonitoringStatus;
  analysisStatus: GitHubRepositoryAnalysisStatus;
  releaseConvention: GitHubReleaseConvention | null;
  tagPrefix: string | null;
  sampleSource: GitHubReleaseSampleSource | null;
  sampleSize: number;
  sampleTruncated: boolean;
  attemptCount: number;
  lastErrorCode: string | null;
  analyzedAt: string | null;
}

export interface GitHubRepository {
  id: string | null;
  externalRepositoryId: number;
  owner: string;
  name: string;
  displayName: string;
  url: string;
  visibility?: "PUBLIC" | "PRIVATE" | string;
  status: string | null;
  monitoring: GitHubRepositoryMonitoring | null;
  statusReason?: GitHubRepositoryStatusReason | string | null;
  accessCheckStatus?: GitHubAccessCheckStatus | null;
}

export interface GitHubConnection {
  id: string;
  installationId: number;
  status: string;
  repositories: GitHubRepository[];
  statusReason?: GitHubConnectionStatusReason | string | null;
}

export interface GitHubAccessCheck {
  sourceScopeId: string;
  status: GitHubAccessCheckStatus;
  attemptCount: number;
  errorCode: string | null;
  nextAttemptAt: string | null;
  verifiedAt: string | null;
}

export interface GitHubImport {
  id: string;
  sourceScopeId: string;
  from: string;
  to: string;
  status: string;
  eligibleCount: number;
  blockCreatedCount: number;
  blockUpdatedCount: number;
  blockUnchangedCount: number;
  errorCode: string | null;
  errorMessage: string | null;
  startedAt: string;
  completedAt: string | null;
}

export type GitHubReleaseDraftStatus =
  | "QUEUED"
  | "RESOLVING"
  | "GENERATING"
  | "READY"
  | "NO_ACTIVITY"
  | "NEEDS_RANGE"
  | "FAILED";

export interface GitHubReleaseActivity {
  id: string;
  sourceScopeId: string;
  tagName: string;
  status: GitHubReleaseDraftStatus;
  baseSha: string | null;
  headSha: string | null;
  artifactId: string | null;
  errorCode: string | null;
  createdAt: string;
  updatedAt: string;
}

interface WritingBlock {
  id: string;
  sourceKind: string;
  title: string | null;
  body: string | null;
  url: string | null;
  canonicalUrl: string | null;
  sourceCreatedAt: string | null;
  status: string;
}

interface WritingBlockPage {
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  items: WritingBlock[];
}

export interface WorkspaceSummary {
  id: string;
  name: string;
  slug: string;
  status: string;
  logoUrl: string | null;
  publicCitationsEnabled: boolean;
  plan: string;
  entitlementStatus: string;
  accessMode: "full" | "read_only";
  trialEndsAt: string;
  role: string | null;
  createdAt: string;
  updatedAt: string;
}

export type RoutineCadence =
  | "DAILY"
  | "WEEKLY"
  | "ON_GITHUB_CHANGE"
  | "ON_GITHUB_RELEASE"
  | "ON_GIT_TAG";
export type RoutineRunStatus = string | null;

export type RoutineExecutionStatus = "PROBING" | "NO_ACTIVITY" | "DISPATCHED" | "FAILED";
export type RoutineAgentRunStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";

export interface RoutineExecutionSummary {
  id: string;
  status: RoutineExecutionStatus;
  chatId: string | null;
  agentRunId: string | null;
  agentRunStatus: RoutineAgentRunStatus | null;
  artifactId: string | null;
  errorCode: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface RoutineAgentStep {
  sequence: number;
  kind: "READ_TOOL" | "ARTIFACT_HANDOFF";
  status: "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED";
  toolName: string | null;
  failureCode: string | null;
  artifactId: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface RoutineAgentRunDetail {
  id: string;
  routineExecutionId: string;
  routineId: string;
  chatId: string | null;
  status: RoutineAgentRunStatus;
  failureCode: string | null;
  artifactId: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  steps: RoutineAgentStep[];
}

export interface ChatAgentRun {
  id: string;
  chatId: string;
  instruction: string;
  status: RoutineAgentRunStatus;
  failureCode: string | null;
  artifactId: string | null;
  artifact: {
    id: string;
    status: string;
    title: string | null;
    updatedAt: string;
  } | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateChatAgentRunInput {
  instruction: string;
  workSessionId?: string;
  writingBlockIds?: string[];
}

export interface Routine {
  id: string;
  name: string;
  sourceScopeId: string;
  sourceLabel: string;
  instruction: string;
  cadence: RoutineCadence;
  enabled: boolean;
  lastRunAt: string | null;
  nextRunAt: string;
  lastRunStatus: RoutineRunStatus;
  lastErrorCode: string | null;
  contextSourceScopeIds: string[];
  latestExecution: RoutineExecutionSummary | null;
  createdAt: string;
  updatedAt: string;
}

export interface WorkSessionSummary {
  id: string;
  title: string | null;
  status: string;
  lastActivityAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ArtifactHistoryItem {
  position: number;
  createdAt: string;
  cause: string;
}

export interface ArtifactHistoryDetail {
  createdAt: string;
  cause: string;
  readOnly: true;
  artifact: Artifact;
}

export class PlotApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly details: Record<string, unknown> | null = null,
    public readonly resourceId: string | null = null,
  ) {
    super(message);
    this.name = "PlotApiError";
  }
}

export interface PlotApiClient {

  createGitHubInstallationRequest(options?: RequestOptions): Promise<GitHubInstallationRequest>;
  listGitHubConnections(options?: RequestOptions): Promise<GitHubConnection[]>;
  listGitHubRepositories(connectionId: string, options?: RequestOptions): Promise<GitHubRepository[]>;
  connectGitHubRepository(connectionId: string, externalRepositoryId: number, options?: RequestOptions): Promise<GitHubRepository>;
  disconnectGitHubRepository(sourceScopeId: string, options?: RequestOptions): Promise<void>;
  getGitHubRepositoryMonitoring(sourceScopeId: string, options?: RequestOptions): Promise<GitHubRepositoryMonitoring>;
  retryGitHubRepositoryMonitoring(sourceScopeId: string, options?: RequestOptions): Promise<GitHubRepositoryMonitoring>;
  recheckGitHubRepositoryAccess(sourceScopeId: string, trigger: GitHubAccessCheckTrigger, options?: RequestOptions): Promise<GitHubAccessCheck>;
  importGitHubRepository(sourceScopeId: string, input: { from: string; to: string }, options?: RequestOptions): Promise<GitHubImport>;
  getGitHubReleaseActivity(sourceScopeId: string, options?: RequestOptions): Promise<GitHubReleaseActivity | null>;
  retryGitHubReleaseDraft(sourceScopeId: string, requestId: string, options?: RequestOptions): Promise<GitHubReleaseActivity>;
  createWorkspace(input: { name: string }, options?: RequestOptions): Promise<WorkspaceSummary>;
  getWorkspace(id: string, options?: RequestOptions): Promise<WorkspaceSummary>;
  updateWorkspace(id: string, input: { name?: string; logoUrl?: string; publicCitationsEnabled?: boolean }, options?: RequestOptions): Promise<WorkspaceSummary>;
  listRoutines(options?: RequestOptions): Promise<Routine[]>;
  getRoutine(id: string, options?: RequestOptions): Promise<Routine>;
  createRoutine(input: { name: string; sourceScopeId: string; contextSourceScopeIds?: string[]; instruction: string; cadence: RoutineCadence }, options?: RequestOptions): Promise<Routine>;
  updateRoutine(id: string, input: { enabled: boolean }, options?: RequestOptions): Promise<Routine>;
  runRoutineNow(id: string, idempotencyKey: string, options?: RequestOptions): Promise<Routine>;
  getRoutineAgentRun(routineId: string, agentRunId: string, options?: RequestOptions): Promise<RoutineAgentRunDetail>;
  createChatAgentRun(input: CreateChatAgentRunInput, idempotencyKey: string, options?: RequestOptions): Promise<ChatAgentRun>;
  getChatAgentRun(id: string, options?: RequestOptions): Promise<ChatAgentRun>;
  listSessionAgentRuns(id: string, options?: RequestOptions): Promise<ChatAgentRun[]>;
  listSessions(options?: RequestOptions): Promise<WorkSessionSummary[]>;
  createSession(input: { title?: string | null }, options?: RequestOptions): Promise<WorkSessionSummary>;
  updateSession(id: string, input: { title?: string }, options?: RequestOptions): Promise<WorkSessionSummary>;
  listSourceReferences(options?: RequestOptions): Promise<SourceReference[]>;
  getArtifact(id: string, options?: RequestOptions): Promise<Artifact>;
  getArtifactVariant(id: string, options?: RequestOptions): Promise<Artifact>;
  listArtifacts(page?: number, size?: number, options?: RequestOptions): Promise<ArtifactPage>;
  saveArtifactVariant(variantId: string, input: { expectedRevisionNumber: number; lexicalContent: Record<string, unknown>; statements: ContentStatementInput[] }, options?: RequestOptions): Promise<Artifact>;
  editSentence(variantId: string, sentenceId: string, input: { expectedRevisionNumber: number; body: string }, options?: RequestOptions): Promise<Artifact>;
  exportArtifactVariant(variantId: string, input: { expectedRevisionNumber: number; includeSources: boolean; acknowledgeUnresolved: boolean; acknowledgedWarningKeys?: string[]; acknowledgedRevisionIds?: string[]; disposition: "COPY" | "DOWNLOAD" }, options?: RequestOptions): Promise<ContentExport>;
  publishArtifactVariant(variantId: string, input: { expectedRevisionNumber: number; acknowledgeUnresolved: boolean; acknowledgedWarningKeys?: string[]; acknowledgedRevisionIds?: string[] }, options?: RequestOptions): Promise<PublishContentVariantResult>;
  listArtifactHistory(variantId: string, options?: RequestOptions): Promise<ArtifactHistoryItem[]>;
  getArtifactHistoryAt(variantId: string, position: number, options?: RequestOptions): Promise<ArtifactHistoryDetail>;
}

export function createPlotApiClient(options: { baseUrl?: string; fetch?: typeof fetch; workspaceId?: string | (() => string | null) } = {}): PlotApiClient {
  const baseUrl = (options.baseUrl ?? "/api/plot").replace(/\/$/, "");
  const fetcher = options.fetch ?? globalThis.fetch;
  const workspaceId = () => typeof options.workspaceId === "function" ? options.workspaceId() : options.workspaceId;

  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const resolvedWorkspaceId = workspaceId();
    const response = await fetcher(`${baseUrl}${path}`, {
      ...init,
      cache: "no-store",
      headers: {
        Accept: "application/json",
        ...(resolvedWorkspaceId ? { "X-Plot-Workspace-Id": resolvedWorkspaceId } : {}),
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...init.headers,
      },
    });
    const payload = await parsePayload(response);
    if (!response.ok) {
      const error = isRecord(payload) ? payload : {};
      throw new PlotApiError(
        response.status,
        typeof error.error === "string" ? error.error : "API_ERROR",
        typeof error.message === "string" ? error.message : `Plot API request failed (${response.status})`,
        isRecord(error.details) ? error.details : null,
        typeof error.resourceId === "string" ? error.resourceId : null,
      );
    }
    return payload as T;
  }

  return {
    createGitHubInstallationRequest: (requestOptions) => request("/github/installations/requests", {
      method: "POST",
      signal: requestOptions?.signal,
    }),
    listGitHubConnections: (requestOptions) => request("/github/connections", { signal: requestOptions?.signal }),
    listGitHubRepositories: (connectionId, requestOptions) => request(
      `/github/connections/${encodeURIComponent(connectionId)}/repositories`,
      { signal: requestOptions?.signal },
    ),
    connectGitHubRepository: (connectionId, externalRepositoryId, requestOptions) => request(
      `/github/repositories/${encodeURIComponent(String(externalRepositoryId))}`,
      { method: "PUT", body: JSON.stringify({ connectionId }), signal: requestOptions?.signal },
    ),
    disconnectGitHubRepository: (sourceScopeId, requestOptions) => request(
      `/github/repositories/${encodeURIComponent(sourceScopeId)}`,
      { method: "DELETE", signal: requestOptions?.signal },
    ),
    getGitHubRepositoryMonitoring: (sourceScopeId, requestOptions) => request(
      `/github/repositories/${encodeURIComponent(sourceScopeId)}/monitoring`,
      { signal: requestOptions?.signal },
    ),
    retryGitHubRepositoryMonitoring: (sourceScopeId, requestOptions) => request(
      `/github/repositories/${encodeURIComponent(sourceScopeId)}/monitoring/retry`,
      { method: "POST", signal: requestOptions?.signal },
    ),
    recheckGitHubRepositoryAccess: (sourceScopeId, trigger, requestOptions) => request(
      `/github/repositories/${encodeURIComponent(sourceScopeId)}/access-check?trigger=${encodeURIComponent(trigger)}`,
      { method: "POST", signal: requestOptions?.signal },
    ),
    importGitHubRepository: (sourceScopeId, input, requestOptions) => request(
      `/github/repositories/${encodeURIComponent(sourceScopeId)}/imports`,
      { method: "POST", body: JSON.stringify(input), signal: requestOptions?.signal },
    ),
    getGitHubReleaseActivity: (sourceScopeId, requestOptions) => request(
      `/github/repositories/${encodeURIComponent(sourceScopeId)}/release-activity`,
      { signal: requestOptions?.signal },
    ),
    retryGitHubReleaseDraft: (sourceScopeId, requestId, requestOptions) => request(
      `/github/repositories/${encodeURIComponent(sourceScopeId)}/release-activity/${encodeURIComponent(requestId)}/retry`,
      { method: "POST", signal: requestOptions?.signal },
    ),
    createWorkspace: (input, requestOptions) => request("/workspaces", {
      method: "POST",
      body: JSON.stringify(input),
      signal: requestOptions?.signal,
    }),
    getWorkspace: (id, requestOptions) => request(`/workspaces/${encodeURIComponent(id)}`, { signal: requestOptions?.signal }),
    updateWorkspace: (id, input, requestOptions) => request(`/workspaces/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify(input),
      signal: requestOptions?.signal,
    }),
    listRoutines: (requestOptions) => request("/routines", { signal: requestOptions?.signal }),
    getRoutine: (id, requestOptions) => request(`/routines/${encodeURIComponent(id)}`, {
      signal: requestOptions?.signal,
    }),
    createRoutine: (input, requestOptions) => request("/routines", {
      method: "POST",
      body: JSON.stringify(input),
      signal: requestOptions?.signal,
    }),
    updateRoutine: (id, input, requestOptions) => request(`/routines/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify(input),
      signal: requestOptions?.signal,
    }),
    runRoutineNow: (id, idempotencyKey, requestOptions) => request(`/routines/${encodeURIComponent(id)}/run`, {
      method: "POST",
      signal: requestOptions?.signal,
      headers: { "Idempotency-Key": idempotencyKey },
    }),
    getRoutineAgentRun: (routineId, agentRunId, requestOptions) => request(
      `/routines/${encodeURIComponent(routineId)}/agent-runs/${encodeURIComponent(agentRunId)}`,
      { signal: requestOptions?.signal },
    ),
    createChatAgentRun: (input, idempotencyKey, requestOptions) => request("/agent-runs", {
      method: "POST",
      body: JSON.stringify(input),
      signal: requestOptions?.signal,
      headers: { "Idempotency-Key": idempotencyKey },
    }),
    getChatAgentRun: (id, requestOptions) => request(`/agent-runs/${encodeURIComponent(id)}`, {
      signal: requestOptions?.signal,
    }),
    listSessionAgentRuns: (id, requestOptions) => request(`/sessions/${encodeURIComponent(id)}/agent-runs`, {
      signal: requestOptions?.signal,
    }),
    listSessions: (requestOptions) => request("/sessions", { signal: requestOptions?.signal }),
    createSession: (input, requestOptions) => request("/sessions", {
      method: "POST",
      body: JSON.stringify(input),
      signal: requestOptions?.signal,
    }),
    updateSession: (id, input, requestOptions) => request(`/sessions/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify(input),
      signal: requestOptions?.signal,
    }),
    listSourceReferences: async (requestOptions) => {
      const connections = await request<GitHubConnection[]>("/github/connections", { signal: requestOptions?.signal });
      const scopes = connections
        .filter((connection) => connection.status === "ACTIVE")
        .flatMap((connection) => connection.repositories)
        .filter((repository): repository is GitHubRepository & { id: string } => Boolean(repository.id) && repository.status === "ACTIVE");
      const pages = await Promise.all(scopes.map(async (scope) => {
        const first = await request<WritingBlockPage>(`/blocks?sourceScopeId=${encodeURIComponent(scope.id)}&page=0&size=100`, { signal: requestOptions?.signal });
        const rest = await Promise.all(Array.from({ length: Math.max(0, first.totalPages - 1) }, (_, index) =>
          request<WritingBlockPage>(`/blocks?sourceScopeId=${encodeURIComponent(scope.id)}&page=${index + 1}&size=100`, { signal: requestOptions?.signal })));
        return { scope, items: [first, ...rest].flatMap((page) => page.items) };
      }));
      return pages.flatMap(({ scope, items }) => items
        .filter((block) => block.status === "ACTIVE")
        .map((block): SourceReference => ({
          id: block.id,
          sourceScopeId: scope.id,
          provider: "GITHUB",
          sourceKind: block.sourceKind,
          sourceLabel: block.title?.trim() || scope.displayName,
          repositoryLabel: scope.displayName,
          title: block.title,
          body: block.body,
          originalUrl: block.canonicalUrl ?? block.url,
          sourceCreatedAt: block.sourceCreatedAt,
        })));
    },
    getArtifact: (id, requestOptions) => request(`/artifacts/${encodeURIComponent(id)}`, { signal: requestOptions?.signal }),
    getArtifactVariant: (id, requestOptions) => request(`/artifact-variants/${encodeURIComponent(id)}`, { signal: requestOptions?.signal }),
    listArtifacts: (page = 0, size = 25, requestOptions) => request(`/artifacts?page=${page}&size=${size}`, { signal: requestOptions?.signal }),
    saveArtifactVariant: (variantId, input, requestOptions) => request(
      `/artifact-variants/${encodeURIComponent(variantId)}`,
      { method: "PATCH", body: JSON.stringify(input), signal: requestOptions?.signal },
    ),
    editSentence: (variantId, sentenceId, input, requestOptions) => request(
      `/artifact-variants/${encodeURIComponent(variantId)}/sentences/${encodeURIComponent(sentenceId)}`,
      { method: "PATCH", body: JSON.stringify(input), signal: requestOptions?.signal },
    ),
    exportArtifactVariant: (variantId, input, requestOptions) => request(
      `/artifact-variants/${encodeURIComponent(variantId)}/exports`,
      { method: "POST", body: JSON.stringify(input), signal: requestOptions?.signal },
    ),
    publishArtifactVariant: (variantId, input, requestOptions) => request(
      `/artifact-variants/${encodeURIComponent(variantId)}/publish`,
      { method: "POST", body: JSON.stringify(input), signal: requestOptions?.signal },
    ),
    listArtifactHistory: (variantId, requestOptions) => request(
      `/artifact-variants/${encodeURIComponent(variantId)}/history`,
      { signal: requestOptions?.signal },
    ),
    getArtifactHistoryAt: (variantId, position, requestOptions) => request(
      `/artifact-variants/${encodeURIComponent(variantId)}/history/at/${encodeURIComponent(String(position))}`,
      { signal: requestOptions?.signal },
    ),
  };
}

export async function fetchPublicChangelog(
  workspaceSlug: string,
  options: { baseUrl: string; fetch?: typeof fetch; signal?: AbortSignal } ,
): Promise<PublicChangelog> {
  const baseUrl = options.baseUrl.replace(/\/$/, "");
  const fetcher = options.fetch ?? globalThis.fetch;
  const response = await fetcher(`${baseUrl}/api/public/changelog/${encodeURIComponent(workspaceSlug)}`, {
    cache: "no-store",
    headers: { Accept: "application/json" },
    signal: options.signal,
  });
  const payload = await parsePayload(response);
  if (!response.ok) {
    const error = isRecord(payload) ? payload : {};
    throw new PlotApiError(
      response.status,
      typeof error.error === "string" ? error.error : "API_ERROR",
      typeof error.message === "string" ? error.message : `Plot API request failed (${response.status})`,
      isRecord(error.details) ? error.details : null,
      typeof error.resourceId === "string" ? error.resourceId : null,
    );
  }
  return payload as PublicChangelog;
}

export async function fetchPublicChangelogEntry(
  workspaceSlug: string,
  entrySlug: string,
  options: { baseUrl: string; fetch?: typeof fetch; signal?: AbortSignal },
): Promise<PublicChangelogEntry> {
  const baseUrl = options.baseUrl.replace(/\/$/, "");
  const fetcher = options.fetch ?? globalThis.fetch;
  const response = await fetcher(
    `${baseUrl}/api/public/changelog/${encodeURIComponent(workspaceSlug)}/${encodeURIComponent(entrySlug)}`,
    {
      cache: "no-store",
      headers: { Accept: "application/json" },
      signal: options.signal,
    },
  );
  const payload = await parsePayload(response);
  if (!response.ok) {
    const error = isRecord(payload) ? payload : {};
    throw new PlotApiError(
      response.status,
      typeof error.error === "string" ? error.error : "API_ERROR",
      typeof error.message === "string" ? error.message : `Plot API request failed (${response.status})`,
      isRecord(error.details) ? error.details : null,
      typeof error.resourceId === "string" ? error.resourceId : null,
    );
  }
  return payload as PublicChangelogEntry;
}

async function parsePayload(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    if (!response.ok) return { error: "INVALID_API_RESPONSE", message: "Plot API returned an invalid response" };
    throw new PlotApiError(response.status, "INVALID_API_RESPONSE", "Plot API returned an invalid response");
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
