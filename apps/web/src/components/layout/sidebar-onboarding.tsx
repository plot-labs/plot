"use client";

import { Check, ChevronDown, ChevronUp, X } from "lucide-react";
import { useEffect, useState } from "react";

import { OnboardingFlow } from "@/features/onboarding/onboarding-flow";
import { plotApiClient, type GitHubConnection } from "@/lib/api-client";

export function SidebarOnboarding({ workspaceId }: { workspaceId: string | null }) {
  const [status, setStatus] = useState<{ connected: boolean; repositories: boolean; changelog: boolean } | null>(null);
  const [collapsed, setCollapsed] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [reloadNonce, setReloadNonce] = useState(0);

  useEffect(() => {
    if (!workspaceId) return;
    const controller = new AbortController();
    void plotApiClient.listGitHubConnections({ signal: controller.signal })
      .then(async (connections) => {
        const repositories = activeRepositories(connections);
        const activities = await Promise.all(repositories.map((repository) => plotApiClient.getGitHubReleaseActivity(repository.id, { signal: controller.signal }).catch(() => null)));
        if (controller.signal.aborted) return;
        setStatus({
          connected: connections.some((connection) => connection.status === "ACTIVE"),
          repositories: repositories.length > 0,
          changelog: activities.some((activity) => activity?.status === "READY"),
        });
      })
      .catch(() => {
        if (!controller.signal.aborted) setStatus({ connected: false, repositories: false, changelog: false });
      });
    return () => controller.abort();
  }, [workspaceId, reloadNonce]);

  useEffect(() => {
    if (!modalOpen) return;
    function close(event: KeyboardEvent) { if (event.key === "Escape") setModalOpen(false); }
    document.addEventListener("keydown", close);
    return () => document.removeEventListener("keydown", close);
  }, [modalOpen]);

  if (!status || status.changelog) return null;
  const steps = [
    ["Connect GitHub", status.connected],
    ["Select repositories", status.repositories],
    ["Create first changelog", status.changelog],
  ] as const;
  const completed = steps.filter(([, done]) => done).length;
  const initialStep: 1 | 2 = status.connected ? 2 : 1;

  return <>
    <div className="px-3 pb-3">
      {collapsed ? (
        <div className="rounded-[13px] bg-gradient-to-b from-white/90 via-white/35 to-white/15 p-px shadow-[0_6px_20px_rgba(30,38,52,0.08)] dark:from-white/20 dark:via-white/8 dark:to-white/5">
          <button type="button" onClick={() => setCollapsed(false)} className="flex w-full items-center gap-2 rounded-[12px] bg-white/48 px-3 py-2 text-left text-[12px] font-medium text-black/55 shadow-[inset_0_1px_0_rgba(255,255,255,0.9)] backdrop-blur-xl transition-[background-color,transform] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] hover:-translate-y-px hover:bg-white/65 dark:bg-white/[0.06] dark:text-white/58 dark:shadow-[inset_0_1px_0_rgba(255,255,255,0.12)] dark:hover:bg-white/[0.09]"><span className="min-w-0 flex-1 truncate">Getting started ({completed}/3)</span><ChevronUp className="size-3.5" /></button>
        </div>
      ) : (
        <div className="rounded-[16px] bg-gradient-to-b from-white/95 via-white/40 to-white/15 p-px shadow-[0_10px_30px_rgba(30,38,52,0.09)] dark:from-white/22 dark:via-white/9 dark:to-white/5 dark:shadow-[0_12px_32px_rgba(0,0,0,0.22)]">
          <section className="relative overflow-hidden rounded-[15px] bg-white/46 shadow-[inset_0_1px_0_rgba(255,255,255,0.95),inset_0_-1px_0_rgba(255,255,255,0.22)] backdrop-blur-xl dark:bg-white/[0.055] dark:shadow-[inset_0_1px_0_rgba(255,255,255,0.14),inset_0_-1px_0_rgba(255,255,255,0.03)]" aria-label="Getting started">
            <span aria-hidden="true" className="pointer-events-none absolute -right-8 -top-10 size-24 rounded-full bg-white/75 blur-2xl dark:bg-white/[0.08]" />
            <span aria-hidden="true" className="pointer-events-none absolute -bottom-12 -left-8 size-20 rounded-full bg-white/65 blur-2xl dark:bg-white/[0.035]" />
            <div className="relative flex items-center gap-2 border-b border-white/65 px-3 py-2.5 dark:border-white/[0.08]"><button type="button" onClick={() => setModalOpen(true)} className="min-w-0 flex-1 text-left text-[12px] font-semibold tracking-[-0.01em] text-black/72 dark:text-white/78">Getting started</button><span className="rounded-full bg-white/60 px-1.5 py-0.5 text-[10px] tabular-nums text-black/38 shadow-[inset_0_1px_0_rgba(255,255,255,0.85)] dark:bg-white/[0.08] dark:text-white/42">{completed}/3</span><button type="button" onClick={() => setCollapsed(true)} aria-label="Collapse getting started" className="rounded-full p-1 text-black/30 transition-colors duration-300 hover:bg-white/60 hover:text-black/60 dark:text-white/35 dark:hover:bg-white/10 dark:hover:text-white/65"><ChevronDown className="size-3.5" /></button></div>
            <div className="relative mx-3 mt-2 h-[5px] overflow-hidden rounded-full border border-white/65 bg-white/28 shadow-[inset_0_1px_2px_rgba(20,28,40,0.09),0_1px_0_rgba(255,255,255,0.5)] dark:border-white/10 dark:bg-black/12 dark:shadow-[inset_0_1px_2px_rgba(0,0,0,0.25)]"><div className="relative h-full rounded-full bg-[#252a30]/68 shadow-[inset_0_1px_0_rgba(255,255,255,0.28)] transition-[width] duration-700 ease-[cubic-bezier(0.32,0.72,0,1)] dark:bg-white/68 dark:shadow-[inset_0_1px_0_rgba(255,255,255,0.55)]" style={{ width: `${(completed / 3) * 100}%` }} /></div>
            <div className="relative p-2">{steps.map(([label, done]) => <button key={label} type="button" disabled={done} onClick={() => setModalOpen(true)} className="flex w-full items-center gap-2 rounded-[9px] px-2 py-1.5 text-left text-[12px] transition-[background-color,transform] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] enabled:hover:translate-x-0.5 enabled:hover:bg-white/60 disabled:cursor-default dark:enabled:hover:bg-white/[0.08]"><span className={`flex size-4 shrink-0 items-center justify-center rounded-full border shadow-[inset_0_1px_0_rgba(255,255,255,0.5)] ${done ? "border-black/65 bg-[#252a30]/90 text-white shadow-[0_2px_7px_rgba(30,38,52,0.14),inset_0_1px_0_rgba(255,255,255,0.28)] dark:border-white/70 dark:bg-white/85 dark:text-[#18191b]" : "border-black/15 bg-white/42 text-transparent dark:border-white/18 dark:bg-white/[0.04]"}`}>{done && <Check className="size-2.5" />}</span><span className={done ? "text-black/35 line-through dark:text-white/38" : "text-black/62 dark:text-white/68"}>{label}</span></button>)}</div>
          </section>
        </div>
      )}
    </div>

    {modalOpen && <div className="fixed inset-0 z-[120] flex items-center justify-center bg-black/25 p-4 backdrop-blur-[2px] dark:bg-black/55" onMouseDown={(event) => { if (event.target === event.currentTarget) setModalOpen(false); }}><section role="dialog" aria-modal="true" aria-labelledby="onboarding-modal-title" className="relative max-h-[min(820px,calc(100dvh-32px))] w-full max-w-[680px] overflow-y-auto rounded-[18px] border border-black/10 bg-[#f4f6f8] px-7 py-8 shadow-[0_24px_70px_rgba(0,0,0,0.2)] dark:border-white/12 dark:bg-[#18191b] sm:px-10 sm:py-10"><h2 id="onboarding-modal-title" className="sr-only">Set up Plot</h2><button autoFocus type="button" onClick={() => setModalOpen(false)} aria-label="Close onboarding" className="absolute right-4 top-4 z-10 inline-flex size-8 items-center justify-center rounded-full text-black/40 transition hover:bg-black/[0.05] hover:text-black/70 dark:text-white/40 dark:hover:bg-white/10 dark:hover:text-white/70"><X className="size-4" /></button><OnboardingFlow embedded initialStep={initialStep} onComplete={() => { setModalOpen(false); setReloadNonce((value) => value + 1); }} /></section></div>}
  </>;
}

function activeRepositories(connections: GitHubConnection[]) {
  return connections.filter(({ status }) => status === "ACTIVE").flatMap(({ repositories }) => repositories.filter((repository): repository is typeof repository & { id: string } => Boolean(repository.id) && repository.status === "ACTIVE"));
}
