"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import { ChatWorkspace } from "./chat-workspace";

export type ConversationTarget = { chatId?: string; agentId?: string; artifactId?: string };
const eventName = "plot:open-conversation";

export function openPlotConversation(target: ConversationTarget = {}) {
  window.dispatchEvent(new CustomEvent(eventName, { detail: target }));
}

export function ConversationPanel() {
  const [target, setTarget] = useState<ConversationTarget | null>(null);
  const [open, setOpen] = useState(false);
  const [workspaceVersion, setWorkspaceVersion] = useState(0);
  const version = useRef(0);
  const trigger = useRef<HTMLElement | null>(null);
  const closeButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const show = (event: Event) => {
      trigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      const next = (event as CustomEvent<ConversationTarget>).detail ?? {};
      if (next.chatId) { version.current += 1; setWorkspaceVersion(version.current); }
      setTarget((current) => !next.chatId && current ? current : next);
      setOpen(true);
    };
    const reset = () => {
      version.current += 1;
      setWorkspaceVersion(version.current);
      setTarget(null);
      setOpen(false);
    };
    window.addEventListener(eventName, show);
    window.addEventListener("plot:workspace-changed", reset);
    return () => {
      window.removeEventListener(eventName, show);
      window.removeEventListener("plot:workspace-changed", reset);
    };
  }, []);

  useEffect(() => { if (open) closeButton.current?.focus(); }, [open]);

  const navigate = useCallback((href: string) => {
    if (version.current !== workspaceVersion) return;
    const params = new URL(href, window.location.origin).searchParams;
    setTarget({ chatId: params.get("chat") ?? undefined, agentId: params.get("agent") ?? undefined, artifactId: params.get("artifact") ?? undefined });
  }, [workspaceVersion]);

  function close() {
    setOpen(false);
    trigger.current?.focus();
  }

  if (target === null) return null;
  return <aside aria-label="Plot conversation" style={open ? undefined : { display: "none" }}
    onKeyDown={(event) => { if (event.key === "Escape" && !event.defaultPrevented) { event.stopPropagation(); close(); } }}
    className="fixed inset-0 z-40 flex min-h-0 flex-col border-l border-black/10 bg-white shadow-xl dark:border-white/10 dark:bg-[#111113] lg:relative lg:inset-auto lg:z-10 lg:h-full lg:w-[min(480px,45vw)] lg:shrink-0">
    <header className="flex shrink-0 items-center justify-between border-b border-black/10 px-4 py-3 dark:border-white/10">
      <span className="font-medium">Plot</span>
      <div className="flex items-center gap-3">
        <button className="text-sm underline" onClick={() => { version.current += 1; setWorkspaceVersion(version.current); setTarget({}); }}>New request</button>
        <button ref={closeButton} aria-label="Close conversation" onClick={close} className="rounded-md p-2 hover:bg-black/5"><X className="size-4" /></button>
      </div>
    </header>
    <div className="min-h-0 flex-1 overflow-y-auto">
      <ChatWorkspace key={`${workspaceVersion}:${target.chatId ?? "new"}`} target={target} onNavigate={navigate} />
    </div>
  </aside>;
}
