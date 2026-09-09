"use client";

import { useEffect, useState } from "react";
import type { WorkSessionSummary as ChatSummary } from "@plot/api-client";

import { plotApiClient } from "@/lib/api-client";

export function useRecentChats({ settingsMode, selectedWorkspaceId }: { settingsMode: boolean; selectedWorkspaceId: string | null }) {
  const [history, setHistory] = useState<{ workspaceId: string; chats: ChatSummary[] } | null>(null);

  useEffect(() => {
    if (settingsMode || !selectedWorkspaceId) return;

    let controller: AbortController | undefined;
    const loadSessions = () => {
      controller?.abort();
      const request = new AbortController();
      controller = request;
      void plotApiClient.listSessions({ signal: request.signal })
        .then((value) => {
          if (!request.signal.aborted) {
            setHistory({ workspaceId: selectedWorkspaceId, chats: value.slice(0, 8) });
          }
        })
        .catch(() => undefined);
    };
    loadSessions();
    const interval = window.setInterval(loadSessions, 30_000);
    window.addEventListener("focus", loadSessions);
    window.addEventListener("plot:sessions-changed", loadSessions);
    return () => {
      controller?.abort();
      window.clearInterval(interval);
      window.removeEventListener("focus", loadSessions);
      window.removeEventListener("plot:sessions-changed", loadSessions);
    };
  }, [settingsMode, selectedWorkspaceId]);

  return !settingsMode && history?.workspaceId === selectedWorkspaceId ? history.chats : [];
}
