export type SourceProvider = "GITHUB" | "SLACK" | "LINEAR";
export type GenerationStatus = "QUEUED" | "WRITING" | "REVIEWING" | "REWRITING" | "READY" | "NEEDS_YOUR_CALL" | "NEEDS_REVIEW" | "FAILED";
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

export interface GenerationIntervention {
  id: string;
  sentenceId: string;
  version: number;
  reason: string;
  evidenceIds: string[];
}

export interface ContentPack {
  id: string;
  generationRunId: string;
  status: string;
  title: string | null;
  variant: {
    id: string;
    status: string;
    sentences: GenerationSentence[];
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
  pendingIntervention: GenerationIntervention | null;
  contentPack: ContentPack | null;
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

export interface GenerationProgressEvent {
  runId: string;
  runStatus: GenerationStatus;
  sequence: number;
}

export interface GenerationEventOptions extends RequestOptions {
  onEvent: (event: GenerationProgressEvent) => void | Promise<void>;
}

export interface GenerationArtifact {
  kind: "WRITER_OUTPUT" | "REVIEWER_OUTPUT" | "REWRITER_OUTPUT" | "CONFLICT" | "CONFLICT_DECISION";
  sequence: number;
  sentenceIds: string[];
  reviews: Array<{ sentenceId: string; verdict: Exclude<SentenceVerdict, "USER_MODIFIED" | "REVIEW_FAILED">; evidenceIds: string[]; reason: string | null }>;
  detail: string | null;
}

export interface ContentPackSummary { id: string; generationRunId: string; status: string; title: string | null }
export interface ContentPackPage { items: ContentPackSummary[]; page: number; size: number; totalItems: number; totalPages: number }

export interface CreateGenerationInput {
  sourceScopeId: string;
  writingBlockIds: string[];
  instruction?: string;
}

export interface RequestOptions { signal?: AbortSignal }

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
  listGenerationReferences(options?: RequestOptions): Promise<GenerationReference[]>;
  createGeneration(input: CreateGenerationInput, idempotencyKey: string, options?: RequestOptions): Promise<GenerationRun>;
  getGeneration(id: string, options?: RequestOptions): Promise<GenerationRun>;
  subscribeGenerationEvents?: (id: string, options: GenerationEventOptions) => Promise<void>;
  getContentPack(id: string, options?: RequestOptions): Promise<ContentPack>;
  listContentPacks(page?: number, size?: number, options?: RequestOptions): Promise<ContentPackPage>;
  editSentence(variantId: string, sentenceId: string, input: { expectedRevisionNumber: number; body: string }, options?: RequestOptions): Promise<ContentPack>;
  exportVariant(variantId: string, input: { acknowledgeUnresolved: boolean; acknowledgedRevisionIds?: string[]; disposition: "COPY" | "DOWNLOAD" }, options?: RequestOptions): Promise<{ exportId: string; disposition: "COPY" | "DOWNLOAD"; filename: string; mediaType: string; text: string; unresolvedCount: number; warningAcknowledged: boolean }>;
}

export function createPlotApiClient(options: { baseUrl?: string; fetch?: typeof fetch } = {}): PlotApiClient {
  const baseUrl = (options.baseUrl ?? "/api/plot").replace(/\/$/, "");
  const fetcher = options.fetch ?? globalThis.fetch;

  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await fetcher(`${baseUrl}${path}`, {
      ...init,
      cache: "no-store",
      headers: {
        Accept: "application/json",
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
    listGenerationReferences: async (requestOptions) => {
      const connections = await request<GitHubConnectionResponse[]>("/github/connections", { signal: requestOptions?.signal });
      const scopes = connections
        .filter((connection) => connection.status === "ACTIVE")
        .flatMap((connection) => connection.repositories)
        .filter((repository): repository is GitHubRepositoryResponse & { id: string } => Boolean(repository.id) && repository.status === "ACTIVE");
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
    subscribeGenerationEvents: async (id, eventOptions) => {
      const response = await fetcher(`${baseUrl}/generations/${encodeURIComponent(id)}/events`, {
        cache: "no-store",
        signal: eventOptions.signal,
        headers: { Accept: "text/event-stream" },
      });
      if (!response.ok) {
        const payload = await parsePayload(response);
        const error = isRecord(payload) ? payload : {};
        throw new PlotApiError(
          response.status,
          typeof error.error === "string" ? error.error : "API_ERROR",
          typeof error.message === "string" ? error.message : `Plot API request failed (${response.status})`,
          isRecord(error.details) ? error.details : null,
          typeof error.resourceId === "string" ? error.resourceId : null,
        );
      }
      if (!response.body) throw new PlotApiError(502, "EMPTY_EVENT_STREAM", "Plot API returned an empty event stream");

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let completed = false;
      try {
        while (true) {
          const chunk = await reader.read();
          buffer += decoder.decode(chunk.value, { stream: !chunk.done });
          const blocks = buffer.split(/\r?\n\r?\n/);
          buffer = blocks.pop() ?? "";
          for (const block of blocks) await parseEventBlock(block, eventOptions.onEvent);
          if (chunk.done) {
            if (buffer.trim()) await parseEventBlock(buffer, eventOptions.onEvent);
            completed = true;
            return;
          }
        }
      } finally {
        if (!completed) await reader.cancel().catch(() => undefined);
        reader.releaseLock();
      }
    },
    getContentPack: (id, requestOptions) => request(`/content-packs/${encodeURIComponent(id)}`, { signal: requestOptions?.signal }),
    listContentPacks: (page = 0, size = 25, requestOptions) => request(`/content-packs?page=${page}&size=${size}`, { signal: requestOptions?.signal }),
    editSentence: (variantId, sentenceId, input, requestOptions) => request(
      `/content-variants/${encodeURIComponent(variantId)}/sentences/${encodeURIComponent(sentenceId)}`,
      { method: "PATCH", body: JSON.stringify(input), signal: requestOptions?.signal },
    ),
    exportVariant: (variantId, input, requestOptions) => request(
      `/content-variants/${encodeURIComponent(variantId)}/exports`,
      { method: "POST", body: JSON.stringify(input), signal: requestOptions?.signal },
    ),
  };
}

async function parseEventBlock(block: string, onEvent: (event: GenerationProgressEvent) => void | Promise<void>): Promise<void> {
  let eventName = "message";
  const data: string[] = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith(":")) continue;
    if (line.startsWith("event:")) eventName = line.slice(6).trim();
    if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  if (eventName !== "checkpoint" || data.length === 0) return;
  let parsed: unknown;
  try {
    parsed = JSON.parse(data.join("\n"));
  } catch {
    throw new PlotApiError(502, "INVALID_EVENT_STREAM", "Plot API returned invalid generation event JSON");
  }
  if (!isRecord(parsed) || typeof parsed.runId !== "string" || !isGenerationStatus(parsed.runStatus) || typeof parsed.sequence !== "number" || !Number.isSafeInteger(parsed.sequence) || parsed.sequence < 0) {
    throw new PlotApiError(502, "INVALID_EVENT_STREAM", "Plot API returned an invalid generation event");
  }
  await onEvent({ runId: parsed.runId, runStatus: parsed.runStatus, sequence: parsed.sequence });
}

function isGenerationStatus(value: unknown): value is GenerationStatus {
  return value === "QUEUED" || value === "WRITING" || value === "REVIEWING" || value === "REWRITING"
    || value === "READY" || value === "NEEDS_YOUR_CALL" || value === "NEEDS_REVIEW" || value === "FAILED";
}

interface GitHubRepositoryResponse {
  id: string | null;
  displayName: string;
  status: string | null;
}

interface GitHubConnectionResponse {
  status: string;
  repositories: GitHubRepositoryResponse[];
}

interface WritingBlockPage {
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  items: Array<{
    id: string;
    sourceKind: string;
    title: string | null;
    body: string | null;
    url: string | null;
    canonicalUrl: string | null;
    sourceCreatedAt: string | null;
    status: string;
  }>;
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
