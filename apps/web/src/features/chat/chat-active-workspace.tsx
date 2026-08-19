"use client";

import { MessageCircle, MoreHorizontal } from "lucide-react";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import {
  ChatMessage,
  ChatMessageBubble,
  ChatMessageList,
} from "@astryxdesign/core/Chat";
import type { ChatAgentRun, SourceReference, WorkSessionSummary as ChatSummary } from "@plot/api-client";
import { ArtifactDocumentSurface } from "@/features/artifacts/artifact-document-surface";
import { ArtifactHistoryPanel } from "@/features/citations/artifact-history-panel";
import { ChatComposer } from "@/features/chat/chat-composer";
import { AgentActivityDetail, ChatActivityPanel, EmptyArtifactState, ErrorNotice } from "@/features/chat/chat-activity";
import { chatHref, toComposerReferences } from "@/features/chat/chat-workspace-utils";
import { useChatAgentActivity } from "@/features/chat/use-chat-agent-activity";
import { useChatArtifactDocument } from "@/features/chat/use-chat-artifact-document";
import { plotApiClient } from "@/lib/api-client";

type ChatActiveWorkspaceProps = {
  activeChat: ChatSummary;
  references: SourceReference[];
  sourceError: string;
  requestedAgentId: string | null;
  requestedArtifactId: string | null;
};

