"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import type { WorkSessionSummary as ChatSummary } from "@plot/api-client";
import { ChatActiveWorkspace } from "@/features/chat/chat-active-workspace";
import { ChatHome } from "@/features/chat/chat-home";
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
  const requestedVersionId = searchParams.get("version");
  const [chats, setChats] = useState<ChatSummary[]>([]);
  const [workspaceRevision, setWorkspaceRevision] = useState(0);

  useEffect(() => {
    function handleWorkspaceChanged() {
      setChats([]);
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
    return () => controller.abort();
  }, [workspaceRevision]);

  const activeChat = requestedChatId ? chats.find((chat) => chat.id === requestedChatId) : null;
  if (activeChat) {
    return (
      <ChatActiveWorkspace
        key={activeChat.id}
        activeChat={activeChat}
        requestedAgentId={requestedAgentId}
        requestedArtifactId={requestedArtifactId}
        requestedVersionId={requestedVersionId}
      />
    );
  }

  return <ChatHome />;
}
