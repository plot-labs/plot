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
import { LoaderCircle } from "lucide-react";
import type { ReactNode } from "react";

import type { ChatAgentRun, ExecutionTimelineItem, SourceReference } from "@plot/api-client";
import {
  agentProgressLabel,
  agentStatusLabel,
  formatActivity,
  formatTimelineTime,
  isSpinningStatus,
  timelineStatusLabel,
} from "@/features/chat/chat-workspace-utils";
import { ChatSourceCitations } from "@/features/chat/chat-source-citations";

export function ChatActivityPanel({
  activities,
  selectedActivityId,
  loading,
  error,
  onSelect,
  timeline = [],
}: {
  activities: ChatAgentRun[];
  selectedActivityId: string | null;
  loading: boolean;
  error: string;
  onSelect: (activity: ChatAgentRun) => void;
  timeline?: ExecutionTimelineItem[];
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
            const timelineItem = timeline.find((item) => item.agentRunId === activity.id || item.id === activity.id);
            const statusLabel = timelineItem?.statusLabel ?? timelineStatusLabel(activity.status, activity.failureCode);
            const showSpinner = isSpinningStatus(
              timelineItem?.status ?? activity.status,
              timelineItem?.safeErrorCode ?? activity.failureCode,
              timelineItem?.nextAttemptAt,
            );
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
                  {timelineItem?.safeErrorCode ? (
                    <span className="mt-1 block font-mono text-[11px] text-rose-600 dark:text-rose-400">
                      {timelineItem.safeErrorCode}
                    </span>
                  ) : null}
                  {timelineItem?.nextAttemptAt ? (
                    <span className="mt-1 block text-[11px] text-amber-600 dark:text-amber-400">
                      Next retry: {formatTimelineTime(timelineItem.nextAttemptAt)}
                    </span>
                  ) : null}
                </button>
              </li>
            );
          })}
        </ol>
      ) : null}
    </section>
  );
}

export function ExecutionTimelineCard({
  item,
}: {
  item: ExecutionTimelineItem;
}) {
  const showSpinner = isSpinningStatus(item.status, item.safeErrorCode, item.nextAttemptAt);
  return (
    <div data-testid="execution-timeline-card" className="mt-3 rounded-lg border border-black/10 bg-black/[0.02] p-3 dark:border-white/10 dark:bg-white/[0.03]">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-[0.06em] text-black/55 dark:text-white/60">
          {showSpinner ? <LoaderCircle aria-hidden="true" data-testid="timeline-spinner" className="size-3.5 animate-spin" /> : null}
          <span data-testid="timeline-status">{item.statusLabel}</span>
        </span>
        <span data-testid="timeline-stage" className="rounded bg-black/[0.05] px-1.5 py-0.5 text-[11px] font-medium tracking-wide text-black/45 dark:bg-white/10 dark:text-white/50">
          {item.stage}
        </span>
      </div>
      {item.updatedAt ? (
        <div data-testid="timeline-updated-at" className="mt-1 text-xs text-black/42 dark:text-white/45">
          Last updated: {formatTimelineTime(item.updatedAt)}
        </div>
      ) : null}
      {item.nextAttemptAt ? (
        <div data-testid="timeline-next-retry" className="mt-1 text-xs font-medium text-amber-700 dark:text-amber-400">
          Next retry: {formatTimelineTime(item.nextAttemptAt)}
        </div>
      ) : null}
      {item.safeErrorCode ? (
        <div data-testid="timeline-error-code" className="mt-1 text-xs font-mono text-rose-700 dark:text-rose-400">
          Error: {item.safeErrorCode}
        </div>
      ) : null}
      {item.recoveryAction ? (
        <div data-testid="timeline-recovery-action" className="mt-1 text-xs text-black/60 dark:text-white/65">
          {item.recoveryAction}
        </div>
      ) : null}
    </div>
  );
}

