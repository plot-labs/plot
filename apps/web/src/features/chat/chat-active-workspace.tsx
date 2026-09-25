"use client";

import { Eye, History, MoreHorizontal, X } from "lucide-react";
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
import { ArtifactEditorStatus, ArtifactSaveDraftButton, artifactSaveStateLabel } from "@/features/artifacts/artifact-editor-chrome";
import { ArtifactHistoryPanel } from "@/features/citations/artifact-history-panel";
import { ExportDialog } from "@/features/citations/export-dialog";
import { ChatComposer } from "@/features/chat/chat-composer";
import { AgentActivityDetail, ChatActivityPanel, ErrorNotice } from "@/features/chat/chat-activity";
import { chatHref, toComposerReferences } from "@/features/chat/chat-workspace-utils";
import { useChatAgentActivity } from "@/features/chat/use-chat-agent-activity";
import { useChatArtifactDocument } from "@/features/chat/use-chat-artifact-document";
import { plotApiClient } from "@/lib/api-client";
import { useWorkspaceEntitlement } from "@/lib/use-workspace-entitlement";
type ChatActiveWorkspaceProps = {
  activeChat: ChatSummary;
  references: SourceReference[];
  sourceError: string;
  requestedAgentId: string | null;
  requestedArtifactId: string | null;
  requestedVersionId?: string | null;
};

const chatBottomFade: CSSProperties = {
  maskImage: "linear-gradient(black calc(100% - 16px), transparent 100%)",
  WebkitMaskImage: "linear-gradient(black calc(100% - 16px), transparent 100%)",
};

const toolbarBottomFade: CSSProperties = {
  maskImage: "linear-gradient(black calc(100% - 12px), transparent 100%)",
  WebkitMaskImage: "linear-gradient(black calc(100% - 12px), transparent 100%)",
};

