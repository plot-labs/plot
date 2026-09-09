"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import type { SourceReference, WorkSessionSummary as ChatSummary } from "@plot/api-client";
import { ChatActiveWorkspace } from "@/features/chat/chat-active-workspace";
import { ChatHome } from "@/features/chat/chat-home";
import { messageFor } from "@/features/chat/chat-workspace-utils";
import { plotApiClient } from "@/lib/api-client";

type Props = { target?: { chatId?: string; agentId?: string; artifactId?: string }; onNavigate?: (href: string) => void };

export function ChatWorkspace(props: Props) {
  return <Suspense fallback={null}><ChatWorkspaceContent {...props} /></Suspense>;
}

function ChatWorkspaceContent({ target, onNavigate }: Props) {
  const searchParams = useSearchParams();
  const router = useRouter();
  const requestedChatId = target ? target.chatId ?? null : searchParams.get("chat");
  const requestedAgentId = target ? target.agentId ?? null : searchParams.get("agent");
  const requestedArtifactId = target ? target.artifactId ?? null : searchParams.get("artifact");
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [chats, setChats] = useState<ChatSummary[]>([]);
  const [references, setReferences] = useState<SourceReference[]>([]);
  const [workspaceRevision, setWorkspaceRevision] = useState(0);
  const [referencesLoading, setReferencesLoading] = useState(true);
  const [referencesError, setReferencesError] = useState("");

  useEffect(() => {
    function handleWorkspaceChanged() {
      setChats([]);
      setSessionsLoading(true);
      setReferences([]);
      setReferencesError("");
      setReferencesLoading(true);
      setWorkspaceRevision((current) => current + 1);
      if (!onNavigate) router.replace("/chat", { scroll: false });
    }

    window.addEventListener("plot:workspace-changed", handleWorkspaceChanged);
    return () => window.removeEventListener("plot:workspace-changed", handleWorkspaceChanged);
  }, [router, onNavigate]);

  useEffect(() => {
    const controller = new AbortController();
    void plotApiClient.listSessions({ signal: controller.signal })
      .then((value) => { if (!controller.signal.aborted) setChats(value); })
      .catch(() => undefined)
      .finally(() => { if (!controller.signal.aborted) setSessionsLoading(false); });
    void plotApiClient.listSourceReferences({ signal: controller.signal })
      .then((value) => { if (!controller.signal.aborted) setReferences(value); })
      .catch((error) => { if (!controller.signal.aborted) setReferencesError(messageFor(error, "Sources could not be loaded.")); })
      .finally(() => { if (!controller.signal.aborted) setReferencesLoading(false); });
    return () => controller.abort();
  }, [workspaceRevision]);

  if (target?.chatId && (referencesLoading || sessionsLoading)) return <p role="status" className="p-4">Loading conversation…</p>;

  const activeChat = requestedChatId ? chats.find((chat) => chat.id === requestedChatId) : null;
  if (activeChat) {
    return (
      <ChatActiveWorkspace
        embedded={Boolean(target)}
        onNavigate={onNavigate}
        activeChat={activeChat}
        references={references}
        sourceError={referencesError}
        requestedAgentId={requestedAgentId}
        requestedArtifactId={requestedArtifactId}
      />
    );
  }

  if (target?.chatId && !activeChat) return <p role="alert" className="p-4">This conversation could not be loaded. Close and reopen it to try again.</p>;

  return <ChatHome embedded={Boolean(target)} onNavigate={onNavigate} references={references} referencesLoading={referencesLoading} referencesError={referencesError} />;
}
