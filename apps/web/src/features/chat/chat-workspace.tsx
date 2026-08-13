"use client";

import Link from "next/link";
import { FileText, LoaderCircle, MoreHorizontal } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState, type Dispatch, type SetStateAction } from "react";

import type {
  ArtifactHistoryDetail,
  Artifact,
  ChatAgentRun,
  SourceReference,
  WorkSessionSummary as ChatSummary,
} from "@plot/api-client";
import { ArtifactDocumentSurface } from "@/features/artifacts/artifact-document-surface";
import { ArtifactHistoryPanel } from "@/features/citations/artifact-history-panel";
import type { SaveArtifactInput } from "@/features/citations/cited-draft-editor";
import { ChatComposer } from "@/features/chat/chat-composer";
import { isTerminalChatAgentStatus, pollChatAgentRun } from "@/lib/chat-agent-polling";
import { plotApiClient } from "@/lib/api-client";

export function ChatWorkspace() {
  return <Suspense fallback={null}><ChatWorkspaceContent /></Suspense>;
}

function ChatWorkspaceContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const requestedChatId = searchParams.get("chat");
  const [chats, setChats] = useState<ChatSummary[]>([]);
  const [references, setReferences] = useState<SourceReference[]>([]);
  const [workspaceRevision, setWorkspaceRevision] = useState(0);
  const [referencesLoading, setReferencesLoading] = useState(true);
  const [referencesError, setReferencesError] = useState("");

  useEffect(() => {
    function handleWorkspaceChanged() {
      setChats([]);
      setReferences([]);
      setReferencesError("");
      setReferencesLoading(true);
      setWorkspaceRevision((current) => current + 1);
      router.replace("/chat", { scroll: false });
    }

    window.addEventListener("plot:workspace-changed", handleWorkspaceChanged);
    return () => window.removeEventListener("plot:workspace-changed", handleWorkspaceChanged);
  }, [router]);

  useEffect(() => {
    const controller = new AbortController();
    void plotApiClient.listSessions({ signal: controller.signal })
      .then((value) => { if (!controller.signal.aborted) setChats(value); })
      .catch(() => undefined);
    void plotApiClient.listSourceReferences({ signal: controller.signal })
      .then((value) => { if (!controller.signal.aborted) setReferences(value); })
      .catch((error) => { if (!controller.signal.aborted) setReferencesError(messageFor(error, "Sources could not be loaded.")); })
      .finally(() => { if (!controller.signal.aborted) setReferencesLoading(false); });
    return () => controller.abort();
  }, [workspaceRevision]);

  const activeChat = requestedChatId ? chats.find((chat) => chat.id === requestedChatId) : null;
  if (activeChat) {
    return <ActiveChatWorkspace activeChat={activeChat} references={references} sourceError={referencesError} />;
  }

  return <ChatHome references={references} referencesLoading={referencesLoading} referencesError={referencesError} />;
}

function ChatHome({
  references,
  referencesLoading,
  referencesError,
}: {
  references: SourceReference[];
  referencesLoading: boolean;
  referencesError: string;
}) {
  const [startError, setStartError] = useState("");
  const [starting, setStarting] = useState(false);
  const pendingRequestRef = useRef<PendingAgentRequest | null>(null);

  async function submitHomeRequest(message: string, referenceIds: string[]) {
    const selected = selectReferences(references, referenceIds);
    const validationError = validateSourceSelection(references, selected, referencesError);
    if (validationError) {
      setStartError(validationError);
      return;
    }

    setStarting(true);
    setStartError("");
    const idempotencyKey = pendingAgentRequestKey(pendingRequestRef, message, selected.map((reference) => reference.id));
    try {
      const run = await plotApiClient.createChatAgentRun({
        instruction: message,
        writingBlockIds: selected.map((reference) => reference.id),
      }, idempotencyKey);
      pendingRequestRef.current = null;
      window.location.assign(chatHref(run.chatId, run.id));
    } catch (error) {
      if (isNonRetryableRequestError(error)) pendingRequestRef.current = null;
      setStartError(messageFor(error, "The request could not be started. Try again."));
      setStarting(false);
    }
  }

  return (
    <div className="flex h-screen min-h-0 flex-col bg-white dark:bg-[#111113]">
      <div className="flex min-h-0 flex-1 items-center justify-center px-6 pb-24 pt-16">
        <div className="w-full max-w-[760px]">
          <h1 className="text-center text-[28px] font-medium tracking-normal text-black/82 dark:text-white/88">What should Plot create?</h1>
          <div className="mt-9">
            <ChatComposer
              key={references.map((reference) => reference.id).join(":") || "no-references"}
              variant="center"
              placeholder="Ask for a changelog, customer update, or source-backed draft..."
              onSubmit={(message, ids) => void submitHomeRequest(message, ids)}
              references={toComposerReferences(references)}
              busy={starting || referencesLoading}
            />
          </div>
          {referencesLoading ? <p className="mt-3 text-center text-sm text-black/45 dark:text-white/45">Loading sources…</p> : null}
          {!referencesLoading && !referencesError && references.length === 0 ? <SourceEmptyState /> : null}
          {referencesError ? <ErrorNotice message={referencesError} /> : null}
          {startError ? <ErrorNotice message={startError} /> : null}
        </div>
      </div>
    </div>
  );
}

