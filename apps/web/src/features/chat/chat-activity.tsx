"use client";

import {
  ChatMessage,
  ChatMessageBubble,
  ChatMessageMetadata,
  ChatSystemMessage,
  ChatToolCalls,
} from "@astryxdesign/core/Chat";
import { Timestamp } from "@astryxdesign/core/Timestamp";
import { Text } from "@astryxdesign/core/Text";
import { ChevronLeft, ChevronRight, LoaderCircle, RotateCcw } from "lucide-react";
import type { ReactNode } from "react";

import type { ChatAgentRun, ChatResponseVersion, RetryEligibility, SourceReference } from "@plot/api-client";
import {
  agentProgressLabel,
  agentStatusLabel,
  CONNECTION_ERROR_CODES,
  formatActivity,
} from "@/features/chat/chat-workspace-utils";
import { ChatSourceCitations } from "@/features/chat/chat-source-citations";

export function ChatActivityPanel({
  activities,
  selectedActivityId,
  loading,
  error,
  onSelect,
}: {
  activities: ChatAgentRun[];
  selectedActivityId: string | null;
  loading: boolean;
  error: string;
  onSelect: (activity: ChatAgentRun) => void;
}) {
  const artifacts = activities.filter((activity) => activity.artifactId && activity.artifact);
  return (
    <section aria-label="Assistant" className="rounded-xl border border-black/10 bg-white p-4 dark:border-white/10 dark:bg-white/[0.04] sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.08em] text-black/42 dark:text-white/45">Assistant</div>
          <h2 className="mt-1 text-sm font-semibold text-black/82 dark:text-white/88">Chat activity</h2>
          <p className="mt-1 text-xs leading-5 text-black/48 dark:text-white/52">Agent requests stay here while they work, fail, or produce an artifact.</p>
        </div>
        {artifacts.length ? <span className="text-xs text-black/42 dark:text-white/45">{artifacts.length} artifact{artifacts.length === 1 ? "" : "s"}</span> : null}
      </div>
      {loading ? <p className="mt-4 text-sm text-black/45 dark:text-white/48">Loading chat activity…</p> : null}
      {error ? <ErrorNotice message={error} /> : null}
      {!loading && !activities.length && !error ? <p className="mt-4 text-sm text-black/48 dark:text-white/48">No Agent requests yet. Start with a source-backed request below.</p> : null}
      {artifacts.length ? (
        <div className="mt-4" aria-label="Artifact selector">
          <div className="mb-2 text-xs font-semibold text-black/55 dark:text-white/58">Artifacts in this chat</div>
          <div className="flex min-w-0 gap-2 overflow-x-auto pb-1" role="listbox" aria-label="Artifacts in this chat">
            {artifacts.map((activity) => (
              <button
                key={activity.artifactId}
                type="button"
                role="option"
                aria-selected={selectedActivityId === activity.id}
                onClick={() => onSelect(activity)}
                className={`min-w-40 max-w-56 shrink-0 rounded-lg border px-3 py-2 text-left text-sm transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 ${selectedActivityId === activity.id ? "border-black/25 bg-black/[0.04] dark:border-white/25 dark:bg-white/10" : "border-black/[0.08] hover:bg-black/[0.025] dark:border-white/10 dark:hover:bg-white/[0.06]"}`}
              >
                <span className="block truncate font-medium text-black/78 dark:text-white/82">{activity.artifact!.title || "Generated artifact"}</span>
                <span className="mt-1 block text-xs text-black/42 dark:text-white/45">{agentStatusLabel(activity.status)}</span>
              </button>
            ))}
          </div>
        </div>
      ) : null}
      {activities.length ? (
        <ol className="mt-4 grid gap-2 md:grid-cols-2 xl:grid-cols-3" aria-label="Agent requests in this chat">
          {activities.map((activity) => {
            const statusLabel = agentStatusLabel(activity.status);
            const showSpinner = activity.status === "RUNNING";
            return (
              <li key={activity.id}>
                <button
                  type="button"
                  aria-current={selectedActivityId === activity.id ? "true" : undefined}
                  onClick={() => onSelect(activity)}
                  className={`w-full rounded-lg border px-3 py-3 text-left transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 ${selectedActivityId === activity.id ? "border-black/25 bg-black/[0.04] dark:border-white/25 dark:bg-white/10" : "border-black/[0.08] hover:bg-black/[0.025] dark:border-white/10 dark:hover:bg-white/[0.06]"}`}
                >
                  <span className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.07em] text-black/48 dark:text-white/52">
                    {showSpinner ? <LoaderCircle aria-hidden="true" className="size-3.5 animate-spin" /> : null}
                    <span>{statusLabel}</span>
                  </span>
                  <span className="mt-1 block truncate text-sm font-medium text-black/78 dark:text-white/82">{activity.artifact?.title || activity.instruction || "Agent request"}</span>
                  <span className="mt-1 block text-xs text-black/42 dark:text-white/45">
                    {activity.artifact ? "Artifact available" : activity.status === "FAILED" ? "No artifact produced" : "Working; no artifact yet"} · {formatActivity(activity.createdAt)}
                  </span>
                </button>
              </li>
            );
          })}
        </ol>
      ) : null}
    </section>
  );
}


