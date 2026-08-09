export type SourceProvider = "GITHUB";
export type GenerationStatus = "QUEUED" | "WRITING" | "REVIEWING" | "REWRITING" | "READY" | "NEEDS_REVIEW" | "FAILED";
export type SentenceOrigin = "GENERATED" | "REWRITTEN" | "USER_MODIFIED";
export type SentenceVerdict = "SUPPORTED" | "NOT_REQUIRED" | "NEEDS_SUPPORT" | "CONFLICT" | "USER_MODIFIED" | "REVIEW_FAILED";
export type CitationStatus = "ACTIVE" | "STALE" | "REMOVED";

export interface GenerationEvidence {
  id: string;
  provider: SourceProvider;
  sourceKind: string;
  sourceLabel: string;
  originalUrl: string;
  snapshotExcerpt: string | null;
  contentHash: string;
}

export interface GenerationReference {
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

export interface GenerationCitation {
  evidenceId: string;
  provider: SourceProvider;
  sourceLabel: string;
  originalUrl: string;
  snapshotExcerpt: string | null;
  status?: CitationStatus;
  sourceAccess?: "AVAILABLE" | "LOST";
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

export interface GenerationSentence {
  id: string;
  revisionId: string;
  revisionNumber: number;
  orderIndex: number;
  body: string;
  origin: SentenceOrigin;
  verdict: SentenceVerdict | null;
  reason: string | null;
  citations: GenerationCitation[];
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
  generationRunId: string;
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

export interface GenerationRun {
  id: string;
  status: GenerationStatus;
  semanticRewriteAttempt: number;
  pollAfterMs: number | null;
  failureCode: string | null;
  evidence: GenerationEvidence[];
  sentences: GenerationSentence[];
  artifacts: GenerationArtifact[];
  artifact: Artifact | null;
  workSessionId?: string | null;
  timing?: GenerationRunTiming | null;
}

export interface GenerationRunTiming {
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  steps: GenerationStepTiming[];
  model: GenerationModelTiming | null;
}

export interface GenerationStepTiming {
  kind: "WRITER" | "REVIEWER" | "REWRITER";
  sequence: number;
  status: "RUNNING" | "SUCCEEDED" | "FAILED";
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
  failureCode: string | null;
}

export interface GenerationModelTiming {
  modelName: string;
  totalTokens: number;
  totalLatencyMs: number;
}

export interface GenerationArtifact {
  kind: "WRITER_OUTPUT" | "REVIEWER_OUTPUT" | "REWRITER_OUTPUT" | "CONFLICT";
  sequence: number;
  sentenceIds: string[];
  reviews: Array<{ sentenceId: string; verdict: Exclude<SentenceVerdict, "USER_MODIFIED" | "REVIEW_FAILED">; evidenceIds: string[]; reason: string | null }>;
  detail: string | null;
}

export interface ArtifactSummary {
  id: string;
  generationRunId: string;
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

export interface CreateGenerationInput {
  sourceScopeId: string;
  writingBlockIds: string[];
  instruction?: string;
  workSessionId?: string;
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
  generationRunId: string | null;
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
  role: string | null;
}

export type RoutineCadence =
  | "DAILY"
  | "WEEKLY"
  | "ON_GITHUB_CHANGE"
  | "ON_GITHUB_RELEASE"
  | "ON_GIT_TAG";
export type RoutineRunStatus = "NO_ACTIVITY" | GenerationStatus | null;

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
  lastGenerationRunId: string | null;
  lastRunStatus: RoutineRunStatus;
  lastErrorCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface WorkSessionSummary {
  id: string;
  title: string | null;
  status: string;
  latestGenerationId: string | null;
  lastActivityAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface SessionGeneration {
  id: string;
  status: GenerationStatus;
  instruction: string | null;
  createdAt: string;
  completedAt: string | null;
  failureCode: string | null;
  artifact: ArtifactSummary | null;
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
  updateWorkspace(id: string, input: { name?: string; logoUrl?: string }, options?: RequestOptions): Promise<WorkspaceSummary>;
  listRoutines(options?: RequestOptions): Promise<Routine[]>;
  createRoutine(input: { name: string; sourceScopeId: string; instruction: string; cadence: RoutineCadence }, options?: RequestOptions): Promise<Routine>;
  updateRoutine(id: string, input: { name?: string; sourceScopeId?: string; instruction?: string; cadence?: RoutineCadence; enabled?: boolean }, options?: RequestOptions): Promise<Routine>;
  runRoutineNow(id: string, options?: RequestOptions): Promise<Routine>;
  listSessions(options?: RequestOptions): Promise<WorkSessionSummary[]>;
  createSession(input: { title?: string | null }, options?: RequestOptions): Promise<WorkSessionSummary>;
  updateSession(id: string, input: { title?: string; latestGenerationId?: string }, options?: RequestOptions): Promise<WorkSessionSummary>;
  listSessionGenerations(id: string, options?: RequestOptions): Promise<SessionGeneration[]>;
  listGenerationReferences(options?: RequestOptions): Promise<GenerationReference[]>;
  createGeneration(input: CreateGenerationInput, idempotencyKey: string, options?: RequestOptions): Promise<GenerationRun>;
  getGeneration(id: string, options?: RequestOptions): Promise<GenerationRun>;
  getArtifact(id: string, options?: RequestOptions): Promise<Artifact>;
  getArtifactVariant(id: string, options?: RequestOptions): Promise<Artifact>;
  listArtifacts(page?: number, size?: number, options?: RequestOptions): Promise<ArtifactPage>;
  saveArtifactVariant(variantId: string, input: { expectedRevisionNumber: number; lexicalContent: Record<string, unknown>; statements: ContentStatementInput[] }, options?: RequestOptions): Promise<Artifact>;
  editSentence(variantId: string, sentenceId: string, input: { expectedRevisionNumber: number; body: string }, options?: RequestOptions): Promise<Artifact>;
  exportArtifactVariant(variantId: string, input: { expectedRevisionNumber: number; includeSources: boolean; acknowledgeUnresolved: boolean; acknowledgedWarningKeys?: string[]; acknowledgedRevisionIds?: string[]; disposition: "COPY" | "DOWNLOAD" }, options?: RequestOptions): Promise<ContentExport>;
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
    runRoutineNow: (id, requestOptions) => request(`/routines/${encodeURIComponent(id)}/run`, {
      method: "POST",
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
    listSessionGenerations: (id, requestOptions) => request(`/sessions/${encodeURIComponent(id)}/generations`, {
      signal: requestOptions?.signal,
    }),
    listGenerationReferences: async (requestOptions) => {
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
        .map((block): GenerationReference => ({
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
    createGeneration: (input, idempotencyKey, requestOptions) => request("/generations", {
      method: "POST",
      body: JSON.stringify(input),
      signal: requestOptions?.signal,
      headers: { "Idempotency-Key": idempotencyKey },
    }),
    getGeneration: (id, requestOptions) => request(`/generations/${encodeURIComponent(id)}`, { signal: requestOptions?.signal }),
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