function ActiveChatWorkspace({ activeChat, references, sourceError }: { activeChat: ChatSummary; references: SourceReference[]; sourceError: string }) {
  const searchParams = useSearchParams();
  const router = useRouter();
  const requestedArtifactId = searchParams.get("artifact");
  const requestedAgentId = searchParams.get("agent");
  const [activities, setActivities] = useState<ChatAgentRun[]>([]);
  const [activitiesLoadedFor, setActivitiesLoadedFor] = useState<string | null>(null);
  const [activitiesError, setActivitiesError] = useState("");
  const [generatedArtifact, setGeneratedArtifact] = useState<Artifact | null>(null);
  const [historicalArtifact, setHistoricalArtifact] = useState<ArtifactHistoryDetail | null>(null);
  const [historicalPosition, setHistoricalPosition] = useState<number | null>(null);
  const [artifactError, setArtifactError] = useState("");
  const [artifactLoading, setArtifactLoading] = useState(false);
  const [agentRun, setAgentRun] = useState<ChatAgentRun | null>(null);
  const [agentError, setAgentError] = useState("");
  const [agentBusy, setAgentBusy] = useState(false);
  const [agentInstruction, setAgentInstruction] = useState("");
  const [saveState, setSaveState] = useState<"saved" | "saving" | "dirty" | "error">("saved");
  const [drafts, setDrafts] = useState<Record<string, Omit<SaveArtifactInput, "expectedRevisionNumber">>>({});
  const [mobilePanel, setMobilePanel] = useState<"assistant" | "history" | null>("assistant");
  const mobileAssistantTriggerRef = useRef<HTMLButtonElement>(null);
  const mobileHistoryTriggerRef = useRef<HTMLButtonElement>(null);
  const previousMobilePanelRef = useRef<"assistant" | "history" | null>(null);
  const previousArtifactIdRef = useRef<string | null>(null);
  const documentKeyRef = useRef("");
  const agentAbortRef = useRef<AbortController | null>(null);
  const pendingRequestRef = useRef<PendingAgentRequest | null>(null);
  const activitiesLoading = activitiesLoadedFor !== activeChat.id;

  useEffect(() => {
    const previous = previousMobilePanelRef.current;
    if (previous && mobilePanel === null) {
      (previous === "assistant" ? mobileAssistantTriggerRef : mobileHistoryTriggerRef).current?.focus();
    }
    previousMobilePanelRef.current = mobilePanel;
  }, [mobilePanel]);

  const selectedActivity = useMemo(() => {
    if (requestedAgentId) return activities.find((activity) => activity.id === requestedAgentId) ?? null;
    if (requestedArtifactId) {
      const artifactActivity = activities.find((activity) => activity.artifactId === requestedArtifactId);
      if (artifactActivity) return artifactActivity;
    }
    return activities[activities.length - 1] ?? null;
  }, [activities, requestedAgentId, requestedArtifactId]);

  useEffect(() => {
    const controller = new AbortController();
    void plotApiClient.listSessionAgentRuns(activeChat.id, { signal: controller.signal })
      .then((value) => {
        if (controller.signal.aborted) return;
        setActivities(value);
        setActivitiesError("");
        setActivitiesLoadedFor(activeChat.id);
      })
      .catch((error) => {
        if (controller.signal.aborted) return;
        setActivitiesError(messageFor(error, "Chat activity could not be loaded."));
        setActivitiesLoadedFor(activeChat.id);
      });
    return () => controller.abort();
  }, [activeChat.id]);

  useEffect(() => {
    if (!requestedAgentId) return;
    agentAbortRef.current?.abort();

    const controller = new AbortController();
    agentAbortRef.current = controller;
    queueMicrotask(() => {
      if (agentAbortRef.current !== controller) return;
      setAgentBusy(true);
      setAgentError("");
    });

    async function restoreAgent() {
      try {
        const current = await plotApiClient.getChatAgentRun(requestedAgentId!, { signal: controller.signal });
        if (agentAbortRef.current !== controller) return;
        if (current.chatId !== activeChat.id) throw new Error("That Agent request is not part of this chat.");
        setAgentRun(current);
        const restored = current.artifactId || isTerminalChatAgentStatus(current.status)
          ? current
          : await pollChatAgentRun(plotApiClient, current.id, {
              signal: controller.signal,
              initialRun: current,
              onUpdate: (next) => {
                if (agentAbortRef.current === controller) {
                  setAgentRun(next);
                  upsertActivity(setActivities, next);
                }
              },
            });
        if (agentAbortRef.current !== controller) return;
        setAgentRun(restored);
        if (restored.artifactId) {
          router.replace(chatHref(activeChat.id, restored.id, restored.artifactId), { scroll: false });
        }
      } catch (error) {
        if (agentAbortRef.current === controller && !(error instanceof DOMException && error.name === "AbortError")) {
          setAgentError(messageFor(error, "The Agent request could not be loaded."));
        }
      } finally {
        if (agentAbortRef.current === controller) setAgentBusy(false);
      }
    }

    void restoreAgent();
    return () => {
      controller.abort();
      if (agentAbortRef.current === controller) {
        agentAbortRef.current = null;
        setAgentBusy(false);
      }
    };
  }, [activeChat.id, requestedAgentId, router]);

  useEffect(() => {
    const requestedArtifactIdForEffect = selectedActivity?.artifactId ?? requestedArtifactId;
    if (!requestedArtifactIdForEffect) {
      queueMicrotask(() => {
        setGeneratedArtifact(null);
        setArtifactLoading(false);
        setArtifactError("");
      });
      return;
    }
    const artifactId = requestedArtifactIdForEffect;

    const controller = new AbortController();
    queueMicrotask(() => {
      if (controller.signal.aborted) return;
      setArtifactLoading(true);
      setArtifactError("");
      setGeneratedArtifact(null);
      setHistoricalArtifact(null);
      setHistoricalPosition(null);
      setSaveState("saved");
    });

    async function restoreArtifact() {
      try {
        const artifact = await plotApiClient.getArtifact(artifactId, { signal: controller.signal });
        if (controller.signal.aborted) return;
        setGeneratedArtifact(artifact);
      } catch (error) {
        if (!controller.signal.aborted && !(error instanceof DOMException && error.name === "AbortError")) {
          setArtifactError(messageFor(error, "The saved artifact could not be restored."));
        }
      } finally {
        if (!controller.signal.aborted) setArtifactLoading(false);
      }
    }
    void restoreArtifact();
    return () => {
      controller.abort();
    };
  }, [requestedArtifactId, selectedActivity?.artifactId]);

  const selectActivity = useCallback((activity: ChatAgentRun) => {
    setHistoricalArtifact(null);
    setHistoricalPosition(null);
    if (!activity.artifactId) setMobilePanel("assistant");
    router.replace(chatHref(activeChat.id, activity.id, activity.artifactId), { scroll: false });
  }, [activeChat.id, router]);

  async function submitMessage(message: string, referenceIds: string[]) {
    const selected = selectReferences(references, referenceIds);
    const validationError = validateSourceSelection(references, selected, sourceError);
    if (validationError) {
      setAgentError(validationError);
      return;
    }
    agentAbortRef.current?.abort();
    const controller = new AbortController();
    agentAbortRef.current = controller;
    const idempotencyKey = pendingAgentRequestKey(pendingRequestRef, message, selected.map((reference) => reference.id));
    setAgentInstruction(message);
    setAgentRun(null);
    setAgentBusy(true);
    setAgentError("");
    setGeneratedArtifact(null);
    setHistoricalArtifact(null);
    setHistoricalPosition(null);
    let admitted = false;
    try {
      const run = await plotApiClient.createChatAgentRun({
        instruction: message,
        writingBlockIds: selected.map((reference) => reference.id),
        workSessionId: activeChat.id,
      }, idempotencyKey, { signal: controller.signal });
      if (agentAbortRef.current !== controller || controller.signal.aborted) return;
      admitted = true;
      pendingRequestRef.current = null;
      setAgentRun(run);
      router.replace(chatHref(activeChat.id, run.id), { scroll: false });
    } catch (error) {
      if (agentAbortRef.current === controller && !(error instanceof DOMException && error.name === "AbortError")) {
        if (isNonRetryableRequestError(error)) pendingRequestRef.current = null;
        setAgentError(messageFor(error, "The request could not be started."));
      }
    } finally {
      if (agentAbortRef.current === controller && !admitted) setAgentBusy(false);
    }
  }

  const currentArtifact = historicalArtifact?.artifact ?? generatedArtifact;
  const currentArtifactId = currentArtifact?.id ?? null;
  const documentKey = `${historicalArtifact ? "history" : "current"}:${currentArtifactId ?? "none"}`;
  useEffect(() => {
    documentKeyRef.current = documentKey;
  }, [documentKey]);
  useEffect(() => {
    if (previousArtifactIdRef.current === currentArtifactId) return;
    previousArtifactIdRef.current = currentArtifactId;
    setSaveState(currentArtifactId && drafts[currentArtifactId] ? "dirty" : "saved");
  }, [currentArtifactId, drafts]);
  const messages: Array<{ id: string; role: "user"; timestamp: string; createdAt: string | null; content: string }> = activities
    .filter((activity) => activity.instruction)
    .map((activity) => ({
      id: activity.id,
      role: "user" as const,
      timestamp: formatActivity(activity.createdAt),
      createdAt: activity.createdAt,
      content: activity.instruction!,
    }));
  if (!messages.length) {
    messages.push({ id: activeChat.id, role: "user", timestamp: "Request", createdAt: null, content: activeChat.title || "Untitled request" });
  }

  return (
    <div className="flex h-screen min-h-0 bg-white dark:bg-[#111113]">
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-14 shrink-0 items-center gap-4 bg-white px-4 py-3 dark:bg-[#111113] sm:px-6 lg:px-8">
          <div className="flex min-w-0 items-center gap-2 text-sm font-semibold text-black/78 dark:text-white/82">
            <FileText aria-hidden="true" className="size-4 shrink-0 text-black/50 dark:text-white/50" />
            <h1 className="truncate">{activeChat.title || "Untitled chat"}</h1>
            <MoreHorizontal aria-hidden="true" className="size-4 shrink-0 text-black/45 dark:text-white/45" />
          </div>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-8 pt-4 sm:px-6 lg:px-8 lg:pt-6">
          <div className="mx-auto max-w-7xl space-y-5">
            <div className="min-w-0">
              <section aria-label="Chat conversation" className="mb-5 space-y-3">
                {messages.map((message) => (
                  <article key={message.id} className="flex justify-end">
                    <div className="max-w-[min(720px,92%)] rounded-[20px] bg-black/[0.055] px-4 py-3 text-sm leading-6 text-black/80 dark:bg-white/10 dark:text-white/82">
                      <p>{message.content}</p>
                      <time dateTime={message.createdAt ?? undefined} className="mt-1 block text-right text-xs text-black/40 dark:text-white/42">{message.timestamp}</time>
                    </div>
                  </article>
                ))}
              </section>
              <AgentActivityDetail run={agentRun} busy={agentBusy} error={agentError} instruction={agentInstruction} />
              {artifactError ? <ErrorNotice message={artifactError} /> : null}
              {currentArtifact ? (
                <ArtifactDocumentSurface
                  pack={currentArtifact}
                  historical={historicalArtifact}
                  client={plotApiClient}
                  initialDraft={historicalArtifact ? undefined : drafts[currentArtifact.id]}
                  saveState={saveState}
                  onSaveStateChange={(state) => {
                    if (documentKeyRef.current === documentKey) setSaveState(state);
                  }}
                  onDraftChange={(draft) => {
                    if (historicalArtifact || documentKeyRef.current !== documentKey) return;
                    setDrafts((current) => ({ ...current, [currentArtifact.id]: draft }));
                  }}
                  onSaveArtifact={(input) => plotApiClient.saveArtifactVariant(currentArtifact.variant.id, input)}
                  onPackChange={(next) => {
                    setDrafts((current) => {
                      const nextDrafts = { ...current };
                      delete nextDrafts[next.id];
                      return nextDrafts;
                    });
                    if (documentKeyRef.current !== documentKey) return;
                    setGeneratedArtifact(next);
                    setHistoricalArtifact(null);
                    setHistoricalPosition(null);
                  }}
                />
              ) : !activitiesLoading && !agentBusy && !agentRun ? (
                <EmptyArtifactState hasSelection={Boolean(selectedActivity)} />
              ) : null}
            </div>

            <div className="lg:hidden">
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
                  disabled={!currentArtifact}
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
                    activities={activities}
                    selectedActivityId={selectedActivity?.id ?? null}
                    loading={activitiesLoading}
                    error={activitiesError}
                    onSelect={selectActivity}
                  />
                </div>
              ) : null}
              {mobilePanel === "history" && currentArtifact ? (
                <div id="mobile-chat-history-panel" role="tabpanel" aria-label="History panel" className="mt-3 rounded-xl border border-black/10 bg-white p-4 dark:border-white/10 dark:bg-white/[0.04]">
                  <ArtifactHistoryPanel
                    variantId={currentArtifact.variant.id}
                    client={plotApiClient}
                    refreshKey={currentArtifact.variant.revisionId}
                    selectedPosition={historicalPosition}
                    onSelect={(detail, position) => {
                      if (documentKeyRef.current !== documentKey) return;
                      setHistoricalArtifact(detail);
                      setHistoricalPosition(position);
                    }}
                  />
                </div>
              ) : null}
            </div>
          </div>
        </div>
        <ChatComposer
          id="chat-composer"
          key={references.map((reference) => reference.id).join(":") || "no-references"}
          placeholder="Ask Plot to create another source-backed artifact..."
          onSubmit={(message, ids) => void submitMessage(message, ids)}
          references={toComposerReferences(references)}
          busy={artifactLoading || agentBusy || activitiesLoading}
        />
      </div>
    </div>
  );
}

