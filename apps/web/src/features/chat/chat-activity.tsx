"use client";

import {Message, MessageContent, MessageActions} from "@/components/ai-elements/message";
import {Tool, ToolHeader, type ToolState} from "@/components/ai-elements/tool";
import { ChevronLeft, ChevronRight, RotateCcw } from "lucide-react";
import Link from "next/link";
import type { ReactNode } from "react";

import type { ChatAgentRun, ChatCitation, ChatResponseVersion, RetryEligibility } from "@plot/api-client";

function formatChatTime(value: string | number | Date): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat("en-US", {
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  }).format(date);
}

import {
  agentProgressLabel,
  CONNECTION_ERROR_CODES,
} from "@/features/chat/chat-workspace-utils";
import { ChatSourceCitations } from "@/features/chat/chat-source-citations";

export function AgentActivityDetail({
  run,
  busy,
  error,
  instruction,
  citations,
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
  citations?: ChatCitation[];
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
  const responseText = run?.responseText?.trim() || "";
  const isFailed = run?.status === "FAILED";
  const isNeedsConnection = Boolean(run?.failureCode && CONNECTION_ERROR_CODES.has(run.failureCode));
  const isComplete = Boolean(run?.status === "SUCCEEDED");
  const citationCount = citations?.length ?? 0;
  const citationSources = (citations ?? []).slice(0, 2).map((citation) => ({
    id: citation.id,
    title: citation.title || "Source",
    url: citation.url,
  }));
  const toolState: ToolState = error || isFailed || isNeedsConnection
    ? "output-error"
    : isComplete
      ? "output-available"
      : status === "QUEUED" ? "input-streaming" : "input-available";

  const currentVersionIndex = versions.length > 0 && selectedVersionId
    ? Math.max(0, versions.findIndex((v) => v.id === selectedVersionId))
    : versions.length > 0 ? versions.length - 1 : 0;

  return (
    <section aria-label="Agent request details">
      <Message from="assistant">
        <MessageContent
          className="w-full min-w-0 max-w-full"
        >
          <p className="whitespace-pre-wrap text-sm leading-6 text-black/75 dark:text-white/78">
            {responseText || (linkedArtifact && isComplete ? "The content is ready below." : instruction ? agentProgressLabel(status) : "Plot is preparing the request…")}
          </p>


          {(linkedArtifact || busy || isFailed || isNeedsConnection) ? (
            <Tool>
              <ToolHeader title={linkedArtifact ? "Create content" : "Process request"} state={toolState} errorText={error || (isNeedsConnection ? "Repository connection required." : isFailed ? "The response could not be completed." : undefined)}/>
            </Tool>
          ) : null}
          <ChatSourceCitations sources={citationSources} totalCount={citationCount} />
          {artifactAction ? <div className="mt-4">{artifactAction}</div> : null}
        </MessageContent>
          <MessageActions className="mt-[10px] [[role=log]_&]:mt-2 gap-1 whitespace-nowrap px-4 text-[12px] leading-[1.6667] text-[light-dark(#4e606f,#aaafb5)]">
            {run ? <span><time dateTime={new Date(run.createdAt).toISOString()} className="whitespace-nowrap">{formatChatTime(run.createdAt)}</time></span> : null} {run ? <span>·</span> : null}
            
                <div className="flex items-center gap-1.5 whitespace-nowrap text-black/50 dark:text-white/50">
                  <span className="mr-1 text-[12px] text-[light-dark(#4e606f,#aaafb5)]">
                    Plot
                  </span>
                    {retryEligibility?.eligible && (
                      <button aria-busy={Boolean(retrying)}
                        type="button"
                        disabled={retrying || status === "QUEUED" || status === "RUNNING"}
                        onClick={onRetry}
                        aria-label="Retry response"
                        title="Retry"
                        className="glass-button glass-icon inline-flex size-6 items-center justify-center"
                      >
                        <RotateCcw className={`size-3.5 ${retrying ? "animate-spin" : ""}`} />
                      </button>
                    )}
                    {versions.length > 1 && (
                      <div className="flex items-center gap-0.5 text-xs text-black/50 dark:text-white/50" role="navigation" aria-label="Response versions">
                        <button
                          type="button"
                          disabled={currentVersionIndex <= 0}
                          onClick={() => {
                            const prev = versions[currentVersionIndex - 1];
                            if (prev && onSelectVersion) onSelectVersion(prev.id);
                          }}
                          aria-label="Previous response version"
                          className="glass-button glass-icon inline-flex size-6 items-center justify-center"
                        >
                          <ChevronLeft className="size-3.5" />
                        </button>
                        <span aria-live="polite" className="px-0.5 text-[11px] font-normal tabular-nums text-black/60 dark:text-white/60">
                          <span className="sr-only">Response {currentVersionIndex + 1} of {versions.length}</span>
                          <span aria-hidden="true">{currentVersionIndex + 1}/{versions.length}</span>
                        </span>
                        <button
                          type="button"
                          disabled={currentVersionIndex >= versions.length - 1}
                          onClick={() => {
                            const next = versions[currentVersionIndex + 1];
                            if (next && onSelectVersion) onSelectVersion(next.id);
                          }}
                          aria-label="Next response version"
                          className="glass-button glass-icon inline-flex size-6 items-center justify-center"
                        >
                          <ChevronRight className="size-3.5" />
                        </button>
                      </div>
                    )}
                  </div>

          </MessageActions>

      </Message>
      {error ? <ErrorNotice message={error} /> : null}
      {(isFailed || isNeedsConnection) && !error ? (
        <ErrorNotice
          message={isNeedsConnection ? "Repository connection required. Reconnect repository access to proceed." : "Plot could not complete this response. It remains available in chat history."}
          action={isNeedsConnection ? <Link href="/settings/integrations" className="font-medium underline underline-offset-4">Open Connections</Link> : null}
        />
      ) : null}
    </section>
  );
}

export function ErrorNotice({ message, action }: { message: string; action?: ReactNode }) {
  return (
    <div role="alert" className="mt-3 rounded-xl border border-rose-300/60 bg-rose-50 px-4 py-3 text-sm text-rose-900 dark:border-rose-400/25 dark:bg-rose-400/[0.08] dark:text-rose-200">
      {message}
      {action ? <span className="ml-2">{action}</span> : null}
    </div>
  );
}