export function ChatActiveWorkspace({
  activeChat,
  references,
  sourceError,
  requestedAgentId,
  requestedArtifactId,
  requestedVersionId = null,
}: ChatActiveWorkspaceProps) {
  const router = useRouter();
  const entitlement = useWorkspaceEntitlement();
  const canGenerate = entitlement?.capabilities.generate ?? true;
  const canEdit = entitlement?.capabilities.edit ?? true;
  const [mobilePanel, setMobilePanel] = useState<"assistant" | "history" | null>(null);
  const [artifactPanelOpen, setArtifactPanelOpen] = useState(false);
  const [artifactHistoryOpen, setArtifactHistoryOpen] = useState(false);
  const [artifactSaveRequestToken, setArtifactSaveRequestToken] = useState(0);
  const artifactPanel = useResizable({ defaultSize: 720, minSizePx: 420, maxSizePx: 1200 });
  const resizeArtifactPanel = artifactPanel.resize;
  const mobileAssistantTriggerRef = useRef<HTMLButtonElement>(null);
  const mobileHistoryTriggerRef = useRef<HTMLButtonElement>(null);
  const artifactTriggerRef = useRef<HTMLButtonElement>(null);
  const artifactHistoryTriggerRef = useRef<HTMLButtonElement>(null);
  const workspaceRef = useRef<HTMLDivElement>(null);
  const artifactPanelInitializedRef = useRef(false);
  const artifactAutoOpenedRef = useRef<string | null>(null);
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
    requestedVersionId,
    references,
    sourceError,
    onAgentArtifact,
    onAdmitted,
  });
  const document = useChatArtifactDocument({
    requestedArtifactId,
    selectedActivityArtifactId: agent.selectedActivity?.artifactId ?? null,
  });
  const shownArtifact = document.historicalArtifact?.artifact ?? document.currentArtifact;
  const artifactMetrics = useMemo(() => {
    if (!shownArtifact) return null;
    const draft = document.historicalArtifact ? undefined : document.drafts[shownArtifact.id];
    const statements = draft?.statements ?? shownArtifact.variant.sentences;
    const text = statements.map((statement) => statement.body).join("\n");
    return {
      characters: text.length.toLocaleString("en-US"),
      words: (text.trim() ? text.trim().split(/\s+/u).length : 0).toLocaleString("en-US"),
    };
  }, [document.drafts, document.historicalArtifact, shownArtifact]);

  useEffect(() => {
    const previous = previousMobilePanelRef.current;
    if (previous && mobilePanel === null) {
      (previous === "assistant" ? mobileAssistantTriggerRef : mobileHistoryTriggerRef).current?.focus();
    }
    previousMobilePanelRef.current = mobilePanel;
  }, [mobilePanel]);

  useEffect(() => {
    if (!requestedArtifactId || document.artifactLoading || !document.currentArtifact) return;
    if (artifactAutoOpenedRef.current === requestedArtifactId) return;
    artifactAutoOpenedRef.current = requestedArtifactId;
    setArtifactPanelOpen(true);
  }, [document.artifactLoading, document.currentArtifact, requestedArtifactId]);

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
    <div ref={workspaceRef} className="relative flex h-[calc(100dvh-49px)] min-h-0 bg-white dark:bg-[#111113] lg:h-full">
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-14 shrink-0 items-center bg-white px-4 py-3 dark:bg-[#111113]" style={toolbarBottomFade}>
          <div className="flex w-full min-w-0 items-center justify-start gap-2 text-sm font-semibold text-black/78 dark:text-white/82">
            <h1 className="truncate text-left">{activeChat.title || "Untitled chat"}</h1>
            <MoreHorizontal aria-hidden="true" className="size-4 shrink-0 text-black/45 dark:text-white/45" />
          </div>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto bg-white dark:bg-[#111113]" style={chatBottomFade}>
          <div className="mx-auto w-full max-w-[760px] px-4 pb-12 pt-8 sm:px-6">
            <ChatMessageList density="compact" gap={4} style={{ flex: "none" }}>
              {agent.turns.length > 0 ? (
                agent.turns.map((turn, turnIdx) => {
                  const isLatestTurn = turnIdx === agent.turns.length - 1;
                  const selectedVersion = turn.versions.find((v) => v.id === turn.selectedVersionId)
                    ?? turn.versions[turn.versions.length - 1]
                    ?? null;
                  const runForDetail = agent.agentRun?.id === selectedVersion?.agentRunId ? agent.agentRun : selectedVersion ? {
                    id: selectedVersion.agentRunId,
                    chatId: activeChat.id,
                    instruction: selectedVersion.instruction || turn.userMessage,
                    status: selectedVersion.status,
                    failureCode: selectedVersion.failureCode,
                    responseText: selectedVersion.responseText,
                    artifactId: selectedVersion.artifactId,
                    artifact: selectedVersion.artifactId ? {
                      id: selectedVersion.artifactId,
                      status: selectedVersion.status === "SUCCEEDED" ? "READY" : "DRAFT",
                      title: selectedVersion.artifact?.title || "Generated artifact",
                      updatedAt: selectedVersion.updatedAt,
                    } : null,
                    createdAt: selectedVersion.createdAt,
                    updatedAt: selectedVersion.updatedAt,
                  } : null;

                  return (
                    <div key={turn.id} className="space-y-4">
                      <ChatMessage sender="user">
                        <ChatMessageBubble className="max-w-[min(680px,92%)]">
                          <p>{turn.userMessage}</p>
                        </ChatMessageBubble>
                      </ChatMessage>
                      {selectedVersion && (
                        <AgentActivityDetail
                          run={runForDetail}
                          busy={isLatestTurn && (agent.agentBusy || agent.isPendingRun)}
                          error={isLatestTurn ? agent.agentError : ""}
                          instruction={selectedVersion.instruction || turn.userMessage}
                          references={references}
                          citations={selectedVersion.citations ?? []}
                          versions={turn.versions}
                          selectedVersionId={selectedVersion.id}
                          onSelectVersion={(versionId) => {
                            agent.selectVersion(turn.id, versionId);
                            const found = turn.versions.find((v) => v.id === versionId);
                            if (found) {
                              router.replace(chatHref(activeChat.id, found.agentRunId, found.artifactId, found.id), { scroll: false });
                            }
                          }}
                          onRetry={isLatestTurn ? () => void agent.retryResponse(selectedVersion.id) : undefined}
                          retrying={agent.retrying}
                          retryEligibility={isLatestTurn ? selectedVersion.retryEligibility : { eligible: false, reason: "NOT_LATEST_TURN" }}
                          artifactAction={document.currentArtifact && selectedVersion.artifactId === document.currentArtifact.id ? (
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
                          ) : undefined}
                        />
                      )}
                    </div>
                  );
                })
              ) : (
                <>
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
                    artifactAction={document.currentArtifact ? (
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
                    ) : undefined}
                  />
                </>
              )}
            </ChatMessageList>

            {document.artifactError ? <ErrorNotice message={document.artifactError} /> : null}
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
          placeholder={agent.isPendingRun ? "Response in progress. Wait for it to finish..." : "Ask a follow-up..."}
          onSubmit={(message, ids, skills, model, reasoningEffort) => {
            setArtifactPanelOpen(false);
            void agent.submitMessage(message, ids, document.clearArtifactSelection, skills, model, reasoningEffort);
          }}
          references={toComposerReferences(references)}
          busy={document.artifactLoading || agent.agentBusy || agent.activitiesLoading || agent.isPendingRun}
          canGenerate={canGenerate && !agent.isPendingRun}
        />
        {agent.isPendingRun && (
          <p className="mx-auto max-w-[720px] px-4 pt-1 text-center text-xs text-black/50 dark:text-white/50">
            Response in progress. Wait for it to finish before sending a follow-up.
          </p>
        )}
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
          className="absolute inset-0 z-30 flex min-w-0 flex-col border-l border-black/[0.08] bg-[#fbfbf8] dark:border-white/10 dark:bg-[#16171a] lg:relative lg:w-[var(--artifact-panel-width)] lg:max-w-[calc(100%-420px)] lg:shrink-0"
          style={{ "--artifact-panel-width": `${artifactPanel.size}px` } as CSSProperties}
        >
          <header className="relative z-20 flex min-h-16 shrink-0 items-center justify-between gap-3 bg-[#fbfbf8]/85 px-4 backdrop-blur-xl dark:bg-[#16171a]/85">
            <div className="flex items-center gap-1">
              {!document.historicalArtifact && shownArtifact ? (
                <ExportDialog pack={shownArtifact} client={plotApiClient} presentation="copy" />
              ) : null}
              <button
                ref={artifactHistoryTriggerRef}
                type="button"
                aria-label="Artifact history"
                aria-controls="artifact-history-drawer"
                aria-expanded={artifactHistoryOpen}
                onClick={() => setArtifactHistoryOpen((open) => !open)}
                className="inline-flex min-h-8 items-center gap-1.5 rounded-lg px-2.5 text-xs font-medium text-black/58 transition hover:bg-black/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 dark:text-white/60 dark:hover:bg-white/[0.08] dark:focus-visible:ring-white/25"
              >
                <History aria-hidden="true" className="size-3.5" />
                History
              </button>
            </div>
            <div className="flex items-center gap-2">
              <span className="hidden sm:inline">
                <ArtifactEditorStatus>
                  {artifactSaveStateLabel(document.saveState, Boolean(document.historicalArtifact))}
                </ArtifactEditorStatus>
              </span>
              {!document.historicalArtifact && canEdit ? (
                <ArtifactSaveDraftButton
                  saving={document.saveState === "saving"}
                  onClick={() => setArtifactSaveRequestToken((value) => value + 1)}
                />
              ) : null}
              <button
                type="button"
                aria-label="Close artifact"
                onClick={() => {
                  setArtifactHistoryOpen(false);
                  setArtifactPanelOpen(false);
                  artifactTriggerRef.current?.focus();
                }}
                className="inline-flex size-8 shrink-0 items-center justify-center rounded-full text-black/45 transition hover:bg-black/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 dark:text-white/50 dark:hover:bg-white/[0.08] dark:focus-visible:ring-white/25"
              >
                <X aria-hidden="true" className="size-4" />
              </button>
            </div>
          </header>
          {shownArtifact ? (
            <div className="relative z-[9] shrink-0 bg-[#fbfbf8] px-6 pb-3 pt-1 dark:bg-[#18181b]">
              <div className="truncate text-[16px] font-medium leading-[22px] text-black/72 dark:text-white/76">{shownArtifact.title || "Generated artifact"}</div>
              {artifactMetrics ? (
                <div className="mt-1 text-[12px] leading-4 text-black/50 dark:text-white/52">{artifactMetrics.characters} characters · {artifactMetrics.words} words</div>
              ) : null}
            </div>
          ) : null}
          <div role="region" aria-label="Artifact document body" className="relative min-h-0 flex-1 overflow-y-auto bg-[#fbfbf8] dark:bg-[#18181b]">
            <div aria-hidden="true" className="pointer-events-none sticky top-0 z-10 -mb-1.5 h-1.5 w-full bg-gradient-to-b from-[#fbfbf8] to-transparent dark:from-[#18181b]" />
            <div className="flex items-start justify-center">
              <ArtifactDocumentSurface
                presentation="workspace"
                pack={document.currentArtifact}
                historical={document.historicalArtifact}
                client={plotApiClient}
                initialDraft={document.historicalArtifact ? undefined : document.drafts[document.currentArtifact.id]}
                saveState={document.saveState}
                saveRequestToken={artifactSaveRequestToken}
                editorLocked={!canEdit}
                onSaveStateChange={document.onSaveStateChange}
                onDraftChange={document.onDraftChange}
                onSaveArtifact={document.onSaveArtifact}
                onPackChange={document.onPackChange}
              />
            </div>
          </div>
          {artifactHistoryOpen ? (
            <aside
              id="artifact-history-drawer"
              aria-label="Artifact history drawer"
              className="absolute inset-y-0 right-0 z-30 flex w-[min(360px,100%)] flex-col border-l border-black/[0.08] bg-[#fbfbf8] shadow-[-12px_0_28px_rgba(0,0,0,0.08)] dark:border-white/10 dark:bg-[#1b1c20]"
            >
              <header className="flex min-h-16 shrink-0 items-center justify-between px-4">
                <div>
                  <div className="text-sm font-medium text-black/72 dark:text-white/76">History</div>
                  <div className="mt-0.5 text-xs text-black/50 dark:text-white/52">Content snapshots</div>
                </div>
                <button
                  type="button"
                  aria-label="Close artifact history"
                  onClick={() => {
                    setArtifactHistoryOpen(false);
                    artifactHistoryTriggerRef.current?.focus();
                  }}
                  className="inline-flex size-8 items-center justify-center rounded-full text-black/45 transition hover:bg-black/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 dark:text-white/50 dark:hover:bg-white/[0.08] dark:focus-visible:ring-white/25"
                >
                  <X aria-hidden="true" className="size-4" />
                </button>
              </header>
              <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-4">
                <ArtifactHistoryPanel
                  variantId={document.currentArtifact.variant.id}
                  client={plotApiClient}
                  refreshKey={document.currentArtifact.variant.revisionId}
                  selectedPosition={document.historicalPosition}
                  presentation="drawer"
                  onSelect={(detail, position) => {
                    document.selectHistoricalArtifact(detail, position);
                    setArtifactHistoryOpen(false);
                  }}
                />
              </div>
            </aside>
          ) : null}
        </aside>
      ) : null}
    </div>
  );
}