function ChatActivityPanel({
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
          {activities.map((activity) => (
            <li key={activity.id}>
              <button
                type="button"
                aria-current={selectedActivityId === activity.id ? "true" : undefined}
                onClick={() => onSelect(activity)}
                className={`w-full rounded-lg border px-3 py-3 text-left transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 ${selectedActivityId === activity.id ? "border-black/25 bg-black/[0.04] dark:border-white/25 dark:bg-white/10" : "border-black/[0.08] hover:bg-black/[0.025] dark:border-white/10 dark:hover:bg-white/[0.06]"}`}
              >
                <span className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.07em] text-black/48 dark:text-white/52">
                  {!isTerminalChatAgentStatus(activity.status) ? <LoaderCircle aria-hidden="true" className="size-3.5 animate-spin" /> : null}
                  {agentStatusLabel(activity.status)}
                </span>
                <span className="mt-1 block truncate text-sm font-medium text-black/78 dark:text-white/82">{activity.artifact?.title || activity.instruction || "Agent request"}</span>
                <span className="mt-1 block text-xs text-black/42 dark:text-white/45">
                  {activity.artifact ? "Artifact available" : activity.status === "FAILED" ? "No artifact produced" : "Working; no artifact yet"} · {formatActivity(activity.createdAt)}
                </span>
              </button>
            </li>
          ))}
        </ol>
      ) : null}
    </section>
  );
}