export function AgentActivityDetail({
  run,
  busy,
  error,
  instruction,
  references,
  artifactAction,
  timelineItem,
}: {
  run: ChatAgentRun | null;
  busy: boolean;
  error: string;
  instruction: string;
  references: SourceReference[];
  artifactAction?: ReactNode;
  timelineItem?: ExecutionTimelineItem | null;
}) {
  if (!run && !busy && !error && !timelineItem) return null;
  const status = run?.status ?? "QUEUED";
  const linkedArtifact = Boolean(run?.artifactId);
  const toolStatus = error || run?.status === "FAILED" || timelineItem?.status === "FAILED"
    ? "error"
    : linkedArtifact || run?.status === "SUCCEEDED" || timelineItem?.status === "READY"
      ? "complete"
      : "running";

  const effectiveStatusLabel = timelineItem?.statusLabel ?? timelineStatusLabel(status, run?.failureCode);
  const effectiveStage = timelineItem?.stage ?? (linkedArtifact ? "ARTIFACT" : status === "RUNNING" ? "AGENT" : "ADMISSION");
  const effectiveUpdated = timelineItem?.updatedAt ?? run?.updatedAt;
  const effectiveNextRetry = timelineItem?.nextAttemptAt;
  const effectiveErrorCode = timelineItem?.safeErrorCode ?? run?.failureCode;
  const effectiveRecoveryAction = timelineItem?.recoveryAction ?? (
    effectiveStatusLabel === "Needs connection"
      ? "Reconnect repository access"
      : effectiveStatusLabel === "Retry scheduled"
        ? "Automatic retry scheduled"
        : effectiveStatusLabel === "Failed"
          ? "Review safe error code and retry"
          : null
  );
  const showSpinner = isSpinningStatus(timelineItem?.status ?? status, effectiveErrorCode, effectiveNextRetry);

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
                <Text type="supporting" color="secondary">
                  Source agent
                </Text>
              }
            />
          }
        >
          <p className="text-sm leading-6 text-black/65 dark:text-white/68">
            {linkedArtifact ? "The source review is complete. The artifact is ready below." : instruction ? agentProgressLabel(status) : "Plot is preparing the request…"}
          </p>

          <div data-testid="agent-timeline" className="mt-3 rounded-lg border border-black/10 bg-black/[0.02] p-3 dark:border-white/10 dark:bg-white/[0.03]">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-[0.06em] text-black/55 dark:text-white/60">
                {showSpinner ? <LoaderCircle aria-hidden="true" data-testid="timeline-spinner" className="size-3.5 animate-spin" /> : null}
                <span data-testid="timeline-status">{effectiveStatusLabel}</span>
              </span>
              <span data-testid="timeline-stage" className="rounded bg-black/[0.05] px-1.5 py-0.5 text-[11px] font-medium tracking-wide text-black/45 dark:bg-white/10 dark:text-white/50">
                {effectiveStage}
              </span>
            </div>
            {effectiveUpdated ? (
              <div data-testid="timeline-updated-at" className="mt-1 text-xs text-black/42 dark:text-white/45">
                Last updated: {formatTimelineTime(effectiveUpdated)}
              </div>
            ) : null}
            {effectiveNextRetry ? (
              <div data-testid="timeline-next-retry" className="mt-1 text-xs font-medium text-amber-700 dark:text-amber-400">
                Next retry: {formatTimelineTime(effectiveNextRetry)}
              </div>
            ) : null}
            {effectiveErrorCode ? (
              <div data-testid="timeline-error-code" className="mt-1 text-xs font-mono text-rose-700 dark:text-rose-400">
                Error: {effectiveErrorCode}
              </div>
            ) : null}
            {effectiveRecoveryAction ? (
              <div data-testid="timeline-recovery-action" className="mt-1 text-xs text-black/60 dark:text-white/65">
                {effectiveRecoveryAction}
              </div>
            ) : null}
          </div>

          <ChatToolCalls
            calls={[{
              name: "Read connected sources",
              status: toolStatus,
              errorMessage: error || (run?.status === "FAILED" || timelineItem?.status === "FAILED" ? "Agent stopped before an artifact was produced." : undefined),
            }]}
            defaultIsExpanded={false}
          />
          <ChatSourceCitations references={references} />
          {artifactAction ? <div className="mt-4">{artifactAction}</div> : null}
        </ChatMessageBubble>
      </ChatMessage>
      {error ? <ErrorNotice message={error} /> : null}
      {(run?.status === "FAILED" || timelineItem?.status === "FAILED") && !error ? (
        <ErrorNotice message={`Agent stopped before an artifact was produced${effectiveErrorCode ? ` (${effectiveErrorCode})` : ""}. It remains available as chat activity.`} />
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
