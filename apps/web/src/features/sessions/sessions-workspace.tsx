"use client";

import Link from "next/link";
import {
  FileText,
  GitPullRequest,
  MessageSquareText,
  MoreHorizontal,
  PanelRightOpen,
  SlidersHorizontal,
} from "lucide-react";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useRef, useState } from "react";

import {
  createDemoAgentReply,
  getSelectedDocument,
  getSessionsWorkspace,
  type SelectedDocument,
  type SessionMessage,
  type WorkSession,
  type ContentPack,
  type GenerationRun,
  type GenerationReference,
  plotApiClient,
} from "@/lib/api-client";
import { createAndPollGeneration, pollGeneration } from "@/lib/generation-polling";
import { CitedDraftEditor } from "@/features/citations/cited-draft-editor";
import { ExportDialog } from "@/features/citations/export-dialog";
import { InterventionPanel } from "@/features/citations/intervention-panel";
import { SessionComposer } from "@/features/sessions/session-composer";
import { SessionSidePanel } from "@/features/sessions/session-side-panel";
import { SessionThread } from "@/features/sessions/session-thread";

export function SessionsWorkspace() {
  return (
    <Suspense fallback={null}>
      <SessionsWorkspaceContent />
    </Suspense>
  );
}

function SessionsWorkspaceContent() {
  const data = getSessionsWorkspace();
  const searchParams = useSearchParams();
  const requestedSessionId = searchParams.get("session");
  const activeSession = requestedSessionId
    ? data.sessions.find((session) => session.id === requestedSessionId)
    : null;

  if (!activeSession) {
    return <SessionsHome data={data} />;
  }

  return (
    <ActiveSessionWorkspace
      key={activeSession.id}
      activeSession={activeSession}
      data={data}
    />
  );
}