function AgentActivityDetail({
  run,
  busy,
  error,
  instruction,
}: {
  run: ChatAgentRun | null;
  busy: boolean;
  error: string;
  instruction: string;
}) {
  if (!run && !busy && !error) return null;
  const status = run?.status ?? "QUEUED";
  const linkedArtifact = Boolean(run?.artifactId);
  return (
    <section aria-label="Agent request details" className="mb-5 space-y-3">
      <div className="rounded-xl border border-black/10 bg-black/[0.025] px-4 py-3 text-sm text-black/62 dark:border-white/10 dark:bg-white/[0.04] dark:text-white/62">
        <div className="flex items-center gap-2 font-medium text-black/75 dark:text-white/78">
          {!isTerminalChatAgentStatus(status) && !linkedArtifact ? <LoaderCircle aria-hidden="true" className="size-3.5 animate-spin" /> : null}
          <span>Agent · {agentStatusLabel(status)}</span>
        </div>
        <p className="mt-1 text-xs leading-5 text-black/48 dark:text-white/50">
          {linkedArtifact ? "Sources are collected. Plot is preparing the grounded artifact…" : instruction ? agentProgressLabel(status) : "Plot is preparing the request…"}
        </p>
      </div>
      {error ? <ErrorNotice message={error} /> : null}
      {run?.status === "FAILED" && !error ? <ErrorNotice message={`Agent stopped before an artifact was produced${run.failureCode ? ` (${run.failureCode})` : ""}. It remains available as chat activity.`} /> : null}
    </section>
  );
}

