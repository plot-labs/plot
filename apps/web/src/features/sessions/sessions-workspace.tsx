"use client";

import Link from "next/link";
import { FileText, MessageSquareText, MoreHorizontal } from "lucide-react";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef, useState } from "react";

import type { ContentPack, GenerationReference, GenerationRun, WorkSessionSummary } from "@/lib/api-client";
import { plotApiClient } from "@/lib/api-client";
import { createAndStreamGeneration, isTerminalGenerationStatus, streamGeneration } from "@/lib/generation-polling";
import { CitedDraftEditor } from "@/features/citations/cited-draft-editor";
import { ExportDialog } from "@/features/citations/export-dialog";
import { GenerationWorkLog } from "@/features/sessions/generation-work-log";
import { SessionComposer } from "@/features/sessions/session-composer";
import { SessionThread, type SessionMessage } from "@/features/sessions/session-thread";

export function SessionsWorkspace() {
  return <Suspense fallback={null}><SessionsWorkspaceContent /></Suspense>;
}

function SessionsWorkspaceContent() {
  const searchParams = useSearchParams();
  const requestedSessionId = searchParams.get("session");
  const [sessions, setSessions] = useState<WorkSessionSummary[]>([]);
  const [references, setReferences] = useState<GenerationReference[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [sessionsError, setSessionsError] = useState("");
  const [referencesLoading, setReferencesLoading] = useState(true);
  const [referencesError, setReferencesError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    void plotApiClient.listSessions({ signal: controller.signal })
      .then((value) => { if (!controller.signal.aborted) setSessions(value); })
      .catch((error) => { if (!controller.signal.aborted) setSessionsError(messageFor(error, "Sessions could not be loaded.")); })
      .finally(() => { if (!controller.signal.aborted) setSessionsLoading(false); });
    void plotApiClient.listGenerationReferences({ signal: controller.signal })
      .then((value) => { if (!controller.signal.aborted) setReferences(value); })
      .catch((error) => { if (!controller.signal.aborted) setReferencesError(messageFor(error, "Sources could not be loaded.")); })
      .finally(() => { if (!controller.signal.aborted) setReferencesLoading(false); });
    return () => controller.abort();
  }, []);

  const activeSession = requestedSessionId ? sessions.find((session) => session.id === requestedSessionId) : null;
  if (activeSession) {
    return <ActiveSessionWorkspace activeSession={activeSession} references={references} sourceError={referencesError} />;
  }

  return <SessionsHome
    sessions={sessions}
    references={references}
    sessionsLoading={sessionsLoading}
    sessionsError={sessionsError}
    referencesLoading={referencesLoading}
    referencesError={referencesError}
    onSessionCreated={(session) => setSessions((current) => [session, ...current])}
  />;
}