function SessionsHome({ data }: { data: ReturnType<typeof getSessionsWorkspace> }) {
  const [submittedRequests, setSubmittedRequests] = useState<string[]>([]);
  const recentRows = [
    ...submittedRequests.map((request) => ({
      id: `request-${request}`,
      href: "/sessions",
      icon: MessageSquareText,
      label: request,
      meta: "Just now",
    })),
    ...data.sessions.map((session) => ({
      id: session.id,
      href: `/sessions?session=${session.id}`,
      icon: MessageSquareText,
      label: session.subtitle,
      meta: session.updatedAt,
    })),
    ...data.references.slice(0, 2).map((reference) => ({
      id: reference.id,
      href: `/sources`,
      icon: GitPullRequest,
      label: `Review ${reference.label}: ${reference.title}`,
      meta: reference.date,
    })),
  ].slice(0, 5);

  function submitHomeRequest(message: string) {
    setSubmittedRequests((current) => [message, ...current].slice(0, 3));
  }

  return (
    <div className="flex h-screen min-h-0 flex-col bg-white dark:bg-[#111113]">
      <div className="flex min-h-0 flex-1 items-center justify-center px-6 pb-24 pt-16">
        <div className="w-full max-w-[760px]">
          <h1 className="text-center text-[28px] font-medium tracking-normal text-black/82 dark:text-white/88">
            What should Plot create?
          </h1>

          <div className="mt-9">
            <SessionComposer
              variant="center"
              placeholder="Ask for a changelog, customer update, or source-backed draft..."
              onSubmit={submitHomeRequest}
            />
          </div>

          <div className="mx-auto mt-5 max-w-[720px] text-sm">
            {recentRows.map((row) => {
              const Icon = row.icon;

              return (
                <Link
                  key={row.id}
                  href={row.href}
                  className="flex items-center gap-3 border-b border-black/[0.06] px-3 py-3 text-black/45 transition hover:text-black/70 dark:border-white/10 dark:text-white/45 dark:hover:text-white/75"
                >
                  <Icon className="size-4 shrink-0" />
                  <span className="min-w-0 flex-1 truncate">{row.label}</span>
                  <span className="text-xs text-black/32 dark:text-white/35">{row.meta}</span>
                </Link>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}

function ActiveSessionWorkspace({
  activeSession,
  data,
}: {
  activeSession: WorkSession;
  data: ReturnType<typeof getSessionsWorkspace>;
}) {
  const [messages, setMessages] = useState<SessionMessage[]>(activeSession.messages);
  const [draftBodies, setDraftBodies] = useState<Record<string, string>>(() =>
    Object.fromEntries(data.drafts.map((draft) => [draft.id, draft.body])),
  );
  const [selectedDocumentId, setSelectedDocumentId] = useState<string | null>(() =>
    getInitialSelectedDocumentId(activeSession),
  );
  const [rightPanelOpen, setRightPanelOpen] = useState(false);
  const [floatingSummaryOpen, setFloatingSummaryOpen] = useState(true);
  const [openDocumentIds, setOpenDocumentIds] = useState<string[]>(() =>
    getInitialOpenDocumentIds(activeSession),
  );
  const [generationRun, setGenerationRun] = useState<GenerationRun | null>(null);
  const [generatedPack, setGeneratedPack] = useState<ContentPack | null>(null);
  const [generationError, setGenerationError] = useState("");
  const [generationReferences, setGenerationReferences] = useState<GenerationReference[]>([]);
  const [generating, setGenerating] = useState(false);
  const generationAbortRef = useRef<AbortController | null>(null);

  const sessionDrafts = data.drafts.filter((draft) => activeSession.draftIds.includes(draft.id));
  const sessionReferences = data.references.filter((reference) =>
    activeSession.referenceIds.includes(reference.id),
  );

  useEffect(() => {
    const controller = new AbortController();
    void plotApiClient.listGenerationReferences({ signal: controller.signal })
      .then(setGenerationReferences)
      .catch(() => {
        // Source discovery is progressive enhancement; unrelated mock sessions remain available.
      });
    return () => {
      controller.abort();
      generationAbortRef.current?.abort();
    };
  }, []);

  const selectedDocument = selectedDocumentId ? getSelectedDocument(selectedDocumentId) : null;

  const openDocuments = useMemo(() => {
    return openDocumentIds
      .map((documentId) => getSelectedDocument(documentId))
      .filter((item): item is SelectedDocument => Boolean(item));
  }, [openDocumentIds]);

  function selectDocument(documentId: string) {
    setSelectedDocumentId(documentId);
    setRightPanelOpen(true);
    setOpenDocumentIds((current) =>
      current.includes(documentId) ? current : [...current.slice(-2), documentId],
    );
  }

  function updateDraftBody(draftId: string, body: string) {
    setDraftBodies((current) => ({
      ...current,
      [draftId]: body,
    }));
  }

  function appendConversation(message: string, includeDemoReply: boolean) {
    setMessages((current) => {
      const userMessage: SessionMessage = {
        id: `user-message-${current.length + 1}`,
        role: "user",
        author: "You",
        timestamp: "Now",
        content: message,
      };
      return includeDemoReply
        ? [...current, userMessage, createDemoAgentReply(message, current.length + 2)]
        : [...current, userMessage];
    });
  }

  async function submitMessage(message: string, referenceIds: string[]) {
    const imported = generationReferences
      .filter((reference) => referenceIds.includes(reference.id))
      .map((reference) => ({ sourceScopeId: reference.sourceScopeId, writingBlockId: reference.id }));
    const selected = sessionReferences
      .filter((reference) => referenceIds.includes(reference.id) && reference.sourceScopeId && reference.writingBlockId)
      .map((reference) => ({ sourceScopeId: reference.sourceScopeId!, writingBlockId: reference.writingBlockId! }));
    const generationReady = [...imported, ...selected];
    if (!generationReady.length) {
      appendConversation(message, true);
      return;
    }
    const scopeId = generationReady[0]!.sourceScopeId;
    if (generationReady.some((reference) => reference.sourceScopeId !== scopeId)) {
      setGenerationError("Selected references must belong to the same source scope.");
      return;
    }

    appendConversation(message, false);
    generationAbortRef.current?.abort();
    const controller = new AbortController();
    generationAbortRef.current = controller;
    setGenerating(true);
    setGenerationError("");
    setGeneratedPack(null);
    try {
      const run = await createAndPollGeneration(
        plotApiClient,
        { sourceScopeId: scopeId, writingBlockIds: generationReady.map((reference) => reference.writingBlockId), instruction: message },
        crypto.randomUUID(),
        { signal: controller.signal, onUpdate: setGenerationRun },
      );
      setGenerationRun(run);
      setGeneratedPack(run.contentPack);
    } catch (error) {
      if (!(error instanceof DOMException && error.name === "AbortError")) {
        setGenerationError(error instanceof Error ? error.message : "Generation could not be completed.");
      }
    } finally {
      if (generationAbortRef.current === controller) setGenerating(false);
    }
  }

  async function resolveIntervention(input: Parameters<typeof plotApiClient.resolveConflict>[2]) {
    const current = generationRun;
    if (!current?.pendingIntervention) throw new Error("This intervention is no longer current.");
    const accepted = await plotApiClient.resolveConflict(current.id, current.pendingIntervention.id, input);
    setGenerationRun(accepted);
    if (["READY", "NEEDS_REVIEW", "NEEDS_YOUR_CALL", "FAILED"].includes(accepted.status)) {
      setGeneratedPack(accepted.contentPack);
      return accepted;
    }
    const controller = new AbortController();
    generationAbortRef.current?.abort();
    generationAbortRef.current = controller;
    setGenerating(true);
    try {
      const resumed = await pollGeneration(plotApiClient, accepted.id, { signal: controller.signal, onUpdate: setGenerationRun });
      setGenerationRun(resumed);
      setGeneratedPack(resumed.contentPack);
      return resumed;
    } finally {
      if (generationAbortRef.current === controller) setGenerating(false);
    }
  }

  return (
    <div className="flex h-screen min-h-0 bg-white dark:bg-[#111113]">
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 shrink-0 items-center justify-between bg-white px-4 dark:bg-[#111113] sm:px-6 lg:px-8">
          <h1 className="sr-only">{activeSession.title}</h1>
          <div className="shell-session-heading flex min-w-0 items-center gap-2 text-sm font-semibold text-black/78 dark:text-white/82">
            <FileText className="size-4 shrink-0 text-black/50 dark:text-white/50" />
            <span className="truncate">{activeSession.title}</span>
            <button
              type="button"
              aria-label="Session actions"
              className="inline-flex size-7 shrink-0 items-center justify-center rounded-xl text-black/45 transition hover:bg-black/5 hover:text-black/70 dark:text-white/45 dark:hover:bg-white/10 dark:hover:text-white/75"
            >
              <MoreHorizontal className="size-4" />
            </button>
          </div>
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={() => setFloatingSummaryOpen((open) => !open)}
              aria-pressed={floatingSummaryOpen}
              aria-label={floatingSummaryOpen ? "Hide session summary" : "Show session summary"}
              className={`hidden size-9 shrink-0 items-center justify-center rounded-xl text-black/55 transition hover:bg-black/5 hover:text-black/75 dark:text-white/55 dark:hover:bg-white/10 dark:hover:text-white/75 ${
                rightPanelOpen ? "min-[2100px]:inline-flex" : "min-[1700px]:inline-flex"
              }`}
            >
              <SlidersHorizontal className="size-4" />
            </button>
            {!rightPanelOpen && (
              <button
                type="button"
                onClick={() => setRightPanelOpen(true)}
                disabled={!selectedDocument}
                aria-label="Open document panel"
                className="inline-flex size-9 shrink-0 items-center justify-center rounded-xl text-black/55 transition hover:bg-black/5 hover:text-black/75 disabled:cursor-not-allowed disabled:opacity-35 dark:text-white/55 dark:hover:bg-white/10 dark:hover:text-white/75"
              >
                <PanelRightOpen className="size-4" />
              </button>
            )}
          </div>
        </header>

        <SessionThread
          messages={messages}
          drafts={sessionDrafts}
          references={sessionReferences}
          floatingSummaryOpen={floatingSummaryOpen}
          rightPanelOpen={rightPanelOpen}
          onSelectDocument={selectDocument}
          generationPanel={
            <GenerationPanel
              run={generationRun}
              pack={generatedPack}
              busy={generating}
              error={generationError}
              onResolve={resolveIntervention}
              onPackChange={setGeneratedPack}
            />
          }
        />
        <SessionComposer
          key={generationReferences.map((reference) => reference.id).join(":") || "mock-references"}
          onSubmit={(message, referenceIds) => void submitMessage(message, referenceIds)}
          references={generationReferences.length
            ? generationReferences.map((reference) => ({
                id: reference.id,
                label: `${reference.repositoryLabel} · ${reference.sourceLabel}`,
                available: true,
                groupId: reference.sourceScopeId,
              }))
            : sessionReferences.map((reference) => ({
                id: reference.id,
                label: reference.label,
                available: Boolean(reference.sourceScopeId && reference.writingBlockId),
                groupId: reference.sourceScopeId,
              }))}
          busy={generating}
        />
      </div>

      {rightPanelOpen && (
        <SessionSidePanel
          selectedDocument={selectedDocument}
          openDocuments={openDocuments}
          drafts={sessionDrafts}
          references={sessionReferences}
          draftBodies={draftBodies}
          onDraftBodyChange={updateDraftBody}
          onSelectDocument={selectDocument}
          onClose={() => setRightPanelOpen(false)}
        />
      )}
    </div>
  );
}

function GenerationPanel({
  run,
  pack,
  busy,
  error,
  onResolve,
  onPackChange,
}: {
  run: GenerationRun | null;
  pack: ContentPack | null;
  busy: boolean;
  error: string;
  onResolve: (input: Parameters<typeof plotApiClient.resolveConflict>[2]) => Promise<GenerationRun>;
  onPackChange: (pack: ContentPack) => void;
}) {
  if (!run && !busy && !error) return null;
  return (
    <article className="space-y-3" aria-live="polite">
      {busy ? (
        <div className="rounded-xl border border-black/10 bg-black/[0.025] px-4 py-3 text-sm text-black/62 dark:border-white/10 dark:bg-white/[0.04] dark:text-white/62">
          Plot is {stageLabel(run?.status)}…
        </div>
      ) : null}
      {error ? <div role="alert" className="rounded-xl border border-rose-300/60 bg-rose-50 px-4 py-3 text-sm text-rose-900 dark:border-rose-400/25 dark:bg-rose-400/[0.08] dark:text-rose-200">{error}</div> : null}
      {run?.status === "FAILED" && !error ? (
        <div role="alert" className="rounded-xl border border-rose-300/60 bg-rose-50 px-4 py-3 text-sm text-rose-900 dark:border-rose-400/25 dark:bg-rose-400/[0.08] dark:text-rose-200">
          Generation stopped before a reviewable draft was produced{run.failureCode ? ` (${run.failureCode})` : ""}. Try again or adjust the selected references.
        </div>
      ) : null}
      {run?.pendingIntervention ? <InterventionPanel key={run.pendingIntervention.id} run={run} onResolve={onResolve} /> : null}
      {pack ? (
        <>
          <CitedDraftEditor
            pack={pack}
            onEditSentence={(sentence, body) => plotApiClient.editSentence(pack.variant.id, sentence.id, { expectedRevisionNumber: sentence.revisionNumber, body })}
            onPackChange={onPackChange}
          />
          <ExportDialog pack={pack} client={plotApiClient} />
        </>
      ) : null}
    </article>
  );
}

function stageLabel(status?: GenerationRun["status"]) {
  if (status === "REVIEWING") return "checking source support";
  if (status === "REWRITING") return "revising failed sentences";
  if (status === "WRITING") return "drafting from selected references";
  return "preparing the grounded draft";
}

function getInitialSelectedDocumentId(session: WorkSession) {
  return session.draftIds[0] ?? session.referenceIds[0] ?? null;
}

function getInitialOpenDocumentIds(session: WorkSession) {
  return [session.draftIds[0], session.referenceIds[0]].filter(
    (documentId): documentId is string => Boolean(documentId),
  );
}