function EmptyArtifactState({ hasSelection }: { hasSelection: boolean }) {
  return <div className="rounded-xl border border-dashed border-black/10 bg-black/[0.02] px-4 py-6 text-sm leading-6 text-black/48 dark:border-white/10 dark:bg-white/[0.03] dark:text-white/52">{hasSelection ? "This Agent request is still working or did not produce an artifact. Review its activity above." : "Select an Agent request to inspect its artifact."}</div>;
}

function SourceEmptyState() {
  return <p className="mt-3 text-center text-sm text-black/50 dark:text-white/50">Connect and import a source in <Link href="/settings/integrations" className="text-[#2563eb] hover:underline dark:text-[#93c5fd]">Integrations</Link> or <Link href="/sources" className="text-[#2563eb] hover:underline dark:text-[#93c5fd]">Sources</Link> before starting a chat.</p>;
}

function ErrorNotice({ message }: { message: string }) {
  return <div role="alert" className="mt-3 rounded-xl border border-rose-300/60 bg-rose-50 px-4 py-3 text-sm text-rose-900 dark:border-rose-400/25 dark:bg-rose-400/[0.08] dark:text-rose-200">{message}</div>;
}

function toComposerReferences(references: SourceReference[]) {
  return references.map((reference) => ({ id: reference.id, label: `${reference.repositoryLabel} · ${reference.sourceLabel}`, available: true, groupId: reference.sourceScopeId }));
}

