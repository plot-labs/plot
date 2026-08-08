"use client";

import Link from "next/link";
import { FileText, LoaderCircle, MoreHorizontal } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState, type Dispatch, type SetStateAction } from "react";

import type {
  ArtifactHistoryDetail,
  Artifact,
  GenerationReference,
  GenerationRun,
  SessionGeneration as ChatGeneration,
  WorkSessionSummary as ChatSummary,
} from "@plot/api-client";
import { ArtifactDocumentSurface } from "@/features/artifacts/artifact-document-surface";
import { ArtifactHistoryPanel } from "@/features/citations/artifact-history-panel";
import type { SaveArtifactInput } from "@/features/citations/cited-draft-editor";
import { GenerationWorkLog } from "@/features/chat/generation-work-log";
import { ChatComposer } from "@/features/chat/chat-composer";
import { isTerminalGenerationStatus, pollGeneration } from "@/lib/generation-polling";
import { plotApiClient } from "@/lib/api-client";

export function ChatWorkspace() {
  return <Suspense fallback={null}><ChatWorkspaceContent /></Suspense>;
}

function ChatWorkspaceContent() {
  const searchParams = useSearchParams();
  const requestedChatId = searchParams.get("chat");
  const [chats, setChats] = useState<ChatSummary[]>([]);
  const [references, setReferences] = useState<GenerationReference[]>([]);
  const [referencesLoading, setReferencesLoading] = useState(true);
  const [referencesError, setReferencesError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    void plotApiClient.listSessions({ signal: controller.signal })
      .then((value) => { if (!controller.signal.aborted) setChats(value); })
      .catch(() => undefined);
    void plotApiClient.listGenerationReferences({ signal: controller.signal })
      .then((value) => { if (!controller.signal.aborted) setReferences(value); })
      .catch((error) => { if (!controller.signal.aborted) setReferencesError(messageFor(error, "Sources could not be loaded.")); })
      .finally(() => { if (!controller.signal.aborted) setReferencesLoading(false); });
    return () => controller.abort();
  }, []);

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
  references: GenerationReference[];
  referencesLoading: boolean;
  referencesError: string;
}) {
  const [startError, setStartError] = useState("");
  const [starting, setStarting] = useState(false);

  async function submitHomeRequest(message: string, referenceIds: string[]) {
    const selected = selectReferences(references, referenceIds);
    const validationError = validateGenerationSelection(references, selected, referencesError);
    if (validationError) {
      setStartError(validationError);
      return;
    }

    setStarting(true);
    setStartError("");
    let chat: ChatSummary;
    try {
      chat = await plotApiClient.createSession({ title: message });
    } catch (error) {
      setStarting(false);
      setStartError(messageFor(error, "A chat could not be created. Please try again."));
      return;
    }

    try {
      const run = await plotApiClient.createGeneration({
        sourceScopeId: selected[0]!.sourceScopeId,
        writingBlockIds: selected.map((reference) => reference.id),
        instruction: message,
        workSessionId: chat.id,
      }, crypto.randomUUID());
      window.location.assign(chatHref(chat.id, run.id));
    } catch (error) {
      setStartError(messageFor(error, "The chat was created, but generation could not start. Choose sources and try again."));
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

function ActiveChatWorkspace({ activeChat, references, sourceError }: { activeChat: ChatSummary; references: GenerationReference[]; sourceError: string }) {
  const searchParams = useSearchParams();
  const router = useRouter();
  const requestedGenerationId = searchParams.get("generation");
  const requestedArtifactId = searchParams.get("artifact");
  const [activities, setActivities] = useState<ChatGeneration[]>([]);
  const [activitiesLoadedFor, setActivitiesLoadedFor] = useState<string | null>(null);
  const [activitiesError, setActivitiesError] = useState("");
  const [generationRun, setGenerationRun] = useState<GenerationRun | null>(null);
  const [generatedArtifact, setGeneratedArtifact] = useState<Artifact | null>(null);
  const [historicalArtifact, setHistoricalArtifact] = useState<ArtifactHistoryDetail | null>(null);
  const [historicalPosition, setHistoricalPosition] = useState<number | null>(null);
  const [generationError, setGenerationError] = useState("");
  const [generating, setGenerating] = useState(false);
  const [saveState, setSaveState] = useState<"saved" | "saving" | "dirty" | "error">("saved");
  const [drafts, setDrafts] = useState<Record<string, Omit<SaveArtifactInput, "expectedRevisionNumber">>>({});
  const [mobilePanel, setMobilePanel] = useState<"assistant" | "history" | null>("assistant");
  const mobileAssistantTriggerRef = useRef<HTMLButtonElement>(null);
  const mobileHistoryTriggerRef = useRef<HTMLButtonElement>(null);
  const previousMobilePanelRef = useRef<"assistant" | "history" | null>(null);
  const previousArtifactIdRef = useRef<string | null>(null);
  const documentKeyRef = useRef("");
  const generationAbortRef = useRef<AbortController | null>(null);
  const activeGenerationIdRef = useRef<string | null>(null);
  const activitiesLoading = activitiesLoadedFor !== activeChat.id;

  useEffect(() => {
    const previous = previousMobilePanelRef.current;
    if (previous && mobilePanel === null) {
      (previous === "assistant" ? mobileAssistantTriggerRef : mobileHistoryTriggerRef).current?.focus();
    }
    previousMobilePanelRef.current = mobilePanel;
  }, [mobilePanel]);

  const selectedActivity = useMemo(() => {
    if (requestedArtifactId) {
      const artifactActivity = activities.find((activity) => activity.artifact?.id === requestedArtifactId);
      if (artifactActivity) return artifactActivity;
    }
    if (requestedGenerationId) return activities.find((activity) => activity.id === requestedGenerationId) ?? null;
    return activities.find((activity) => activity.id === activeChat.latestGenerationId) ?? activities[0] ?? null;
  }, [activities, activeChat.latestGenerationId, requestedArtifactId, requestedGenerationId]);

  useEffect(() => {
    const controller = new AbortController();
    void plotApiClient.listSessionGenerations(activeChat.id, { signal: controller.signal })
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
    const generationId = selectedActivity?.id ?? requestedGenerationId ?? (requestedArtifactId ? null : activeChat.latestGenerationId);
    if (!generationId) {
      queueMicrotask(() => {
        setGenerationRun(null);
        setGeneratedArtifact(null);
        setGenerating(false);
      });
      return;
    }
    if (activeGenerationIdRef.current === generationId) return;

    const controller = new AbortController();
    activeGenerationIdRef.current = generationId;
    generationAbortRef.current?.abort();
    generationAbortRef.current = controller;
    queueMicrotask(() => {
      if (generationAbortRef.current !== controller) return;
      setGenerationRun(null);
      setGeneratedArtifact(null);
      setHistoricalArtifact(null);
      setHistoricalPosition(null);
      setGenerating(true);
      setGenerationError("");
      setSaveState("saved");
    });

    async function restoreGeneration() {
      try {
        const current = await plotApiClient.getGeneration(generationId!, { signal: controller.signal });
        if (generationAbortRef.current !== controller) return;
        if (current.workSessionId !== activeChat.id) {
          throw new Error("That generation is not part of this chat.");
        }
        setGenerationRun(current);
        upsertActivity(setActivities, activityForRun(current));
        const restored = isTerminalGenerationStatus(current.status) ? current : await pollGeneration(plotApiClient, current.id, {
          signal: controller.signal,
          initialRun: current,
          onUpdate: (next) => {
            if (generationAbortRef.current !== controller) return;
            setGenerationRun(next);
            upsertActivity(setActivities, activityForRun(next));
          },
        });
        if (generationAbortRef.current !== controller) return;
        setGenerationRun(restored);
        setGeneratedArtifact(restored.artifact);
        upsertActivity(setActivities, activityForRun(restored));
      } catch (error) {
        if (generationAbortRef.current === controller && !(error instanceof DOMException && error.name === "AbortError")) {
          setGenerationError(messageFor(error, "The saved generation could not be restored."));
        }
      } finally {
        if (generationAbortRef.current === controller) setGenerating(false);
      }
    }
    void restoreGeneration();
    return () => {
      controller.abort();
      if (generationAbortRef.current === controller) {
        generationAbortRef.current = null;
        activeGenerationIdRef.current = null;
      }
    };
  }, [activeChat.id, activeChat.latestGenerationId, requestedArtifactId, requestedGenerationId, selectedActivity?.id]);

  const selectActivity = useCallback((activity: ChatGeneration) => {
    setHistoricalArtifact(null);
    setHistoricalPosition(null);
    if (!activity.artifact) setMobilePanel("assistant");
    router.replace(chatHref(activeChat.id, activity.id, activity.artifact?.id ?? null), { scroll: false });
  }, [activeChat.id, router]);

  async function submitMessage(message: string, referenceIds: string[]) {
    const selected = selectReferences(references, referenceIds);
    const validationError = validateGenerationSelection(references, selected, sourceError);
    if (validationError) {
      setGenerationError(validationError);
      return;
    }
    generationAbortRef.current?.abort();
    const controller = new AbortController();
    generationAbortRef.current = controller;
    setGenerating(true);
    setGenerationError("");
    setGenerationRun(null);
    setGeneratedArtifact(null);
    setHistoricalArtifact(null);
    setHistoricalPosition(null);
    try {
      const run = await plotApiClient.createGeneration({
        sourceScopeId: selected[0]!.sourceScopeId,
        writingBlockIds: selected.map((reference) => reference.id),
        instruction: message,
        workSessionId: activeChat.id,
      }, crypto.randomUUID(), { signal: controller.signal });
      if (generationAbortRef.current !== controller || controller.signal.aborted) return;
      upsertActivity(setActivities, activityForRun(run, message));
      router.replace(chatHref(activeChat.id, run.id, run.artifact?.id ?? null), { scroll: false });
    } catch (error) {
      if (generationAbortRef.current === controller && !(error instanceof DOMException && error.name === "AbortError")) {
        setGenerationError(messageFor(error, "Generation could not be started."));
      }
    } finally {
      if (generationAbortRef.current === controller) setGenerating(false);
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
              <GenerationActivityDetail run={generationRun} busy={generating} error={generationError} />
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
                    setGenerationRun((current) => current ? { ...current, artifact: next } : current);
                    setHistoricalArtifact(null);
                    setHistoricalPosition(null);
                  }}
                />
              ) : !activitiesLoading ? (
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
          busy={generating || activitiesLoading}
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
  activities: ChatGeneration[];
  selectedActivityId: string | null;
  loading: boolean;
  error: string;
  onSelect: (activity: ChatGeneration) => void;
}) {
  const artifacts = activities.filter((activity) => activity.artifact);
  return (
    <section aria-label="Assistant" className="rounded-xl border border-black/10 bg-white p-4 dark:border-white/10 dark:bg-white/[0.04] sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.08em] text-black/42 dark:text-white/45">Assistant</div>
          <h2 className="mt-1 text-sm font-semibold text-black/82 dark:text-white/88">Chat activity</h2>
          <p className="mt-1 text-xs leading-5 text-black/48 dark:text-white/52">Generations stay here while they work, fail, or produce an artifact.</p>
        </div>
        {artifacts.length ? <span className="text-xs text-black/42 dark:text-white/45">{artifacts.length} artifact{artifacts.length === 1 ? "" : "s"}</span> : null}
      </div>
      {loading ? <p className="mt-4 text-sm text-black/45 dark:text-white/48">Loading chat activity…</p> : null}
      {error ? <ErrorNotice message={error} /> : null}
      {!loading && !activities.length && !error ? <p className="mt-4 text-sm text-black/48 dark:text-white/48">No generations yet. Start with a source-backed request below.</p> : null}
      {artifacts.length ? (
        <div className="mt-4" aria-label="Artifact selector">
          <div className="mb-2 text-xs font-semibold text-black/55 dark:text-white/58">Artifacts in this chat</div>
          <div className="flex min-w-0 gap-2 overflow-x-auto pb-1" role="listbox" aria-label="Artifacts in this chat">
            {artifacts.map((activity) => (
              <button
                key={activity.artifact!.id}
                type="button"
                role="option"
                aria-selected={selectedActivityId === activity.id}
                onClick={() => onSelect(activity)}
                className={`min-w-40 max-w-56 shrink-0 rounded-lg border px-3 py-2 text-left text-sm transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 ${selectedActivityId === activity.id ? "border-black/25 bg-black/[0.04] dark:border-white/25 dark:bg-white/10" : "border-black/[0.08] hover:bg-black/[0.025] dark:border-white/10 dark:hover:bg-white/[0.06]"}`}
              >
                <span className="block truncate font-medium text-black/78 dark:text-white/82">{activity.artifact!.title || "Generated artifact"}</span>
                <span className="mt-1 block text-xs text-black/42 dark:text-white/45">{generationStatusLabel(activity.status)}</span>
              </button>
            ))}
          </div>
        </div>
      ) : null}
      {activities.length ? (
        <ol className="mt-4 grid gap-2 md:grid-cols-2 xl:grid-cols-3" aria-label="Generations in this chat">
          {activities.map((activity) => (
            <li key={activity.id}>
              <button
                type="button"
                aria-current={selectedActivityId === activity.id ? "true" : undefined}
                onClick={() => onSelect(activity)}
                className={`w-full rounded-lg border px-3 py-3 text-left transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 ${selectedActivityId === activity.id ? "border-black/25 bg-black/[0.04] dark:border-white/25 dark:bg-white/10" : "border-black/[0.08] hover:bg-black/[0.025] dark:border-white/10 dark:hover:bg-white/[0.06]"}`}
              >
                <span className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.07em] text-black/48 dark:text-white/52">
                  {isActiveGenerationStatus(activity.status) ? <LoaderCircle aria-hidden="true" className="size-3.5 animate-spin" /> : null}
                  {generationStatusLabel(activity.status)}
                </span>
                <span className="mt-1 block truncate text-sm font-medium text-black/78 dark:text-white/82">{activity.artifact?.title || activity.instruction || "Generation"}</span>
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

function GenerationActivityDetail({ run, busy, error }: { run: GenerationRun | null; busy: boolean; error: string }) {
  if (!run && !busy && !error) return null;
  return (
    <section aria-label="Selected generation details" className="mb-5 space-y-3">
      {run ? <GenerationWorkLog run={run} /> : busy ? <div className="rounded-xl border border-black/10 bg-black/[0.025] px-4 py-3 text-sm text-black/62 dark:border-white/10 dark:bg-white/[0.04] dark:text-white/62">Plot is preparing the grounded artifact…</div> : null}
      {error ? <ErrorNotice message={error} /> : null}
      {run?.status === "FAILED" && !error ? <ErrorNotice message={`Generation stopped before a reviewable artifact was produced${run.failureCode ? ` (${run.failureCode})` : ""}. It remains available as chat activity.`} /> : null}
      {run?.status === "NEEDS_REVIEW" && run.failureCode && !error ? <ErrorNotice message={`Source review needs attention (${run.failureCode}). The artifact is preserved, but its review is incomplete.`} /> : null}
    </section>
  );
}

function EmptyArtifactState({ hasSelection }: { hasSelection: boolean }) {
  return <div className="rounded-xl border border-dashed border-black/10 bg-black/[0.02] px-4 py-6 text-sm leading-6 text-black/48 dark:border-white/10 dark:bg-white/[0.03] dark:text-white/52">{hasSelection ? "This generation is still working or did not produce an artifact. Review its activity above." : "Select a generation to inspect its artifact."}</div>;
}

function SourceEmptyState() {
  return <p className="mt-3 text-center text-sm text-black/50 dark:text-white/50">Connect and import a source in <Link href="/settings/integrations" className="text-[#2563eb] hover:underline dark:text-[#93c5fd]">Integrations</Link> or <Link href="/sources" className="text-[#2563eb] hover:underline dark:text-[#93c5fd]">Sources</Link> before starting a chat.</p>;
}

function ErrorNotice({ message }: { message: string }) {
  return <div role="alert" className="mt-3 rounded-xl border border-rose-300/60 bg-rose-50 px-4 py-3 text-sm text-rose-900 dark:border-rose-400/25 dark:bg-rose-400/[0.08] dark:text-rose-200">{message}</div>;
}

function toComposerReferences(references: GenerationReference[]) {
  return references.map((reference) => ({ id: reference.id, label: `${reference.repositoryLabel} · ${reference.sourceLabel}`, available: true, groupId: reference.sourceScopeId }));
}

function selectReferences(references: GenerationReference[], ids: string[]) {
  return references.filter((reference) => ids.includes(reference.id));
}

function validateGenerationSelection(all: GenerationReference[], selected: GenerationReference[], sourceError: string) {
  if (sourceError) return sourceError;
  if (!all.length) return "Connect and import a source before starting a generation.";
  if (!selected.length) return "Select at least one reference before starting a generation.";
  if (selected.some((reference) => reference.sourceScopeId !== selected[0]!.sourceScopeId)) return "Selected references must belong to the same source scope.";
  return "";
}

function chatHref(chatId: string, generationId: string | null, artifactId: string | null = null) {
  const params = new URLSearchParams({ chat: chatId });
  if (generationId) params.set("generation", generationId);
  if (artifactId) params.set("artifact", artifactId);
  return `/chat?${params.toString()}`;
}

function formatActivity(value: string | null) {
  if (!value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function generationStatusLabel(status: GenerationRun["status"]) {
  const label = status.toLowerCase().replaceAll("_", " ");
  return label.charAt(0).toUpperCase() + label.slice(1);
}

function isActiveGenerationStatus(status: GenerationRun["status"]) {
  return !isTerminalGenerationStatus(status);
}

function activityForRun(run: GenerationRun, instruction: string | null = null): ChatGeneration {
  const createdAt = run.timing?.createdAt ?? new Date().toISOString();
  const completedAt = run.timing?.finishedAt ?? null;
  return {
    id: run.id,
    status: run.status,
    instruction,
    createdAt,
    completedAt,
    failureCode: run.failureCode,
    artifact: run.artifact
      ? {
          id: run.artifact.id,
          generationRunId: run.artifact.generationRunId,
          status: run.artifact.status,
          title: run.artifact.title,
          updatedAt: completedAt ?? createdAt,
        }
      : null,
  };
}

function upsertActivity(setActivities: Dispatch<SetStateAction<ChatGeneration[]>>, next: ChatGeneration) {
  setActivities((current) => {
    const existingIndex = current.findIndex((activity) => activity.id === next.id);
    if (existingIndex < 0) return [...current, next].sort(compareActivity);
    const updated = [...current];
    const existing = updated[existingIndex]!;
    updated[existingIndex] = {
      ...existing,
      ...next,
      createdAt: existing.createdAt || next.createdAt,
      completedAt: next.completedAt ?? existing.completedAt,
      failureCode: next.failureCode ?? existing.failureCode,
      artifact: next.artifact ?? existing.artifact,
      instruction: next.instruction ?? existing.instruction ?? null,
    };
    return updated.sort(compareActivity);
  });
}

function compareActivity(left: ChatGeneration, right: ChatGeneration) {
  return left.createdAt.localeCompare(right.createdAt) || left.id.localeCompare(right.id);
}

function messageFor(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}