export function ChatActiveWorkspace({ activeChat, references, sourceError, requestedAgentId, requestedArtifactId }: ChatActiveWorkspaceProps) {
  const router = useRouter();
  const [mobilePanel, setMobilePanel] = useState<"assistant" | "history" | null>("assistant");
  const mobileAssistantTriggerRef = useRef<HTMLButtonElement>(null);
  const mobileHistoryTriggerRef = useRef<HTMLButtonElement>(null);
  const previousMobilePanelRef = useRef<"assistant" | "history" | null>(null);

  const onAgentArtifact = useCallback((run: ChatAgentRun) => {
    if (run.artifactId) router.replace(chatHref(activeChat.id, run.id, run.artifactId), { scroll: false });
  }, [activeChat.id, router]);
  const onAdmitted = useCallback((run: ChatAgentRun) => {
    router.replace(chatHref(activeChat.id, run.id), { scroll: false });
  }, [activeChat.id, router]);
  const agent = useChatAgentActivity({
    chatId: activeChat.id,
    requestedAgentId,
    requestedArtifactId,
    references,
    sourceError,
    onAgentArtifact,
    onAdmitted,
  });
  const document = useChatArtifactDocument({
    requestedArtifactId,
    selectedActivityArtifactId: agent.selectedActivity?.artifactId ?? null,
  });

  useEffect(() => {
    const previous = previousMobilePanelRef.current;
    if (previous && mobilePanel === null) {
      (previous === "assistant" ? mobileAssistantTriggerRef : mobileHistoryTriggerRef).current?.focus();
    }
    previousMobilePanelRef.current = mobilePanel;
  }, [mobilePanel]);

  const selectActivity = useCallback((activity: ChatAgentRun) => {
    document.resetHistory();
    if (!activity.artifactId) setMobilePanel("assistant");
    router.replace(chatHref(activeChat.id, activity.id, activity.artifactId), { scroll: false });
  }, [activeChat.id, document, router]);

  const messages = useMemo(() => {
    const current = agent.activities
      .filter((activity) => activity.instruction)
      .map((activity) => ({
        id: activity.id,
        role: "user" as const,
        content: activity.instruction!,
      }));
    return current.length
      ? current
      : [{ id: activeChat.id, role: "user" as const, content: activeChat.title || "Untitled request" }];
  }, [activeChat.id, activeChat.title, agent.activities]);

  return (
    <div className="flex h-full min-h-dvh bg-white dark:bg-[#111113] lg:min-h-0">
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-14 shrink-0 items-center gap-4 border-b border-black/[0.06] bg-white/90 px-4 py-3 backdrop-blur-xl dark:border-white/[0.07] dark:bg-[#111113]/90 sm:px-6 lg:px-8">
          <div className="flex min-w-0 items-center gap-2 text-sm font-semibold text-black/78 dark:text-white/82">
            <MessageCircle aria-hidden="true" className="size-4 shrink-0 text-black/50 dark:text-white/50" />
            <h1 className="truncate">{activeChat.title || "Untitled chat"}</h1>
            <MoreHorizontal aria-hidden="true" className="size-4 shrink-0 text-black/45 dark:text-white/45" />
          </div>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto bg-[#f7f8fa] dark:bg-[#16171a]">
          <div className="mx-auto w-full max-w-5xl px-4 pb-10 pt-6 sm:px-6 lg:px-8 lg:pt-8">
            <ChatMessageList density="compact" gap={4} style={{ flex: "none" }}>
              {messages.map((message) => (
                <ChatMessage key={message.id} sender="user">
                  <ChatMessageBubble className="max-w-[min(680px,92%)]">
                    <p>{message.content}</p>
                  </ChatMessageBubble>
                </ChatMessage>
              ))}
              <AgentActivityDetail
                run={agent.agentRun}
                busy={agent.agentBusy}
                error={agent.agentError}
                instruction={agent.agentInstruction}
                references={references}
              />
            </ChatMessageList>

            <div className="mt-8">
              {document.artifactError ? <ErrorNotice message={document.artifactError} /> : null}
              {document.currentArtifact ? (
                <ArtifactDocumentSurface
                  pack={document.currentArtifact}
                  historical={document.historicalArtifact}
                  client={plotApiClient}
                  initialDraft={document.historicalArtifact ? undefined : document.drafts[document.currentArtifact.id]}
                  saveState={document.saveState}
                  onSaveStateChange={document.onSaveStateChange}
                  onDraftChange={document.onDraftChange}
                  onSaveArtifact={document.onSaveArtifact}
                  onPackChange={document.onPackChange}
                />
              ) : !agent.activitiesLoading && !agent.agentBusy && !agent.agentRun ? (
                <EmptyArtifactState hasSelection={Boolean(agent.selectedActivity)} />
              ) : null}
            </div>

            <div className="mt-5 lg:hidden">
              <div role="tablist" aria-label="Chat workspace panels" className="flex gap-2 rounded-xl border border-black/10 bg-white p-2 dark:border-white/10 dark:bg-white/[0.04]">
                <button
                  ref={mobileAssistantTriggerRef}
                  type="button"
                  role="tab"
                  aria-selected={mobilePanel === "assistant"}
                  aria-expanded={mobilePanel === "assistant"}
                  aria-controls="mobile-chat-assistant-panel"
                  onClick={() => setMobilePanel((current) => current === "assistant" ? null : "assistant")}
                  className={`min-h-10 flex-1 rounded-lg px-3 text-sm font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 ${mobilePanel === "assistant" ? "bg-black text-white dark:bg-white dark:text-black" : "text-black/60 hover:bg-black/[0.04] dark:text-white/62 dark:hover:bg-white/[0.06]"}`}
                >
                  Assistant
                </button>
                <button
                  ref={mobileHistoryTriggerRef}
                  type="button"
                  role="tab"
                  disabled={!document.currentArtifact}
                  aria-selected={mobilePanel === "history"}
                  aria-expanded={mobilePanel === "history"}
                  aria-controls="mobile-chat-history-panel"
                  onClick={() => setMobilePanel((current) => current === "history" ? null : "history")}
                  className={`min-h-10 flex-1 rounded-lg px-3 text-sm font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 disabled:cursor-not-allowed disabled:opacity-40 ${mobilePanel === "history" ? "bg-black text-white dark:bg-white dark:text-black" : "text-black/60 hover:bg-black/[0.04] dark:text-white/62 dark:hover:bg-white/[0.06]"}`}
                >
                  History
                </button>
              </div>
              {mobilePanel === "assistant" ? (
                <div id="mobile-chat-assistant-panel" role="tabpanel" aria-label="Assistant panel" className="mt-3">
                  <ChatActivityPanel
                    activities={agent.activities}
                    selectedActivityId={agent.selectedActivity?.id ?? null}
                    loading={agent.activitiesLoading}
                    error={agent.activitiesError}
                    onSelect={selectActivity}
                  />
                </div>
              ) : null}
              {mobilePanel === "history" && document.currentArtifact ? (
                <div id="mobile-chat-history-panel" role="tabpanel" aria-label="History panel" className="mt-3 rounded-xl border border-black/10 bg-white p-4 dark:border-white/10 dark:bg-white/[0.04]">
                  <ArtifactHistoryPanel
                    variantId={document.currentArtifact.variant.id}
                    client={plotApiClient}
                    refreshKey={document.currentArtifact.variant.revisionId}
                    selectedPosition={document.historicalPosition}
                    onSelect={document.selectHistoricalArtifact}
                  />
                </div>
              ) : null}
            </div>
          </div>
        </div>

        <ChatComposer
          id="chat-composer"
          key={references.map((reference) => reference.id).join(":") || "no-references"}
          variant="dock"
          placeholder="Ask Plot to create another source-backed artifact..."
          onSubmit={(message, ids) => void agent.submitMessage(message, ids, document.clearArtifactSelection)}
          references={toComposerReferences(references)}
          busy={document.artifactLoading || agent.agentBusy || agent.activitiesLoading}
        />
      </div>
    </div>
  );
}