function selectReferences(references: SourceReference[], ids: string[]) {
  return references.filter((reference) => ids.includes(reference.id));
}

function validateSourceSelection(all: SourceReference[], selected: SourceReference[], sourceError: string) {
  if (sourceError) return sourceError;
  if (!all.length) return "Connect and import a source before starting a Chat.";
  return "";
}

function chatHref(chatId: string, agentRunId: string | null = null, artifactId: string | null = null) {
  const params = new URLSearchParams({ chat: chatId });
  if (agentRunId) params.set("agent", agentRunId);
  if (artifactId) params.set("artifact", artifactId);
  return `/chat?${params.toString()}`;
}

type PendingAgentRequest = { key: string; fingerprint: string };

function pendingAgentRequestKey(ref: { current: PendingAgentRequest | null }, instruction: string, writingBlockIds: string[]) {
  const fingerprint = `${instruction}\u0000${writingBlockIds.join("\u0000")}`;
  if (ref.current?.fingerprint === fingerprint) return ref.current.key;
  const next = { key: crypto.randomUUID(), fingerprint };
  ref.current = next;
  return next.key;
}

function isNonRetryableRequestError(error: unknown) {
  const status = error && typeof error === "object" && "status" in error ? error.status : null;
  return typeof status === "number" && status >= 400 && status < 500 && status !== 408 && status !== 429;
}

function formatActivity(value: string | null) {
  if (!value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function agentStatusLabel(status: ChatAgentRun["status"]) {
  const label = status.toLowerCase().replaceAll("_", " ");
  return label.charAt(0).toUpperCase() + label.slice(1);
}

function agentProgressLabel(status: ChatAgentRun["status"]) {
  if (status === "QUEUED") return "Queued to explore the connected sources…";
  if (status === "RUNNING") return "Reading the connected sources…";
  if (status === "SUCCEEDED") return "Source exploration is complete. Preparing the artifact…";
  return "Source exploration stopped before an artifact was produced.";
}

function upsertActivity(setActivities: Dispatch<SetStateAction<ChatAgentRun[]>>, next: ChatAgentRun) {
  setActivities((current) => {
    const existingIndex = current.findIndex((activity) => activity.id === next.id);
    if (existingIndex < 0) return [...current, next].sort(compareActivity);
    const updated = [...current];
    const existing = updated[existingIndex]!;
    updated[existingIndex] = {
      ...existing,
      ...next,
      createdAt: existing.createdAt || next.createdAt,
      updatedAt: next.updatedAt || existing.updatedAt,
      failureCode: next.failureCode ?? existing.failureCode,
      artifact: next.artifact ?? existing.artifact,
      instruction: next.instruction || existing.instruction,
    };
    return updated.sort(compareActivity);
  });
}

function compareActivity(left: ChatAgentRun, right: ChatAgentRun) {
  return left.createdAt.localeCompare(right.createdAt) || left.id.localeCompare(right.id);
}

function messageFor(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}
