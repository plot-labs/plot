"use client";

import { useEffect, useState } from "react";
import type { WorkSessionSummary as ChatSummary } from "@plot/api-client";

import { plotApiClient } from "@/lib/api-client";

export function useRecentChats({ settingsMode, selectedWorkspaceId }: { settingsMode: boolean; selectedWorkspaceId: string | null }) {
  const [recentChats, setRecentChats] = useState<ChatSummary[]>([]);

  useEffect(() => {
    if (settingsMode || !selectedWorkspaceId) return;

    const controller = new AbortController();
    const loadSessions = () => {
      void plotApiClient.listSessions({ signal: controller.signal })
        .then((value) => {
          if (!controller.signal.aborted) setRecentChats(value.slice(0, 8));
        })
        .catch(() => undefined);
    };
    loadSessions();
    window.addEventListener("plot:sessions-changed", loadSessions);
    return () => {
      controller.abort();
      window.removeEventListener("plot:sessions-changed", loadSessions);
    };
  }, [settingsMode, selectedWorkspaceId]);

  return recentChats;
}