export function AgentActivityDetail({
  run,
  busy,
  error,
  instruction,
  references,
  artifactAction,
  versions = [],
  selectedVersionId = null,
  onSelectVersion,
  onRetry,
  retrying = false,
  retryEligibility = null,
}: {
  run: ChatAgentRun | null;
  busy: boolean;
  error: string;
  instruction: string;
  references: SourceReference[];
  artifactAction?: ReactNode;
  versions?: ChatResponseVersion[];
  selectedVersionId?: string | null;
  onSelectVersion?: (versionId: string) => void;
  onRetry?: () => void;
  retrying?: boolean;
  retryEligibility?: RetryEligibility | null;
}) {
  if (!run && !busy && !error) return null;
  const status = run?.status ?? "QUEUED";
  const linkedArtifact = Boolean(run?.artifactId);
  const isFailed = run?.status === "FAILED";
  const isNeedsConnection = Boolean(run?.failureCode && CONNECTION_ERROR_CODES.has(run.failureCode));
  const isComplete = Boolean(linkedArtifact || run?.status === "SUCCEEDED");
  const toolStatus = error || isFailed || isNeedsConnection
    ? "error"
    : isComplete
      ? "complete"
      : "running";

  const currentVersionIndex = versions.length > 0 && selectedVersionId
    ? Math.max(0, versions.findIndex((v) => v.id === selectedVersionId))
    : versions.length > 0 ? versions.length - 1 : 0;

  return (
    <section aria-label="Agent request details">
      <ChatMessage sender="assistant">
        <ChatMessageBubble
          variant="ghost"
          className="w-full min-w-0 max-w-full"
          metadata={
            <ChatMessageMetadata
              className="mt-4"
              timestamp={run ? <Timestamp value={run.createdAt} format="time" /> : undefined}
              footer={
                <div className="mt-2 flex w-full flex-wrap items-center justify-between gap-3">
                  <div className="flex items-center gap-2">
                    <Text type="supporting" color="secondary">
                      Source agent
                    </Text>
                    {versions.length > 1 && (
                      <div className="flex items-center gap-1 text-xs" role="navigation" aria-label="Response versions">
                        <button
                          type="button"
                          disabled={currentVersionIndex <= 0}
                          onClick={() => {
                            const prev = versions[currentVersionIndex - 1];
                            if (prev && onSelectVersion) onSelectVersion(prev.id);
                          }}
                          aria-label="Previous response version"
                          className="rounded p-0.5 text-black/50 hover:bg-black/5 disabled:opacity-30 dark:text-white/50 dark:hover:bg-white/10"
                        >
                          <ChevronLeft className="size-3.5" />
                        </button>
                        <span aria-live="polite" className="text-black/55 dark:text-white/55">
                          Response {currentVersionIndex + 1} of {versions.length}
                        </span>
                        <button
                          type="button"
                          disabled={currentVersionIndex >= versions.length - 1}
                          onClick={() => {
                            const next = versions[currentVersionIndex + 1];
                            if (next && onSelectVersion) onSelectVersion(next.id);
                          }}
                          aria-label="Next response version"
                          className="rounded p-0.5 text-black/50 hover:bg-black/5 disabled:opacity-30 dark:text-white/50 dark:hover:bg-white/10"
                        >
                          <ChevronRight className="size-3.5" />
                        </button>
                      </div>
                    )}
                  </div>
                  <div className="flex items-center gap-2">
                    {retryEligibility?.eligible ? (
                      <button
                        type="button"
                        disabled={retrying || status === "QUEUED" || status === "RUNNING"}
                        onClick={onRetry}
                        aria-label="Retry response"
                        className="inline-flex items-center gap-1 rounded-md border border-black/10 bg-white px-2.5 py-1 text-xs font-medium text-black/70 hover:bg-black/[0.03] disabled:opacity-50 dark:border-white/10 dark:bg-white/[0.04] dark:text-white/70 dark:hover:bg-white/[0.08]"
                      >
                        <RotateCcw className={`size-3 ${retrying ? "animate-spin" : ""}`} />
                        {retrying ? "Retrying…" : "Retry"}
                      </button>
                    ) : retryEligibility?.reason && retryEligibility.reason !== "RUN_NOT_TERMINAL" && retryEligibility.reason !== "NOT_LATEST_TURN" ? (
                      <span className="text-xs text-black/40 dark:text-white/40" title={retryEligibility.reason}>
                        {retryEligibility.reason === "NOT_LATEST_VERSION" ? "Select newest response to retry" : "Retry unavailable"}
                      </span>
                    ) : null}
                  </div>
                </div>
              }
            />
          }
        >
          <p className="text-sm leading-6 text-black/65 dark:text-white/68">
            {linkedArtifact ? "The source review is complete. The artifact is ready below." : instruction ? agentProgressLabel(status) : "Plot is preparing the request…"}
          </p>


          <ChatToolCalls
            calls={[{
              name: "Read connected sources",
              status: toolStatus,
              errorMessage: error || (isNeedsConnection ? "Repository connection required." : isFailed ? "Agent stopped before an artifact was produced." : undefined),
            }]}
            defaultIsExpanded={false}
          />
          <ChatSourceCitations references={references} />
          {artifactAction ? <div className="mt-4">{artifactAction}</div> : null}
        </ChatMessageBubble>
      </ChatMessage>
      {error ? <ErrorNotice message={error} /> : null}
      {(isFailed || isNeedsConnection) && !error ? (
        <ErrorNotice message={isNeedsConnection ? `Repository connection required. Reconnect repository access to proceed.` : "Agent stopped before an artifact was produced. It remains available as chat activity."} />
      ) : null}
    </section>
  );
}

export function EmptyArtifactState({ hasSelection }: { hasSelection: boolean }) {
  return (
    <ChatSystemMessage>
      {hasSelection ? "This Agent request is still working or did not produce an artifact. Review its activity above." : "Select an Agent request to inspect its artifact."}
    </ChatSystemMessage>
  );
}

export function ErrorNotice({ message }: { message: string }) {
  return <div role="alert" className="mt-3 rounded-xl border border-rose-300/60 bg-rose-50 px-4 py-3 text-sm text-rose-900 dark:border-rose-400/25 dark:bg-rose-400/[0.08] dark:text-rose-200">{message}</div>;
}
