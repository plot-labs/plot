"use client";

import { PanelRightOpen } from "lucide-react";
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
  const activeSession =
    data.sessions.find((session) => session.id === requestedSessionId) ?? data.sessions[0];

  return (
    <ActiveSessionWorkspace
      key={activeSession.id}
      activeSession={activeSession}
      data={data}
    />
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
  const [rightPanelOpen, setRightPanelOpen] = useState(true);
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
    <div className="flex h-[calc(100vh-3rem)] min-h-0">
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="border-b border-black/10 bg-[#fbfaf6] px-4 py-4 dark:border-white/10 dark:bg-[#181818] sm:px-6 lg:px-8">
          <div className="text-xs font-medium uppercase text-black/45 dark:text-white/45">Session</div>
          <div className="mt-1 flex items-center justify-between gap-4">
            <div>
              <h1 className="text-2xl font-semibold">{activeSession.title}</h1>
              <p className="mt-1 text-sm text-black/55 dark:text-white/55">{activeSession.subtitle}</p>
            </div>
            {!rightPanelOpen && (
              <button
                type="button"
                onClick={() => setRightPanelOpen(true)}
                className="inline-flex items-center gap-2 rounded-md border border-black/10 px-3 py-2 text-sm dark:border-white/10"
              >
                <PanelRightOpen className="size-4" />
                Open document
              </button>
            )}
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
