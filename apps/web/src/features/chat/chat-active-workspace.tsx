"use client";

import { Eye, X } from "lucide-react";
import { useRouter } from "next/navigation";
import { type CSSProperties, useCallback, useEffect, useMemo, useRef, useState } from "react";

import {Message, MessageContent} from "@/components/ai-elements/message";
import {Conversation, ConversationContent} from "@/components/ai-elements/conversation";
import {ResizablePanelGroup, ResizablePanel, ResizableHandle} from "@/components/ui/resizable";
import {usePanelRef} from "react-resizable-panels";
import type { ChatAgentRun, WorkSessionSummary as ChatSummary } from "@plot/api-client";
import { ArtifactDocumentSurface } from "@/features/artifacts/artifact-document-surface";
import { ArtifactEditorStatus, ArtifactSaveDraftButton, artifactSaveStateLabel } from "@/features/artifacts/artifact-editor-chrome";
import { ArtifactActionsMenu } from "@/features/artifacts/artifact-actions-menu";
import { ExportDialog } from "@/features/citations/export-dialog";
import { PublishDialog } from "@/features/citations/publish-dialog";
import { ChatComposer } from "@/features/chat/chat-composer";
import { AgentActivityDetail, ErrorNotice } from "@/features/chat/chat-activity";
import { chatHref, documentPhaseStatus } from "@/features/chat/chat-workspace-utils";
import { useChatAgentActivity } from "@/features/chat/use-chat-agent-activity";
import { useChatArtifactDocument } from "@/features/chat/use-chat-artifact-document";
import { plotApiClient } from "@/lib/api-client";
import { useWorkspaceEntitlement } from "@/lib/use-workspace-entitlement";
type ChatActiveWorkspaceProps = {
  activeChat: ChatSummary;
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
  requestedAgentId,
  requestedArtifactId,
  requestedVersionId = null,
}: ChatActiveWorkspaceProps) {
  const router = useRouter();
  const entitlement = useWorkspaceEntitlement();
  const canGenerate = entitlement?.capabilities.generate ?? true;
  const canEdit = entitlement?.capabilities.edit ?? true;
  const canPublish = entitlement?.capabilities.publish ?? true;
  const canUnpublish = entitlement?.capabilities.unpublish ?? true;
  const [artifactPanelOpen, setArtifactPanelOpen] = useState(false);
  const [generationOpenedRunId, setGenerationOpenedRunId] = useState<string | null>(null);
  const [artifactSaveRequestToken, setArtifactSaveRequestToken] = useState(0);
  const artifactPanelRef = usePanelRef();
  const artifactSizeRef = useRef<number | null>(null);
  const [desktop, setDesktop] = useState(false);
  const [workspaceWidth, setWorkspaceWidth] = useState(0);
  const panelMinimum = Math.min(420, Math.max(0, workspaceWidth - 1) / 2);
  const artifactTriggerRef = useRef<HTMLButtonElement>(null);
  const workspaceRef = useRef<HTMLDivElement>(null);
  const artifactAutoOpenedRef = useRef<string | null>(null);

  const onAgentArtifact = useCallback((run: ChatAgentRun) => {
    if (run.artifactId) router.replace(chatHref(activeChat.id, run.id, run.artifactId), { scroll: false });
  }, [activeChat.id, router]);
  const onAdmitted = useCallback((run: ChatAgentRun) => {
    router.replace(chatHref(activeChat.id, run.id), { scroll: false });
    window.dispatchEvent(new Event("plot:sessions-changed"));
  }, [activeChat.id, router]);
  const agent = useChatAgentActivity({
    chatId: activeChat.id,
    requestedAgentId,
    requestedArtifactId,
    requestedVersionId,
        onAgentArtifact,
    onAdmitted,
  });
  const selectedProgress = agent.progress?.runId === agent.selectedActivity?.id ? agent.progress : null;
  const hasDocumentProgress = Boolean(selectedProgress && (selectedProgress.draftParagraphs.length ||
    ["WRITING", "REVIEWING", "REWRITING"].includes(selectedProgress.phase) || (selectedProgress.status === "SUCCEEDED" && selectedProgress.artifactId)));
  const document = useChatArtifactDocument({
    requestedArtifactId,
    selectedActivityArtifactId: agent.selectedActivity?.status === "SUCCEEDED" ? agent.selectedActivity.artifactId : null,
    retryFinalRead: selectedProgress?.status === "SUCCEEDED" && Boolean(selectedProgress.artifactId),
    retainCurrentArtifact: Boolean((selectedProgress || agent.isPendingRun) && generationOpenedRunId !== agent.agentRun?.id),
  });
  const showPreview = hasDocumentProgress && document.currentArtifact?.id !== selectedProgress?.artifactId &&
    (!document.currentArtifact || generationOpenedRunId === selectedProgress?.runId);
  const hasPanelContent = Boolean(document.currentArtifact || hasDocumentProgress);
  const desktopArtifactVisible = desktop && artifactPanelOpen && hasPanelContent;
  const openGeneratedDocument = () => {
    if (selectedProgress) setGenerationOpenedRunId(selectedProgress.runId);
    setArtifactPanelOpen(true);
  };
  const previewAction = hasDocumentProgress ? (
    <button ref={artifactTriggerRef} type="button" aria-controls="artifact-editor-panel" aria-expanded={artifactPanelOpen && showPreview}
      onClick={openGeneratedDocument} className="glass-button border text-left">
      <Eye aria-hidden="true" className="mr-2 inline size-3.5" />Open generated content
    </button>
  ) : undefined;
  const shownArtifact = document.currentArtifact;
  const artifactMetrics = useMemo(() => {
    if (!shownArtifact) return null;
    const draft = document.drafts[shownArtifact.id];
    const statements = draft?.statements ?? shownArtifact.variant.sentences;
    const text = statements.map((statement) => statement.body).join("\n");
    return {
      characters: text.length.toLocaleString("en-US"),
      words: (text.trim() ? text.trim().split(/\s+/u).length : 0).toLocaleString("en-US"),
    };
  }, [document.drafts, shownArtifact]);

  useEffect(() => {
    if (!requestedArtifactId || document.artifactLoading || !document.currentArtifact) return;
    if (artifactAutoOpenedRef.current === requestedArtifactId || (selectedProgress && artifactAutoOpenedRef.current === selectedProgress.runId)) return;
    artifactAutoOpenedRef.current = requestedArtifactId;
    setArtifactPanelOpen(true);
  }, [document.artifactLoading, document.currentArtifact, requestedArtifactId, selectedProgress]);


  useEffect(() => {
    if (!hasDocumentProgress || !selectedProgress || artifactAutoOpenedRef.current === selectedProgress.runId) return;
    let cancelled = false;
    queueMicrotask(() => {
      if (cancelled) return;
      artifactAutoOpenedRef.current = selectedProgress.runId;
      if (!document.currentArtifact) {
        setGenerationOpenedRunId(selectedProgress.runId);
        setArtifactPanelOpen(true);
      }
    });
    return () => { cancelled = true; };
  }, [document.currentArtifact, hasDocumentProgress, selectedProgress]);
  useEffect(() => {
    const media = window.matchMedia("(min-width: 1024px)");
    const update = () => setDesktop(media.matches);
    update();
    media.addEventListener("change", update);
    const observer = new ResizeObserver(() => setWorkspaceWidth(workspaceRef.current?.getBoundingClientRect().width ?? 0));
    if (workspaceRef.current) observer.observe(workspaceRef.current);
    setWorkspaceWidth(workspaceRef.current?.getBoundingClientRect().width ?? 0);
    return () => { media.removeEventListener("change", update); observer.disconnect(); };
  }, []);

  useEffect(() => {
    if (!workspaceWidth) return;
    artifactPanelRef.current?.resize(desktopArtifactVisible ? `${artifactSizeRef.current ?? workspaceWidth / 2}px` : "0%");
  }, [artifactPanelRef, desktopArtifactVisible, workspaceWidth]);

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
      <ResizablePanelGroup orientation="horizontal" disabled={!desktopArtifactVisible} className="min-h-0">
      <ResizablePanel id="chat-pane" minSize={desktopArtifactVisible ? `${panelMinimum}px` : "0%"} groupResizeBehavior="preserve-relative-size" className="flex h-full min-w-0 flex-col">

        <header className="flex min-h-14 shrink-0 items-center bg-white px-4 py-3 dark:bg-[#111113]" style={toolbarBottomFade}>
          <div className="flex w-full min-w-0 items-center justify-start gap-2 text-sm font-semibold text-black/78 dark:text-white/82">
            <h1 className="truncate text-left">{activeChat.title || "Untitled chat"}</h1>
          </div>
        </header>

        <Conversation className="min-h-0 bg-white dark:bg-[#111113]" style={chatBottomFade}>
          <ConversationContent className="mx-auto w-full max-w-[760px] gap-0 px-4 pb-12 pt-8 sm:px-6" scrollClassName="overflow-y-auto">
            <div className="flex flex-col gap-4 px-3 pb-2 pt-6">
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
                      title: selectedVersion.artifact?.title || "Generated content",
                      updatedAt: selectedVersion.updatedAt,
                    } : null,
                    createdAt: selectedVersion.createdAt,
                    updatedAt: selectedVersion.updatedAt,
                  } : null;

                  return (
                    <div key={turn.id} className="space-y-4">
                      <Message from="user">
                        <MessageContent className="max-w-[min(680px,92%)]">
                          <p>{turn.userMessage}</p>
                        </MessageContent>
                      </Message>
                      {selectedVersion && (
                        <AgentActivityDetail
                          run={runForDetail}
                          phase={runForDetail && selectedProgress?.runId === runForDetail.id ? selectedProgress.phase : null}
                          busy={isLatestTurn && (agent.agentBusy || agent.isPendingRun)}
                          error={isLatestTurn ? agent.agentError : ""}
                          instruction={selectedVersion.instruction || turn.userMessage}
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
                          artifactAction={selectedVersion.agentRunId === selectedProgress?.runId && showPreview ? previewAction : selectedVersion.status === "SUCCEEDED" && selectedVersion.artifactId ? (
                            <button
                              ref={selectedVersion.agentRunId === agent.selectedActivity?.id ? artifactTriggerRef : undefined}
                              id={`artifact-preview-${selectedVersion.id}`}
                              type="button"
                              aria-controls="artifact-editor-panel"
                              aria-expanded={artifactPanelOpen && selectedVersion.artifactId === document.currentArtifact?.id}
                              onClick={() => {
                                setGenerationOpenedRunId(agent.agentRun?.id ?? null);
                                agent.selectVersion(turn.id, selectedVersion.id);
                                setArtifactPanelOpen(true);
                              }}
                              className="glass-control glass-card glass-primary flex w-full items-center justify-between gap-4 rounded-xl border px-4 py-3 text-left"
                            >
                              <span className="min-w-0 truncate text-sm font-medium text-black/82 dark:text-white/85">
                                {selectedVersion.artifact?.title || (selectedVersion.artifactId === document.currentArtifact?.id ? document.currentArtifact.title : "Generated content")}
                              </span>
                              <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-black/[0.035] px-3 py-1.5 text-xs font-medium text-black/50 dark:bg-white/[0.06] dark:text-white/55">
                                <Eye aria-hidden="true" className="size-3.5" />
                                Open content
                              </span>
                            </button>
                          ) : selectedVersion.agentRunId === selectedProgress?.runId ? previewAction : undefined}
                        />
                      )}
                    </div>
                  );
                })
              ) : (
                <>
                  {messages.map((message) => (
                    <Message key={message.id} from="user">
                      <MessageContent className="max-w-[min(680px,92%)]">
                        <p>{message.content}</p>
                      </MessageContent>
                    </Message>
                  ))}
                  <AgentActivityDetail
                    run={agent.agentRun ?? agent.selectedActivity}
                    phase={selectedProgress && (agent.agentRun ?? agent.selectedActivity)?.id === selectedProgress.runId ? selectedProgress.phase : null}
                    busy={agent.agentBusy}
                    error={agent.agentError}
                    instruction={agent.agentInstruction}
                    artifactAction={showPreview ? previewAction : document.currentArtifact ? (
                      <button
                        ref={artifactTriggerRef}
                        id="artifact-preview"
                        type="button"
                        aria-controls="artifact-editor-panel"
                        aria-expanded={artifactPanelOpen}
                        onClick={() => setArtifactPanelOpen(true)}
                        className="glass-control glass-card glass-primary flex w-full items-center justify-between gap-4 rounded-xl border px-4 py-3 text-left"
                      >
                        <span className="min-w-0 truncate text-sm font-medium text-black/82 dark:text-white/85">
                          {document.currentArtifact.title || "Generated content"}
                        </span>
                        <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-black/[0.035] px-3 py-1.5 text-xs font-medium text-black/50 dark:bg-white/[0.06] dark:text-white/55">
                          <Eye aria-hidden="true" className="size-3.5" />
                          Open content
                        </span>
                      </button>
                    ) : previewAction}
                  />
                </>
              )}
            </div>

            {document.artifactError ? <ErrorNotice message={document.artifactError} /> : null}
          </ConversationContent>
        </Conversation>

        <ChatComposer
          id="chat-composer"
          variant="dock"
          placeholder={agent.isPendingRun ? "Response in progress. Wait for it to finish..." : "Ask a follow-up..."}
          onSubmit={(message, skills, model, reasoningEffort) => {
            void agent.submitMessage(message, undefined, skills, model, reasoningEffort);
          }}
          busy={document.artifactLoading || agent.agentBusy || agent.activitiesLoading || agent.isPendingRun}
          canGenerate={canGenerate && !agent.isPendingRun}
        />
        {agent.isPendingRun && (
          <p className="mx-auto max-w-[720px] px-4 pt-1 text-center text-xs text-black/50 dark:text-white/50">
            Response in progress. Wait for it to finish before sending a follow-up.
          </p>
        )}
      </ResizablePanel>
      <ResizableHandle aria-label="Resize content panel" disabled={!desktopArtifactVisible} className={`w-px bg-black/[0.08] dark:bg-white/10 ${desktopArtifactVisible ? "flex" : "hidden"}`}/>
        <ResizablePanel id="artifact-pane" panelRef={artifactPanelRef} defaultSize="0%" minSize={desktopArtifactVisible ? `${panelMinimum}px` : "0%"} maxSize={desktop ? "1200px" : "100%"} groupResizeBehavior="preserve-pixel-size" style={{overflow:"visible",height:"100%"}} onResize={(size, _id, previousSize) => {if (desktopArtifactVisible && size.inPixels > 0 && (previousSize?.inPixels ?? 0) > 0) artifactSizeRef.current = size.inPixels;}}>
      {artifactPanelOpen && hasPanelContent ? (
        <aside
          id="artifact-editor-panel"
          aria-label="Content panel"
          aria-busy={showPreview && selectedProgress?.status !== "FAILED"}
          className="glass-layer absolute inset-0 z-30 flex h-full min-w-0 flex-col border-l border-black/[0.08] dark:border-white/10 lg:relative"
        >
          <header className="relative z-20 flex min-h-16 shrink-0 items-center justify-between gap-3 bg-[#fbfbf8]/85 px-4 backdrop-blur-xl dark:bg-[#16171a]/85">
            <div className="flex items-center gap-1">
              {!showPreview && shownArtifact ? (
                <ArtifactActionsMenu>
                  {() => <ExportDialog pack={shownArtifact} client={plotApiClient} presentation="menu" />}
                </ArtifactActionsMenu>
              ) : null}
              {showPreview ? <span role="status" className="text-sm text-black/60 dark:text-white/60">{selectedProgress?.status === "FAILED" ? "Generation failed · last draft" : selectedProgress?.status === "SUCCEEDED" ? "Loading final draft" : documentPhaseStatus(selectedProgress?.phase)}</span> : null}
            </div>
            <div className="flex items-center gap-2">
              <span className="hidden sm:inline">
                <ArtifactEditorStatus>
                  {showPreview ? "Read-only draft" : artifactSaveStateLabel(document.saveState, false)}
                </ArtifactEditorStatus>
              </span>
              {!showPreview && document.currentArtifact && canEdit ? (
                <ArtifactSaveDraftButton
                  saving={document.saveState === "saving"}
                  onClick={() => setArtifactSaveRequestToken((value) => value + 1)}
                />
              ) : null}
              {!showPreview && shownArtifact && (canPublish || (shownArtifact.publication && canUnpublish)) ? (
                <PublishDialog pack={shownArtifact} client={plotApiClient} onPackChange={document.onPackChange} />
              ) : null}
              <button
                type="button"
                aria-label="Close content"
                onClick={() => {
                  setArtifactPanelOpen(false);
                  artifactTriggerRef.current?.focus();
                }}
                className="glass-button glass-icon inline-flex size-8 shrink-0 items-center justify-center"
              >
                <X aria-hidden="true" className="size-4" />
              </button>
            </div>
          </header>
          {!showPreview && shownArtifact ? (
            <div className="relative z-[9] shrink-0 bg-[#fbfbf8] px-6 pb-3 pt-1 dark:bg-[#18181b]">
              <div className="truncate text-[16px] font-medium leading-[22px] text-black/72 dark:text-white/76">{shownArtifact.title || "Generated content"}</div>
              {artifactMetrics ? (
                <div className="mt-1 text-[12px] leading-4 text-black/50 dark:text-white/52">{artifactMetrics.characters} characters · {artifactMetrics.words} words</div>
              ) : null}
            </div>
          ) : null}
          <div role="region" aria-label="Content body" className="relative min-h-0 flex-1 overflow-y-auto bg-[#fbfbf8] dark:bg-[#18181b]">
            <div aria-hidden="true" className="pointer-events-none sticky top-0 z-10 -mb-1.5 h-1.5 w-full bg-gradient-to-b from-[#fbfbf8] to-transparent dark:from-[#18181b]" />
            <div className="flex items-start justify-center">
              {showPreview ? (
                <div className="w-full max-w-[760px] space-y-5 px-6 py-8 text-sm leading-7 text-black/75 dark:text-white/78">
                  {selectedProgress?.draftParagraphs.map((body, index) => <p key={index} className="whitespace-pre-wrap">{body}</p>)}
                  {!selectedProgress?.draftParagraphs.length ? <p>Preparing the draft…</p> : null}
                </div>
              ) : document.currentArtifact ? <ArtifactDocumentSurface
                presentation="workspace"
                pack={document.currentArtifact}
                client={plotApiClient}
                initialDraft={document.drafts[document.currentArtifact.id]}
                saveState={document.saveState}
                saveRequestToken={artifactSaveRequestToken}
                editorLocked={!canEdit}
                onSaveStateChange={document.onSaveStateChange}
                onDraftChange={document.onDraftChange}
                onSaveArtifact={document.onSaveArtifact}
                onPackChange={document.onPackChange}
              /> : null}
            </div>
          </div>
        </aside>
      ) : null}
        </ResizablePanel>
      </ResizablePanelGroup>
    </div>
  );
}
