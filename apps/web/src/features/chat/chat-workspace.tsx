"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import type { SourceReference, WorkSessionSummary as ChatSummary } from "@plot/api-client";
import { ChatActiveWorkspace } from "@/features/chat/chat-active-workspace";
import { ChatHome } from "@/features/chat/chat-home";
import { messageFor } from "@/features/chat/chat-workspace-utils";
import { plotApiClient } from "@/lib/api-client";

export function ChatWorkspace() {
  return <Suspense fallback={null}><ChatWorkspaceContent /></Suspense>;
}

function ChatWorkspaceContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const requestedChatId = searchParams.get("chat");
  const requestedAgentId = searchParams.get("agent");
  const requestedArtifactId = searchParams.get("artifact");
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
    return (
      <ChatActiveWorkspace
        activeChat={activeChat}
        references={references}
        sourceError={referencesError}
        requestedAgentId={requestedAgentId}
        requestedArtifactId={requestedArtifactId}
      />
    );
  }

  return <ChatHome references={references} referencesLoading={referencesLoading} referencesError={referencesError} />;
}