function SessionsHome({
  sessions,
  references,
  sessionsLoading,
  sessionsError,
  referencesLoading,
  referencesError,
  onSessionCreated,
}: {
  sessions: WorkSessionSummary[];
  references: GenerationReference[];
  sessionsLoading: boolean;
  sessionsError: string;
  referencesLoading: boolean;
  referencesError: string;
  onSessionCreated: (session: WorkSessionSummary) => void;
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
    let session: WorkSessionSummary;
    try {
      session = await plotApiClient.createSession({ title: message });
      onSessionCreated(session);
    } catch (error) {
      setStarting(false);
      setStartError(messageFor(error, "A session could not be created. Please try again."));
      return;
    }

    try {
      const run = await plotApiClient.createGeneration({
        sourceScopeId: selected[0]!.sourceScopeId,
        writingBlockIds: selected.map((reference) => reference.id),
        instruction: message,
      }, crypto.randomUUID());
      try {
        await plotApiClient.updateSession(session.id, { latestGenerationId: run.id });
      } catch {
        markSessionPointerRepair(session.id, run.id);
      }
      window.location.assign(sessionHref(session.id, run.id));
    } catch (error) {
      setStartError(messageFor(error, "The session was created, but generation could not start. Choose sources and try again."));
      setStarting(false);
    }
  }

  return (
    <div className="flex h-screen min-h-0 flex-col bg-white dark:bg-[#111113]">
      <div className="flex min-h-0 flex-1 items-center justify-center px-6 pb-24 pt-16">
        <div className="w-full max-w-[760px]">
          <h1 className="text-center text-[28px] font-medium tracking-normal text-black/82 dark:text-white/88">What should Plot create?</h1>
          <div className="mt-9">
            <SessionComposer
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
          {sessionsError ? <ErrorNotice message={sessionsError} /> : null}
          <div className="mx-auto mt-5 max-w-[720px] text-sm">
            {!sessionsLoading && !sessionsError && sessions.length === 0 ? <p className="px-3 py-3 text-black/42 dark:text-white/42">No sessions yet. Start with a source-backed request.</p> : null}
            {sessions.map((session) => (
              <Link key={session.id} href={sessionHref(session.id, session.latestGenerationId)} className="flex items-center gap-3 border-b border-black/[0.06] px-3 py-3 text-black/45 transition hover:text-black/70 dark:border-white/10 dark:text-white/45 dark:hover:text-white/75">
                <MessageSquareText className="size-4 shrink-0" />
                <span className="min-w-0 flex-1 truncate">{session.title || "Untitled session"}</span>
                <span className="text-xs text-black/32 dark:text-white/35">{formatActivity(session.lastActivityAt)}</span>
              </Link>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

function ActiveSessionWorkspace({ activeSession, references, sourceError }: { activeSession: WorkSessionSummary; references: GenerationReference[]; sourceError: string }) {
  const searchParams = useSearchParams();
  const requestedGenerationId = searchParams.get("generation") ?? activeSession.latestGenerationId;
  const [generationRun, setGenerationRun] = useState<GenerationRun | null>(null);
  const [generatedPack, setGeneratedPack] = useState<ContentPack | null>(null);
  const [generationError, setGenerationError] = useState("");
  const [generating, setGenerating] = useState(false);
  const generationAbortRef = useRef<AbortController | null>(null);
  const activeGenerationIdRef = useRef<string | null>(null);
  const sessionUpdateRef = useRef(new Set<string>());
  const messages: SessionMessage[] = [{
    id: activeSession.id,
    role: "user",
    timestamp: "Request",
    content: activeSession.title || "Untitled request",
  }];

  useEffect(() => {
    const generationId = requestedGenerationId;
    if (!generationId || activeGenerationIdRef.current === generationId) return;
    const controller = new AbortController();
    activeGenerationIdRef.current = generationId;
    generationAbortRef.current?.abort();
    generationAbortRef.current = controller;
    setGenerationRun(null);
    setGeneratedPack(null);
    setGenerating(true);
    setGenerationError("");

    async function restoreGeneration() {
      try {
        const current = await plotApiClient.getGeneration(generationId!, { signal: controller.signal });
        if (generationAbortRef.current !== controller) return;
        const repairRequested = consumeSessionPointerRepair(activeSession.id, current.id);
        if (activeSession.latestGenerationId !== current.id && repairRequested) {
          void plotApiClient.updateSession(activeSession.id, { latestGenerationId: current.id }).catch(() => undefined);
        }
        setGenerationRun(current);
        const restored = isTerminalGenerationStatus(current.status) ? current : await streamGeneration(plotApiClient, current.id, {
          signal: controller.signal,
          onUpdate: (next) => { if (generationAbortRef.current === controller) setGenerationRun(next); },
        });
        if (generationAbortRef.current !== controller) return;
        setGenerationRun(restored);
        setGeneratedPack(restored.contentPack);
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
  }, [activeSession.id, activeSession.latestGenerationId, requestedGenerationId]);

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
    setGeneratedPack(null);
    try {
      const run = await createAndStreamGeneration(plotApiClient, {
        sourceScopeId: selected[0]!.sourceScopeId,
        writingBlockIds: selected.map((reference) => reference.id),
        instruction: message,
      }, crypto.randomUUID(), {
        signal: controller.signal,
        onUpdate: (next) => {
          if (generationAbortRef.current !== controller || controller.signal.aborted) return;
          setGenerationRun(next);
          if (activeGenerationIdRef.current !== next.id) {
            activeGenerationIdRef.current = next.id;
            window.history.replaceState(null, "", sessionHref(activeSession.id, next.id));
          }
          const sessionUpdateKey = `${activeSession.id}:${next.id}`;
          if (!sessionUpdateRef.current.has(sessionUpdateKey)) {
            sessionUpdateRef.current.add(sessionUpdateKey);
            void plotApiClient.updateSession(activeSession.id, { title: message, latestGenerationId: next.id })
              .catch((error) => {
                if (generationAbortRef.current === controller) {
                  setGenerationError(messageFor(error, "The generation started, but this session could not be updated."));
                }
              });
          }
        },
      });
      if (generationAbortRef.current !== controller || controller.signal.aborted) return;
      setGenerationRun(run);
      setGeneratedPack(run.contentPack);
    } catch (error) {
      if (generationAbortRef.current === controller && !(error instanceof DOMException && error.name === "AbortError")) {
        setGenerationError(messageFor(error, "Generation could not be completed."));
      }
    } finally {
      if (generationAbortRef.current === controller) setGenerating(false);
    }
  }

  return (
    <div className="flex h-screen min-h-0 bg-white dark:bg-[#111113]">
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 shrink-0 items-center justify-between bg-white px-4 dark:bg-[#111113] sm:px-6 lg:px-8">
          <h1 className="sr-only">{activeSession.title || "Untitled session"}</h1>
          <div className="shell-session-heading flex min-w-0 items-center gap-2 text-sm font-semibold text-black/78 dark:text-white/82">
            <FileText className="size-4 shrink-0 text-black/50 dark:text-white/50" />
            <span className="truncate">{activeSession.title || "Untitled session"}</span>
            <MoreHorizontal className="size-4 shrink-0 text-black/45 dark:text-white/45" />
          </div>
        </header>
        <SessionThread messages={messages} generationPanel={<GenerationPanel run={generationRun} pack={generatedPack} busy={generating} error={generationError} onPackChange={setGeneratedPack} />} />
        <SessionComposer key={references.map((reference) => reference.id).join(":") || "no-references"} onSubmit={(message, ids) => void submitMessage(message, ids)} references={toComposerReferences(references)} busy={generating} />
      </div>
    </div>
  );
}

function GenerationPanel({ run, pack, busy, error, onPackChange }: { run: GenerationRun | null; pack: ContentPack | null; busy: boolean; error: string; onPackChange: (pack: ContentPack) => void }) {
  if (!run && !busy && !error) return null;
  return <article className="space-y-3">
    {run ? <p role="status" aria-label={`Generation status: ${generationStatusLabel(run.status)}`} className="sr-only">Generation status: {generationStatusLabel(run.status)}</p> : null}
    {run ? <GenerationWorkLog run={run} /> : busy ? <div className="rounded-xl border border-black/10 bg-black/[0.025] px-4 py-3 text-sm text-black/62 dark:border-white/10 dark:bg-white/[0.04] dark:text-white/62">Plot is preparing the grounded draft…</div> : null}
    {error ? <ErrorNotice message={error} /> : null}
    {run?.status === "FAILED" && !error ? <ErrorNotice message={`Generation stopped before a reviewable draft was produced${run.failureCode ? ` (${run.failureCode})` : ""}. Try again or adjust the selected references.`} /> : null}
    {run?.status === "NEEDS_REVIEW" && run.failureCode && !error ? <ErrorNotice message={`Review failed (${run.failureCode}). The latest draft is preserved, but its failed revision has not been verified.`} /> : null}
    {run?.status === "NEEDS_YOUR_CALL" && !error ? <ErrorNotice message="This saved draft predates automatic conflict handling. Generate it again to receive a single resolved result." /> : null}
    {pack ? <><CitedDraftEditor pack={pack} onEditSentence={(sentence, body) => plotApiClient.editSentence(pack.variant.id, sentence.id, { expectedRevisionNumber: sentence.revisionNumber, body })} onPackChange={onPackChange} /><ExportDialog pack={pack} client={plotApiClient} /></> : null}
  </article>;
}

function SourceEmptyState() {
  return <p className="mt-3 text-center text-sm text-black/50 dark:text-white/50">Connect and import a source in <Link href="/integrations" className="text-[#2563eb] hover:underline dark:text-[#93c5fd]">Integrations</Link> or <Link href="/sources" className="text-[#2563eb] hover:underline dark:text-[#93c5fd]">Sources</Link> before starting a session.</p>;
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

function sessionHref(sessionId: string, generationId: string | null) {
  const params = new URLSearchParams({ session: sessionId });
  if (generationId) params.set("generation", generationId);
  return `/sessions?${params.toString()}`;
}

function markSessionPointerRepair(sessionId: string, generationId: string) {
  try {
    window.sessionStorage.setItem(sessionPointerRepairKey(sessionId), generationId);
  } catch {
    // Navigation still restores the real generation when storage is unavailable.
  }
}

function consumeSessionPointerRepair(sessionId: string, generationId: string) {
  try {
    const key = sessionPointerRepairKey(sessionId);
    if (window.sessionStorage.getItem(key) !== generationId) return false;
    window.sessionStorage.removeItem(key);
    return true;
  } catch {
    return false;
  }
}

function sessionPointerRepairKey(sessionId: string) {
  return `plot.session-pointer-repair:${sessionId}`;
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

function messageFor(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}
