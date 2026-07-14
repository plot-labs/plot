export type SourceProvider = "GITHUB" | "SLACK" | "LINEAR";
export type GenerationStatus = "QUEUED" | "WRITING" | "REVIEWING" | "REWRITING" | "READY" | "NEEDS_YOUR_CALL" | "NEEDS_REVIEW" | "FAILED";
export type SentenceOrigin = "GENERATED" | "REWRITTEN" | "USER_MODIFIED";
export type SentenceVerdict = "SUPPORTED" | "NOT_REQUIRED" | "NEEDS_SUPPORT" | "CONFLICT" | "USER_MODIFIED";
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
  pendingIntervention: GenerationIntervention | null;
  contentPack: ContentPack | null;
}

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
  createGeneration(input: CreateGenerationInput, idempotencyKey: string, options?: RequestOptions): Promise<GenerationRun>;
  getGeneration(id: string, options?: RequestOptions): Promise<GenerationRun>;
  getContentPack(id: string, options?: RequestOptions): Promise<ContentPack>;
  editSentence(variantId: string, sentenceId: string, input: { expectedRevisionNumber: number; body: string }, options?: RequestOptions): Promise<ContentPack>;
  resolveConflict(runId: string, interventionId: string, input: { expectedVersion: number; action: "PREFER_SOURCE" | "OMIT_CLAIM" | "PROVIDE_WORDING"; preferredEvidenceId?: string; providedWording?: string }, options?: RequestOptions): Promise<GenerationRun>;
  exportVariant(variantId: string, input: { acknowledgeUnresolved: boolean; disposition: "COPY" | "DOWNLOAD" }, options?: RequestOptions): Promise<{ exportId: string; disposition: "COPY" | "DOWNLOAD"; filename: string; mediaType: string; text: string; unresolvedCount: number; warningAcknowledged: boolean }>;
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
    createGeneration: (input, idempotencyKey, requestOptions) => request("/generations", {
      method: "POST",
      body: JSON.stringify(input),
      signal: requestOptions?.signal,
      headers: { "Idempotency-Key": idempotencyKey },
    }),
    getGeneration: (id, requestOptions) => request(`/generations/${encodeURIComponent(id)}`, { signal: requestOptions?.signal }),
    getContentPack: (id, requestOptions) => request(`/content-packs/${encodeURIComponent(id)}`, { signal: requestOptions?.signal }),
    editSentence: (variantId, sentenceId, input, requestOptions) => request(
      `/content-variants/${encodeURIComponent(variantId)}/sentences/${encodeURIComponent(sentenceId)}`,
      { method: "PATCH", body: JSON.stringify(input), signal: requestOptions?.signal },
    ),
    resolveConflict: (runId, interventionId, input, requestOptions) => request(
      `/generations/${encodeURIComponent(runId)}/interventions/${encodeURIComponent(interventionId)}/resolution`,
      { method: "POST", body: JSON.stringify(input), signal: requestOptions?.signal },
    ),
    exportVariant: (variantId, input, requestOptions) => request(
      `/content-variants/${encodeURIComponent(variantId)}/exports`,
      { method: "POST", body: JSON.stringify(input), signal: requestOptions?.signal },
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
