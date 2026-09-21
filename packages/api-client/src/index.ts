export type SourceProvider = "GITHUB" | "USER_CONFIRMED";
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
	originalUrl: string | null;
	status?: "ACTIVE" | "STALE" | "REMOVED" | string;
}

export interface ContentSource {
  evidenceId: string;
  provider: SourceProvider;
  sourceLabel: string;
  originalUrl: string | null;
  statementIds: string[];
}

export interface ContentStatementInput {
	id: string | null;
	orderIndex: number;
	body: string;
	lineage?: string[];
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

export interface ArtifactPublication {
  entryId: string;
  entrySlug: string;
  publicPath: string;
  publishedAt: string;
}

export type ContentType = "ARTIFACT" | "CHANGELOG" | "LAUNCH_ANNOUNCEMENT";

export interface RelatedArtifactSummary {
  id: string;
  title: string | null;
  contentType: ContentType;
  status: string;
  updatedAt: string;
}

export interface ReplicateArtifactInput {
  contentType: ContentType;
  instruction?: string;
  contentProfileRevisionId?: string;
  brief?: ContentBriefInput;
}

export interface Artifact {
  id: string;
  status: string;
  title: string | null;
  contentType: ContentType;
  publication?: ArtifactPublication | null;
  relatedArtifacts?: RelatedArtifactSummary[];
	variant: {
    id: string;
    status: string;
    revisionId: string;
    revisionNumber: number;
    lexicalContent: Record<string, unknown>;
    sentences: ContentSentence[];
		sources: ContentSource[];
		documentVersion?: 1 | 2;
		destinations?: CtaDestinationInput[];
	};
}

export interface ArtifactSummary {
  id: string;
  status: string;
  title: string | null;
  contentType: ContentType;
  updatedAt: string;
  published?: boolean;
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

export interface UnpublishContentVariantResult {
  entryId: string;
  entrySlug: string;
  publicPath: string;
  publishedAt: string;
  unpublishedAt: string;
}

export type ProductDeliveryEventKind =
  | "CLIPBOARD_WRITE_SUCCEEDED"
  | "CLIPBOARD_WRITE_FAILED"
  | "DOWNLOAD_STARTED"
  | "EXTERNAL_DELIVERY_CONFIRMED";

export interface RecordProductDeliveryEventInput {
  kind: ProductDeliveryEventKind;
  exportId?: string;
  entryId?: string;
  clientEventId?: string;
}

export interface ProductDeliveryEventResult {
  id: string;
  kind: ProductDeliveryEventKind;
  duplicate: boolean;
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
	documentVersion?: 1 | 2;
}

export interface RequestOptions { signal?: AbortSignal; idempotencyKey?: string }

export interface GitHubInstallationRequest {
  installUrl: string;
  expiresAt: string;
}

export interface GitHubProductOAuthStart {
  authorizationUrl: string;
  expiresAt: string;
}

export interface GitHubInstallationCallback {
  connectionId: string;
  installationId: number;
  repositories: GitHubRepository[];
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
  | "DEFERRED"
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

export interface GitHubReleaseRangeInput {
  baseSha: string;
  headSha: string;
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

export interface WorkspaceCapabilities {
  generate: boolean;
  edit: boolean;
  publish: boolean;
  export: boolean;
  configure: boolean;
  unpublish: boolean;
}

export interface WorkspaceSummary {
  id: string;
  name: string;
  slug: string;
  status: string;
  logoUrl: string | null;
  organizationId: string | null;
  publicCitationsEnabled: boolean;
  plan: string;
  entitlementStatus: string;
  accessMode: "full" | "complete_only" | "read_only";
  capabilities: WorkspaceCapabilities;
  trialEndsAt: string;
  role: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreditUsageEvent {
  id: string;
  timestamp: string;
  credits: number;
  provider: string | null;
  model: string | null;
}

export interface WorkspaceCreditOverview {
  balance: number;
  creditedUnits: number;
  consumedUnits: number;
  usageEvents: CreditUsageEvent[];
  checkoutAvailable?: boolean;
}

export interface WorkspaceCheckout {
  checkoutId: string;
  url: string;
}

export type RoutineCadence =
  | "DAILY"
  | "WEEKLY"
  | "ON_GITHUB_CHANGE"
  | "ON_GITHUB_RELEASE"
  | "ON_GIT_TAG";
export type RoutineRunStatus = string | null;

export type RoutineExecutionStatus = "PROBING" | "NO_ACTIVITY" | "DISPATCHED" | "DEFERRED" | "FAILED";
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
  releaseRequestId: string | null;
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
  contentType: ContentType;
  status: RoutineAgentRunStatus;
  failureCode: string | null;
  artifactId: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  steps: RoutineAgentStep[];
}

export interface ChatAgentRun {
  skills?: SkillSnapshot[];
  id: string;
  chatId: string;
  instruction: string;
  status: RoutineAgentRunStatus;
  failureCode: string | null;
  responseText: string | null;
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

export interface RetryEligibility {
  eligible: boolean;
  reason?: string | null;
}

export interface ChatCitation {
  id: string;
  title: string | null;
  excerpt: string;
  url: string | null;
}

export interface ChatResponseSource {
  id: string;
  displayName: string;
  role: string;
}

export interface ChatResponseVersion {
  id: string;
  turnId: string;
  versionIndex: number;
  agentRunId: string;
  status: RoutineAgentRunStatus;
  failureCode: string | null;
  instruction: string;
  responseText: string | null;
  artifactId: string | null;
  artifact: {
    id: string;
    status: string;
    title: string | null;
    updatedAt: string;
  } | null;
  retryEligibility: RetryEligibility;
  lineageParentVersionId: string | null;
  sources?: ChatResponseSource[];
  citations?: ChatCitation[];
  createdAt: string;
  updatedAt: string;
}

export interface ChatTurn {
  id: string;
  workSessionId: string;
  turnIndex: number;
  userMessage: string;
  versions: ChatResponseVersion[];
  selectedVersionId: string;
  createdAt: string;
  updatedAt: string;
}

export interface ConfirmedFactInput {
  body: string;
  kind?: string;
}

export interface ContentBrief {
  purpose?: string | null;
  audience?: string | null;
  availability?: string | null;
  pricing?: string | null;
  userAction?: string | null;
	confirmedFacts?: ConfirmedFactInput[];
	destinations?: CtaDestinationInput[];
}

export type ContentBriefInput = ContentBrief;

export interface CtaDestinationInput {
	id: string;
	label: string;
	url: string;
}

export interface Skill {
  id: string;
  name: string;
  description: string;
  revision: number;
  isSystem: boolean;
}

export interface SkillSnapshot extends Omit<Skill, "isSystem"> { content: string }
export type SkillInput = Pick<SkillSnapshot, "name" | "description" | "content">;

export interface CreateChatAgentRunInput {
  skillIds?: string[];
  instruction: string;
  model?: ChatModel;
  reasoningEffort?: ChatReasoningEffort;
  workSessionId?: string;
  writingBlockIds?: string[];
}

export type ChatReasoningEffort = "none" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max";

export interface ChatModelCapability {
  model: ChatModel;
  reasoningEfforts: ChatReasoningEffort[];
  reasoningDefault: ChatReasoningEffort | null;
}

export type ChatModel =
  | "auto"
  | "anthropic/claude-opus-5"
  | "anthropic/claude-opus-4.8"
  | "anthropic/claude-sonnet-5"
  | "anthropic/claude-sonnet-4.6"
  | "anthropic/claude-haiku-4.5"
  | "openai/gpt-5.4"
  | "openai/gpt-5.5"
  | "openai/gpt-5.6-sol"
  | "openai/gpt-5.6-luna"
  | "google/gemini-3.8-flash"
  | "deepseek/deepseek-v4.1-flash"
  | "x-ai/grok-4.6"
  | "qwen/qwen3.8-max-0902";

export interface ContentProfile {
  revisionId: string | null;
  revisionNumber: number | null;
  productSummary: string;
  primaryAudience: string;
  customerTerms: string;
  tone: string;
  defaultLocale: string;
  bannedPhrases: string[];
  updatedAt: string | null;
}

export interface UpdateContentProfileInput {
  productSummary?: string;
  primaryAudience?: string;
  customerTerms?: string;
  tone?: string;
  defaultLocale?: string;
  bannedPhrases?: string[];
}

export interface Routine {
	skills?: SkillSnapshot[];
	id: string;
	name: string;
	sourceScopeId: string;
	sourceLabel: string;
	instruction: string;
	model: ChatModel;
	reasoningEffort: ChatReasoningEffort | null;
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

export type ActivityStatus =
  | "IN_PROGRESS"
  | "READY_FOR_REVIEW"
  | "ACTION_REQUIRED"
  | "NO_UPDATE_NEEDED"
  | "EXCLUDED";

export interface ActivityItem {
  id: string;
  sourceScopeId: string;
  signalId: string | null;
  responseVersionId: string | null;
  agentRunId: string | null;
  chatId: string | null;
  artifactId: string | null;
  title: string;
  status: ActivityStatus;
  reason: string;
  semanticTime: string;
  updatedAt: string;
}

export interface ActivityPage {
  items: ActivityItem[];
  nextCursor: string | null;
  hasMore: boolean;
  highWaterMark: string;
}

export interface PlotApiClient {
  getActivity(query?: { cursor?: string; limit?: number; highWaterMark?: string }, options?: RequestOptions): Promise<ActivityPage>;

