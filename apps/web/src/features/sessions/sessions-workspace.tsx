"use client";

import { PanelRightOpen } from "lucide-react";
import { useMemo, useState } from "react";

import {
  createDemoAgentReply,
  getSelectedDocument,
  getSessionsWorkspace,
  type SelectedDocument,
} from "@/lib/api-client";
import type { SessionMessage } from "@/lib/dev-context";
import { SessionComposer } from "@/features/sessions/session-composer";
import { SessionSidePanel } from "@/features/sessions/session-side-panel";
import { SessionThread } from "@/features/sessions/session-thread";

export function SessionsWorkspace() {
  const data = getSessionsWorkspace();
  const activeSession = data.sessions[0];
  const [messages, setMessages] = useState<SessionMessage[]>(activeSession.messages);
  const [selectedDocumentId, setSelectedDocumentId] = useState(activeSession.draftIds[0]);
  const [rightPanelOpen, setRightPanelOpen] = useState(true);
  const [openDocumentIds, setOpenDocumentIds] = useState<string[]>([
    activeSession.draftIds[0],
    activeSession.referenceIds[0],
  ]);

  const sessionDrafts = data.drafts.filter((draft) => activeSession.draftIds.includes(draft.id));
  const sessionReferences = data.references.filter((reference) =>
    activeSession.referenceIds.includes(reference.id),
  );

  const selectedDocument = getSelectedDocument(selectedDocumentId);

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

  function submitMessage(message: string) {
    const userMessage: SessionMessage = {
      id: `user-message-${messages.length + 1}`,
      role: "user",
      author: "You",
      timestamp: "Now",
      content: message,
    };
    const agentReply = createDemoAgentReply(message, messages.length + 2);

    setMessages((current) => [...current, userMessage, agentReply]);
  }

  return (
    <div className="flex h-[calc(100vh-3rem)] min-h-0">
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="border-b border-black/10 bg-[#fbfaf6] px-8 py-4 dark:border-white/10 dark:bg-[#181818]">
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
          drafts={data.drafts}
          references={data.references}
          onSelectDocument={selectDocument}
          onClose={() => setRightPanelOpen(false)}
        />
      )}
    </div>
  );
}
