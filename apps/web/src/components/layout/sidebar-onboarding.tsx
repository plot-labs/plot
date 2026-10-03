"use client";

import { Check, ChevronDown, ChevronUp, X } from "lucide-react";
import { useEffect, useState } from "react";

import { OnboardingFlow } from "@/features/onboarding/onboarding-flow";
import { plotApiClient } from "@/lib/api-client";

export function SidebarOnboarding({ workspaceId }: { workspaceId: string | null }) {
  const [status, setStatus] = useState<{ connected: boolean; routine: boolean; firstRun: boolean } | null>(null);
  const [collapsed, setCollapsed] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [modalStep, setModalStep] = useState<1 | 2 | 3>(1);
  const [reloadNonce, setReloadNonce] = useState(0);

  useEffect(() => {
    if (!workspaceId) return;
    const controller = new AbortController();
    void Promise.all([
      plotApiClient.listGitHubConnections({ signal: controller.signal }),
      plotApiClient.listRoutines({ signal: controller.signal }),
    ]).then(([connections, routines]) => {
      if (controller.signal.aborted) return;
      const releaseRoutines = routines.filter((routine) => routine.cadence === "ON_GITHUB_RELEASE");
      setStatus({
        connected: connections.some((connection) => connection.status === "ACTIVE"),
        routine: releaseRoutines.length > 0,
        firstRun: releaseRoutines.some((routine) => Boolean(routine.latestExecution?.artifactId) || routine.latestExecution?.agentRunStatus === "SUCCEEDED"),
      });
    }).catch(() => {
      if (!controller.signal.aborted) setStatus({ connected: false, routine: false, firstRun: false });
    });
    return () => controller.abort();
  }, [workspaceId, reloadNonce]);

  useEffect(() => {
    if (!modalOpen) return;
    function close(event: KeyboardEvent) { if (event.key === "Escape") setModalOpen(false); }
    document.addEventListener("keydown", close);
    return () => document.removeEventListener("keydown", close);
  }, [modalOpen]);

  if (!status || status.firstRun) return null;
  const steps = [
    ["Install GitHub App", status.connected],
    ["Create first automation", status.routine],
    ["Get the first draft", status.firstRun],
  ] as const;
  const completed = steps.filter(([, done]) => done).length;
  const initialStep: 1 | 2 | 3 = !status.connected ? 1 : !status.routine ? 2 : 3;
  function openModal(step = initialStep) {
    setModalStep(step);
    setModalOpen(true);
  }

  return <>
    <div className="px-3 pb-3">
      {collapsed ? (
        <div className="glass-card rounded-[13px] border border-black/10 p-px dark:border-white/[0.08]">
          <button type="button" onClick={() => setCollapsed(false)} className="glass-control flex w-full items-center justify-start gap-2 rounded-[9px] px-2 py-1.5 text-left text-[12px]"><span className="min-w-0 flex-1 truncate">Getting started ({completed}/3)</span><ChevronUp className="size-3.5" /></button>
        </div>
      ) : (
        <div className="glass-card rounded-[16px] border border-black/10 p-px dark:border-white/[0.08]">
          <section className="relative overflow-hidden rounded-[15px]" aria-label="Getting started">
            <div className="relative flex items-center gap-2 border-b border-black/[0.06] px-3 py-2.5 dark:border-white/[0.06]"><button type="button" onClick={() => openModal()} className="glass-control min-w-0 flex-1 rounded text-left text-[12px] font-semibold tracking-[-0.01em]">Getting started</button><span className="px-1 text-[11px] tabular-nums text-black/40 dark:text-white/45">{completed}/3</span><button type="button" onClick={() => setCollapsed(true)} aria-label="Collapse getting started" className="glass-button glass-icon size-7"><ChevronDown className="size-3.5" /></button></div>
            <div className="relative p-2">{steps.map(([label, done], index) => <button key={label} type="button" onClick={() => openModal((index + 1) as 1 | 2 | 3)} className="glass-control flex w-full items-center justify-start gap-2 rounded-[9px] px-2 py-1.5 text-left text-[12px]"><span className={`flex size-4 shrink-0 items-center justify-center rounded-full border ${done ? "border-black/30 text-black/65 dark:border-white/30 dark:text-white/70" : "border-black/20 text-transparent dark:border-white/25"}`}>{done && <Check className="size-2.5" />}</span><span className={done ? "text-black/40 line-through dark:text-white/45" : "text-black/65 dark:text-white/75"}>{label}</span></button>)}</div>
          </section>
        </div>
      )}
    </div>

    {modalOpen && <div className="fixed inset-0 z-[120] flex items-center justify-center bg-black/25 p-4 backdrop-blur-[2px] dark:bg-black/55" onMouseDown={(event) => { if (event.target === event.currentTarget) setModalOpen(false); }}><section role="dialog" aria-modal="true" aria-labelledby="onboarding-modal-title" className="glass-layer relative max-h-[min(820px,calc(100dvh-32px))] w-full max-w-[600px] overflow-y-auto rounded-[22px] border border-white/75 px-7 pb-8 pt-7 dark:border-white/13 sm:px-8 sm:pb-9 sm:pt-8"><h2 id="onboarding-modal-title" className="sr-only">Set up Plot</h2><button autoFocus type="button" onClick={() => setModalOpen(false)} aria-label="Close onboarding" className="glass-button glass-icon absolute right-4 top-4 z-10 inline-flex size-8 items-center justify-center"><X className="size-4" /></button><OnboardingFlow embedded initialStep={modalStep} onComplete={() => { setModalOpen(false); setReloadNonce((value) => value + 1); }} onResultReview={() => { setModalOpen(false); setReloadNonce((value) => value + 1); }} /></section></div>}
  </>;
}
