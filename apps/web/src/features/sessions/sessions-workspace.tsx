"use client";

import Link from "next/link";
import { GitPullRequest, MessageSquareText, PanelRightClose, PanelRightOpen } from "lucide-react";
import { useSearchParams } from "next/navigation";
import { Suspense, useMemo, useState } from "react";

import {
  createDemoAgentReply,
  getSelectedDocument,
  getSessionsWorkspace,
  type SelectedDocument,
  type SessionMessage,
  type WorkSession,
} from "@/lib/api-client";
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
  const [openDocumentIds, setOpenDocumentIds] = useState<string[]>(() =>
    getInitialOpenDocumentIds(activeSession),
  );

  const sessionDrafts = data.drafts.filter((draft) => activeSession.draftIds.includes(draft.id));
  const sessionReferences = data.references.filter((reference) =>
    activeSession.referenceIds.includes(reference.id),
  );

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

  function submitMessage(message: string) {
    setMessages((current) => {
      const userMessage: SessionMessage = {
        id: `user-message-${current.length + 1}`,
        role: "user",
        author: "You",
        timestamp: "Now",
        content: message,
      };
      const agentReply = createDemoAgentReply(message, current.length + 2);

      return [...current, userMessage, agentReply];
    });
  }

  return (
    <div className="flex h-screen min-h-0 bg-white dark:bg-[#111113]">
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="border-b border-black/[0.08] bg-white px-4 py-4 dark:border-white/10 dark:bg-[#111113] sm:px-6 lg:px-8">
          <div className="text-xs font-medium text-black/40 dark:text-white/40">Session</div>
          <div className="mt-1 flex items-center justify-between gap-4">
            <div>
              <h1 className="text-xl font-semibold">{activeSession.title}</h1>
              <p className="mt-1 text-sm text-black/55 dark:text-white/55">{activeSession.subtitle}</p>
            </div>
            <button
              type="button"
              onClick={() => setRightPanelOpen((open) => !open)}
              disabled={!selectedDocument}
              aria-pressed={rightPanelOpen}
              aria-label={rightPanelOpen ? "Close document panel" : "Open document panel"}
              className="inline-flex size-9 shrink-0 items-center justify-center rounded-xl border border-black/10 text-black/55 transition hover:bg-black/5 hover:text-black/75 disabled:cursor-not-allowed disabled:opacity-35 dark:border-white/10 dark:text-white/55 dark:hover:bg-white/10 dark:hover:text-white/75"
            >
              {rightPanelOpen ? <PanelRightClose className="size-4" /> : <PanelRightOpen className="size-4" />}
            </button>
          </div>
        </header>

        <SessionThread
          messages={messages}
          drafts={sessionDrafts}
          references={sessionReferences}
          onSelectDocument={selectDocument}
        />
        <SessionComposer onSubmit={submitMessage} />
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

function getInitialSelectedDocumentId(session: WorkSession) {
  return session.draftIds[0] ?? session.referenceIds[0] ?? null;
}

function getInitialOpenDocumentIds(session: WorkSession) {
  return [session.draftIds[0], session.referenceIds[0]].filter(
    (documentId): documentId is string => Boolean(documentId),
  );
}
