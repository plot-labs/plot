"use client";

import { Eye, MessageCircle, MoreHorizontal, Save, X } from "lucide-react";
import { useRouter } from "next/navigation";
import { type CSSProperties, useCallback, useEffect, useMemo, useRef, useState } from "react";

import {
  ChatMessage,
  ChatMessageBubble,
  ChatMessageList,
} from "@astryxdesign/core/Chat";
import { ResizeHandle, useResizable } from "@astryxdesign/core/Resizable";
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
  const [mobilePanel, setMobilePanel] = useState<"assistant" | "history" | null>(null);
  const [artifactPanelOpen, setArtifactPanelOpen] = useState(false);
  const [artifactSaveRequestToken, setArtifactSaveRequestToken] = useState(0);
  const artifactPanel = useResizable({ defaultSize: 720, minSizePx: 420, maxSizePx: 1200 });
  const resizeArtifactPanel = artifactPanel.resize;
  const mobileAssistantTriggerRef = useRef<HTMLButtonElement>(null);
  const mobileHistoryTriggerRef = useRef<HTMLButtonElement>(null);
  const artifactTriggerRef = useRef<HTMLButtonElement>(null);
  const workspaceRef = useRef<HTMLDivElement>(null);
  const artifactPanelInitializedRef = useRef(false);
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

  useEffect(() => {
    if (!artifactPanelOpen || artifactPanelInitializedRef.current) return;
    if (!window.matchMedia("(min-width: 1024px)").matches) return;
    const workspaceWidth = workspaceRef.current?.getBoundingClientRect().width;
    if (!workspaceWidth) return;
    resizeArtifactPanel(Math.round(workspaceWidth / 2));
    artifactPanelInitializedRef.current = true;
  }, [artifactPanelOpen, resizeArtifactPanel]);

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
    <div ref={workspaceRef} className="relative flex h-[calc(100dvh-49px)] min-h-0 bg-[#fbfbf8] dark:bg-[#111113] lg:h-full">
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-14 shrink-0 items-center border-b border-black/[0.06] bg-[#fbfbf8]/90 px-4 py-3 backdrop-blur-xl dark:border-white/[0.07] dark:bg-[#111113]/90 sm:px-6">
          <div className="mx-auto flex w-full max-w-[760px] min-w-0 items-center justify-center gap-2 text-sm font-semibold text-black/78 dark:text-white/82">
            <MessageCircle aria-hidden="true" className="size-4 shrink-0 text-black/50 dark:text-white/50" />
            <h1 className="truncate text-center">{activeChat.title || "Untitled chat"}</h1>
            <MoreHorizontal aria-hidden="true" className="size-4 shrink-0 text-black/45 dark:text-white/45" />
          </div>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto bg-[#fbfbf8] dark:bg-[#16171a]">
          <div className="mx-auto w-full max-w-[760px] px-4 pb-12 pt-8 sm:px-6">
            <ChatMessageList density="compact" gap={4} style={{ flex: "none" }}>
              {messages.map((message) => (
                <ChatMessage key={message.id} sender="user">
                  <ChatMessageBubble className="max-w-[min(680px,92%)]">
                    <p>{message.content}</p>
                  </ChatMessageBubble>
                </ChatMessage>
              ))}
              <AgentActivityDetail
                run={agent.agentRun ?? agent.selectedActivity}
                busy={agent.agentBusy}
                error={agent.agentError}
                instruction={agent.agentInstruction}
                references={references}
              />
            </ChatMessageList>

            <div className="mt-5">
              {document.artifactError ? <ErrorNotice message={document.artifactError} /> : null}
              {document.currentArtifact ? (
                <button
                  ref={artifactTriggerRef}
                  id="artifact-preview"
                  type="button"
                  aria-controls="artifact-editor-panel"
                  aria-expanded={artifactPanelOpen}
                  onClick={() => setArtifactPanelOpen(true)}
                  className="flex w-full items-center justify-between gap-4 rounded-xl border border-black/[0.08] bg-white/70 px-4 py-3 text-left transition hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 dark:border-white/10 dark:bg-white/[0.04] dark:hover:bg-white/[0.07] dark:focus-visible:ring-white/25"
                >
                  <span className="min-w-0 truncate text-sm font-medium text-black/82 dark:text-white/85">
                    {document.currentArtifact.title || "Generated artifact"}
                  </span>
                  <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-black/[0.035] px-3 py-1.5 text-xs font-medium text-black/50 dark:bg-white/[0.06] dark:text-white/55">
                    <Eye aria-hidden="true" className="size-3.5" />
                    Open artifact
                  </span>
                </button>
              ) : !agent.activitiesLoading && !agent.agentBusy && !agent.agentRun ? (
                <EmptyArtifactState hasSelection={Boolean(agent.selectedActivity)} />
              ) : null}
            </div>

            <div className="mt-5 lg:hidden">
              <div role="tablist" aria-label="Chat workspace panels" className="flex gap-2">
                <button
                  ref={mobileAssistantTriggerRef}
                  type="button"
                  role="tab"
                  aria-selected={mobilePanel === "assistant"}
                  aria-expanded={mobilePanel === "assistant"}
                  aria-controls="mobile-chat-assistant-panel"
                  onClick={() => setMobilePanel((current) => current === "assistant" ? null : "assistant")}
                  className={`min-h-8 rounded-full border px-3 text-xs font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 dark:focus-visible:ring-white/25 ${mobilePanel === "assistant" ? "border-black bg-black text-white dark:border-white dark:bg-white dark:text-black" : "border-black/10 bg-white text-black/55 hover:bg-black/[0.03] dark:border-white/10 dark:bg-white/[0.04] dark:text-white/58 dark:hover:bg-white/[0.07]"}`}
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
                  className={`min-h-8 rounded-full border px-3 text-xs font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:cursor-not-allowed disabled:opacity-40 dark:focus-visible:ring-white/25 ${mobilePanel === "history" ? "border-black bg-black text-white dark:border-white dark:bg-white dark:text-black" : "border-black/10 bg-white text-black/55 hover:bg-black/[0.03] dark:border-white/10 dark:bg-white/[0.04] dark:text-white/58 dark:hover:bg-white/[0.07]"}`}
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
          onSubmit={(message, ids) => {
            setArtifactPanelOpen(false);
            void agent.submitMessage(message, ids, document.clearArtifactSelection);
          }}
          references={toComposerReferences(references)}
          busy={document.artifactLoading || agent.agentBusy || agent.activitiesLoading}
        />
      </div>
      {artifactPanelOpen && document.currentArtifact ? (
        <ResizeHandle
          direction="horizontal"
          isReversed
          hasDivider
          isAlwaysVisible={false}
          label="Resize artifact document"
          resizable={artifactPanel.props}
          className="hidden lg:block"
        />
      ) : null}
      {artifactPanelOpen && document.currentArtifact ? (
        <aside
          id="artifact-editor-panel"
          aria-label="Artifact document panel"
          className="absolute inset-0 z-30 flex min-w-0 flex-col border-l border-black/[0.08] bg-[#fbfbf8] dark:border-white/10 dark:bg-[#16171a] lg:static lg:w-[var(--artifact-panel-width)] lg:max-w-[calc(100%-420px)] lg:shrink-0"
          style={{ "--artifact-panel-width": `${artifactPanel.size}px` } as CSSProperties}
        >
          <header className="flex min-h-16 shrink-0 items-center justify-end gap-3 px-4">
            <div className="flex items-center gap-2">
              <span role="status" aria-live="polite" className="hidden text-xs text-black/42 dark:text-white/45 sm:inline">
                {document.saveState === "saving" ? "Saving…" : document.saveState === "dirty" ? "Unsaved changes" : document.saveState === "error" ? "Save needs attention" : "Saved"}
              </span>
              {!document.historicalArtifact ? (
                <button
                  type="button"
                  disabled={document.saveState === "saving"}
                  onClick={() => setArtifactSaveRequestToken((value) => value + 1)}
                  className="inline-flex min-h-8 items-center gap-1.5 rounded-lg bg-black px-3 text-xs font-medium text-white transition hover:bg-black/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:pointer-events-none disabled:opacity-40 dark:bg-white dark:text-black dark:hover:bg-white/85 dark:focus-visible:ring-white/25"
                >
                  <Save aria-hidden="true" className="size-3.5" />
                  Save draft
                </button>
              ) : null}
              <button
                type="button"
                aria-label="Close artifact"
                onClick={() => {
                  setArtifactPanelOpen(false);
                  artifactTriggerRef.current?.focus();
                }}
                className="inline-flex size-8 shrink-0 items-center justify-center rounded-full text-black/45 transition hover:bg-black/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 dark:text-white/50 dark:hover:bg-white/[0.08] dark:focus-visible:ring-white/25"
              >
                <X aria-hidden="true" className="size-4" />
              </button>
            </div>
          </header>
          <div className="flex min-h-0 flex-1 items-start justify-center overflow-y-auto bg-[#fbfbf8] dark:bg-[#18181b]">
            <ArtifactDocumentSurface
              presentation="workspace"
              pack={document.currentArtifact}
              historical={document.historicalArtifact}
              client={plotApiClient}
              initialDraft={document.historicalArtifact ? undefined : document.drafts[document.currentArtifact.id]}
              saveState={document.saveState}
              saveRequestToken={artifactSaveRequestToken}
              onSaveStateChange={document.onSaveStateChange}
              onDraftChange={document.onDraftChange}
              onSaveArtifact={document.onSaveArtifact}
              onPackChange={document.onPackChange}
            />
          </div>
        </aside>
      ) : null}
    </div>
  );
}