  createGitHubInstallationRequest(options?: RequestOptions): Promise<GitHubInstallationRequest>;
  startGitHubProductOAuth(returnTo?: "/settings/integrations" | "/chat", options?: RequestOptions): Promise<GitHubProductOAuthStart>;
  syncGitHubInstallation(options?: RequestOptions): Promise<GitHubInstallationCallback>;
  listGitHubConnections(options?: RequestOptions): Promise<GitHubConnection[]>;
  listGitHubRepositories(connectionId: string, options?: RequestOptions): Promise<GitHubRepository[]>;
  connectGitHubRepository(connectionId: string, externalRepositoryId: number, options?: RequestOptions): Promise<GitHubRepository>;
  disconnectGitHubRepository(sourceScopeId: string, options?: RequestOptions): Promise<void>;
  getGitHubRepositoryMonitoring(sourceScopeId: string, options?: RequestOptions): Promise<GitHubRepositoryMonitoring>;
  retryGitHubRepositoryMonitoring(sourceScopeId: string, options?: RequestOptions): Promise<GitHubRepositoryMonitoring>;
  recheckGitHubRepositoryAccess(sourceScopeId: string, trigger: GitHubAccessCheckTrigger, options?: RequestOptions): Promise<GitHubAccessCheck>;
  importGitHubRepository(sourceScopeId: string, input: { from: string; to: string }, options?: RequestOptions): Promise<GitHubImport>;
  getGitHubReleaseActivity(sourceScopeId: string, options?: RequestOptions): Promise<GitHubReleaseActivity | null>;
  getGitHubReleaseActivityById(sourceScopeId: string, requestId: string, options?: RequestOptions): Promise<GitHubReleaseActivity>;
  retryGitHubReleaseDraft(sourceScopeId: string, requestId: string, options?: RequestOptions): Promise<GitHubReleaseActivity>;
  selectGitHubReleaseRange(sourceScopeId: string, requestId: string, range: GitHubReleaseRangeInput, options?: RequestOptions): Promise<GitHubReleaseActivity>;
  createWorkspace(input: { name: string }, options?: RequestOptions): Promise<WorkspaceSummary>;
  getWorkspace(id: string, options?: RequestOptions): Promise<WorkspaceSummary>;
  updateWorkspace(id: string, input: { name?: string; logoUrl?: string; publicCitationsEnabled?: boolean }, options?: RequestOptions): Promise<WorkspaceSummary>;
  getCreditOverview(options?: RequestOptions): Promise<WorkspaceCreditOverview>;
  createCreditCheckout(options?: RequestOptions): Promise<WorkspaceCheckout>;
  getContentProfile(options?: RequestOptions): Promise<ContentProfile>;
  updateContentProfile(input: UpdateContentProfileInput, options?: RequestOptions): Promise<ContentProfile>;
  listSkills(options?: RequestOptions): Promise<Skill[]>;
  getSkill(id: string, options?: RequestOptions): Promise<SkillSnapshot>;
  createSkill(input: SkillInput, options?: RequestOptions): Promise<SkillSnapshot>;
  updateSkill(id: string, input: SkillInput, options?: RequestOptions): Promise<SkillSnapshot>;
  deleteSkill(id: string, options?: RequestOptions): Promise<void>;
  listRoutines(options?: RequestOptions): Promise<Routine[]>;
  getRoutine(id: string, options?: RequestOptions): Promise<Routine>;
	createRoutine(input: { skillIds?: string[]; name: string; sourceScopeId: string; contextSourceScopeIds?: string[]; instruction: string; cadence: RoutineCadence; model?: ChatModel; reasoningEffort?: ChatReasoningEffort | null }, options?: RequestOptions): Promise<Routine>;
  updateRoutine(id: string, input: { enabled: boolean }, options?: RequestOptions): Promise<Routine>;
  runRoutineNow(id: string, idempotencyKey: string, options?: RequestOptions): Promise<Routine>;
  getRoutineAgentRun(routineId: string, agentRunId: string, options?: RequestOptions): Promise<RoutineAgentRunDetail>;
  listChatModelCapabilities(options?: RequestOptions): Promise<ChatModelCapability[]>;
  createChatAgentRun(input: CreateChatAgentRunInput, idempotencyKey: string, options?: RequestOptions): Promise<ChatAgentRun>;
  getChatAgentRun(id: string, options?: RequestOptions): Promise<ChatAgentRun>;
  listSessionAgentRuns(id: string, options?: RequestOptions): Promise<ChatAgentRun[]>;
  listChatTurns(sessionId: string, options?: { selectedVersionId?: string } & RequestOptions): Promise<ChatTurn[]>;
  getChatResponseVersion(versionId: string, options?: RequestOptions): Promise<ChatResponseVersion>;
  getRetryEligibility(versionId: string, options?: RequestOptions): Promise<RetryEligibility>;
  retryChatResponse(versionId: string, idempotencyKey: string, options?: RequestOptions): Promise<ChatResponseVersion>;
  listSessions(options?: RequestOptions): Promise<WorkSessionSummary[]>;
  listSourceReferences(options?: RequestOptions): Promise<SourceReference[]>;
  getArtifact(id: string, options?: RequestOptions): Promise<Artifact>;
  getArtifactVariant(id: string, options?: RequestOptions): Promise<Artifact>;
  replicateArtifact(id: string, input: ReplicateArtifactInput, idempotencyKey: string, options?: RequestOptions): Promise<ChatAgentRun>;
  listArtifacts(page?: number, size?: number, options?: RequestOptions): Promise<ArtifactPage>;
  saveArtifactVariant(variantId: string, input: { expectedRevisionNumber: number; lexicalContent: Record<string, unknown>; statements: ContentStatementInput[] }, options?: RequestOptions): Promise<Artifact>;
  editSentence(variantId: string, sentenceId: string, input: { expectedRevisionNumber: number; body: string }, options?: RequestOptions): Promise<Artifact>;
  exportArtifactVariant(variantId: string, input: { expectedRevisionNumber: number; includeSources: boolean; acknowledgeUnresolved: boolean; acknowledgedWarningKeys?: string[]; acknowledgedRevisionIds?: string[]; disposition: "COPY" | "DOWNLOAD" }, options?: RequestOptions): Promise<ContentExport>;
  publishArtifactVariant(variantId: string, input: { expectedRevisionNumber: number; acknowledgeUnresolved: boolean; acknowledgedWarningKeys?: string[]; acknowledgedRevisionIds?: string[] }, options?: RequestOptions): Promise<PublishContentVariantResult>;
  unpublishArtifactVariant(variantId: string, options?: RequestOptions): Promise<UnpublishContentVariantResult>;
  recordProductDeliveryEvent(variantId: string, input: RecordProductDeliveryEventInput, options?: RequestOptions): Promise<ProductDeliveryEventResult>;
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
    getActivity: (query, requestOptions) => {
      const params = new URLSearchParams();
      if (query?.cursor) params.set("cursor", query.cursor);
      if (query?.limit) params.set("limit", String(query.limit));
      if (query?.highWaterMark) params.set("highWaterMark", query.highWaterMark);
      const queryString = params.toString();
      return request(`/autonomy/activity${queryString ? `?${queryString}` : ""}`, { signal: requestOptions?.signal });
    },
    createGitHubInstallationRequest: (requestOptions) => request("/github/installations/requests", {
      method: "POST",
      signal: requestOptions?.signal,
    }),
    startGitHubProductOAuth: (returnTo, requestOptions) => request(
      `/github/oauth/start${returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : ""}`,
      { method: "POST", signal: requestOptions?.signal },
    ),
    syncGitHubInstallation: (requestOptions) => request("/github/installations/sync", {
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
    getGitHubReleaseActivityById: (sourceScopeId, requestId, requestOptions) => request(
      `/github/repositories/${encodeURIComponent(sourceScopeId)}/release-activity/${encodeURIComponent(requestId)}`,
      { signal: requestOptions?.signal },
    ),
    retryGitHubReleaseDraft: (sourceScopeId, requestId, requestOptions) => request(
      `/github/repositories/${encodeURIComponent(sourceScopeId)}/release-activity/${encodeURIComponent(requestId)}/retry`,
      { method: "POST", signal: requestOptions?.signal },
    ),
    selectGitHubReleaseRange: (sourceScopeId, requestId, range, requestOptions) => request(
      `/github/repositories/${encodeURIComponent(sourceScopeId)}/release-activity/${encodeURIComponent(requestId)}/range`,
      {
        method: "POST",
        body: JSON.stringify({
          baseSha: range.baseSha.trim().toLowerCase(),
          headSha: range.headSha.trim().toLowerCase(),
        }),
        signal: requestOptions?.signal,
      },
    ),
    createWorkspace: (input, requestOptions) => request("/workspaces", {
      method: "POST",
      body: JSON.stringify(input),
      signal: requestOptions?.signal,
      headers: requestOptions?.idempotencyKey ? { "Idempotency-Key": requestOptions.idempotencyKey } : undefined,
    }),
    getWorkspace: (id, requestOptions) => request(`/workspaces/${encodeURIComponent(id)}`, { signal: requestOptions?.signal }),
    updateWorkspace: (id, input, requestOptions) => request(`/workspaces/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify(input),
      signal: requestOptions?.signal,
    }),
    getCreditOverview: (requestOptions) => request("/billing/credits", { signal: requestOptions?.signal }),
    createCreditCheckout: (requestOptions) => request("/billing/checkout", {
      method: "POST",
      signal: requestOptions?.signal,
    }),
    getContentProfile: (requestOptions) => request("/content-profile", { signal: requestOptions?.signal }),
    updateContentProfile: (input, requestOptions) => request("/content-profile", {
      method: "PUT",
      body: JSON.stringify(input),
      signal: requestOptions?.signal,
    }),
    listSkills: (options) => request("/skills", { signal: options?.signal }),
    getSkill: (id, options) => request(`/skills/${encodeURIComponent(id)}`, { signal: options?.signal }),
    createSkill: (input, options) => request("/skills", { method: "POST", body: JSON.stringify(input), signal: options?.signal }),
    updateSkill: (id, input, options) => request(`/skills/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify(input), signal: options?.signal }),
    deleteSkill: (id, options) => request(`/skills/${encodeURIComponent(id)}`, { method: "DELETE", signal: options?.signal }),
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
    listChatModelCapabilities: (requestOptions) => request("/agent-runs/models", {
      signal: requestOptions?.signal,
    }),
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
    listChatTurns: (sessionId, queryOptions) => {
      const params = new URLSearchParams();
      if (queryOptions?.selectedVersionId) params.set("selectedVersionId", queryOptions.selectedVersionId);
      const queryString = params.toString();
      return request(`/sessions/${encodeURIComponent(sessionId)}/turns${queryString ? `?${queryString}` : ""}`, { signal: queryOptions?.signal });
    },
    getChatResponseVersion: (versionId, requestOptions) => request(`/agent-runs/versions/${encodeURIComponent(versionId)}`, { signal: requestOptions?.signal }),
    getRetryEligibility: (versionId, requestOptions) => request(`/agent-runs/versions/${encodeURIComponent(versionId)}/eligibility`, { signal: requestOptions?.signal }),
    retryChatResponse: (versionId, idempotencyKey, requestOptions) => request(
      `/agent-runs/versions/${encodeURIComponent(versionId)}/retry`,
      {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        signal: requestOptions?.signal,
      },
    ),
    listSessions: (requestOptions) => request("/sessions", { signal: requestOptions?.signal }),
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
    replicateArtifact: (id, input, idempotencyKey, requestOptions) => request(
      `/artifacts/${encodeURIComponent(id)}/replicate`,
      {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify(input),
        signal: requestOptions?.signal,
      },
    ),
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
    unpublishArtifactVariant: (variantId, requestOptions) => request(
      `/artifact-variants/${encodeURIComponent(variantId)}/unpublish`,
      { method: "POST", signal: requestOptions?.signal },
    ),
    recordProductDeliveryEvent: (variantId, input, requestOptions) => request(
      `/artifact-variants/${encodeURIComponent(variantId)}/delivery-events`,
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
