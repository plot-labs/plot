"use client";

import { Suspense, useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
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
  const [chatsStatus, setChatsStatus] = useState<"loading" | "ready" | "error">("loading");
  const [workspaceRevision, setWorkspaceRevision] = useState(0);

  useEffect(() => {
    function handleWorkspaceChanged() {
      setChats([]);
      setChatsStatus("loading");
      setWorkspaceRevision((current) => current + 1);
      router.replace("/chat", { scroll: false });
    }

    window.addEventListener("plot:workspace-changed", handleWorkspaceChanged);
    return () => window.removeEventListener("plot:workspace-changed", handleWorkspaceChanged);
  }, [router]);

  useEffect(() => {
    const controller = new AbortController();
    void plotApiClient.listSessions({ signal: controller.signal })
      .then((value) => {
        if (controller.signal.aborted) return;
        setChats(value);
        setChatsStatus("ready");
      })
      .catch(() => {
        if (!controller.signal.aborted) setChatsStatus("error");
      });
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

  if (requestedChatId && chatsStatus === "loading") {
    return <ChatStatusMessage role="status" message="Loading chat…" />;
  }

  if (requestedChatId && chatsStatus === "error") {
    return (
      <ChatStatusMessage role="alert" message="This chat could not be loaded.">
        <button
          type="button"
          onClick={() => {
            setChatsStatus("loading");
            setWorkspaceRevision((current) => current + 1);
          }}
          className="glass-button"
        >
          Try again
        </button>
      </ChatStatusMessage>
    );
  }

  if (requestedChatId && chatsStatus === "ready") {
    return (
      <ChatStatusMessage role="alert" message="This chat could not be found. It may have been removed or belong to another workspace.">
        <Link href="/chat" className="glass-button">Start a new chat</Link>
      </ChatStatusMessage>
    );
  }

  return <ChatHome />;
}

function ChatStatusMessage({ role, message, children }: { role: "status" | "alert"; message: string; children?: ReactNode }) {
  return (
    <div className="flex h-full min-h-[60vh] flex-col items-center justify-center gap-4 px-6 text-center">
      <p role={role} className="text-sm text-black/55 dark:text-white/55">{message}</p>
      {children}
    </div>
  );
}
