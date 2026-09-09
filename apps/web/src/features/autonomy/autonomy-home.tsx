"use client";

import Link from "next/link";
import { openPlotConversation } from "@/features/chat/conversation-panel";
import { useEffect, useRef, useState } from "react";
import type { AutonomyHome, AutonomyHomeItem } from "@plot/api-client";
import { getSelectedWorkspaceId, plotApiClient, PlotApiError } from "@/lib/api-client";

const buttonClass = "shrink-0 rounded-lg px-3 py-2 text-sm font-medium text-black/55 transition hover:bg-black/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:opacity-40 dark:text-white/55 dark:hover:bg-white/10 dark:focus-visible:ring-white/25";

export function AutonomyHomeWorkspace({ view = "overview" }: { view?: "overview" | "activity" }) {
  const [data, setData] = useState<AutonomyHome | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const generation = useRef(0);
  const action = useRef<AbortController | null>(null);

  useEffect(() => {
    const change = () => {
      generation.current += 1;
      action.current?.abort();
      action.current = null;
      setData(null);
      setError(null);
      setBusy(null);
      setLoading(true);
      setRevision((value) => value + 1);
    };
    window.addEventListener("plot:workspace-changed", change);
    return () => {
      generation.current += 1;
      action.current?.abort();
      window.removeEventListener("plot:workspace-changed", change);
    };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const current = ++generation.current;
    const workspace = getSelectedWorkspaceId();
    const valid = () => !controller.signal.aborted && generation.current === current && workspace === getSelectedWorkspaceId();
    async function load() {
      try {
        if (!workspace) throw new Error("Select a workspace to see its updates.");
        const result = await plotApiClient.getAutonomyHome({ signal: controller.signal });
        if (valid()) setData(result);
      } catch (cause) {
        if (valid()) setError(cause instanceof Error ? cause.message : "Could not load updates.");
      } finally {
        if (valid()) setLoading(false);
      }
    }
    void load();
    return () => controller.abort();
  }, [revision]);

  function refresh() {
    setError(null);
    setLoading(true);
    setRevision((value) => value + 1);
  }

  async function changeDismissal(item: AutonomyHomeItem) {
    if (action.current) return;
    const controller = new AbortController();
    action.current = controller;
    const current = generation.current;
    const workspace = getSelectedWorkspaceId();
    const valid = () => !controller.signal.aborted && current === generation.current && workspace === getSelectedWorkspaceId();
    setBusy(item.id);
    setError(null);
    try {
      const updated = await (item.dismissed ? plotApiClient.restoreOpportunity : plotApiClient.dismissOpportunity)(item.id, item.version, { signal: controller.signal });
      if (valid()) setData((value) => value && ({ ...value, items: value.items.map((existing) => existing.id === updated.id ? updated : existing) }));
    } catch (cause) {
      if (valid()) setError(cause instanceof PlotApiError && cause.status === 409
        ? "This update has changed. Refresh before trying again."
        : "Could not save your decision. Refresh to check its current state.");
    } finally {
      if (action.current === controller) action.current = null;
      if (valid()) setBusy(null);
    }
  }

  const excluded = data?.items.filter((item) => item.dismissed || item.disposition === "EXCLUDED") ?? [];
  const visible = data?.items.filter((item) => !item.dismissed && item.disposition !== "EXCLUDED") ?? [];
  const results = visible.filter((item) => item.agentRunId);
  const pending = visible.filter((item) => !item.agentRunId);

  const surfaceClass = "overflow-hidden rounded-[14px] border border-black/[0.09] bg-white/80 shadow-[0_1px_2px_rgb(15_23_42_/_0.025)] dark:border-white/10 dark:bg-white/[0.04]";

  function rows(items: AutonomyHomeItem[]) {
    return <ul className={`${surfaceClass} mt-4 divide-y divide-black/[0.07] dark:divide-white/[0.08]`}>{items.map((item) => <li key={item.id} className="px-5 py-4 sm:px-6 sm:py-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <p className="text-xs text-black/50 dark:text-white/50">{statusLabel(item)}</p>
          <h3 className="mt-1 break-words text-[15px] font-semibold tracking-[-0.01em] text-black/82 dark:text-white/86">{item.title}</h3>
          <p className="mt-1 text-sm leading-6 text-black/52 dark:text-white/52">{item.reason}</p>
          {item.lastErrorCode && <p className="mt-2 text-sm">Assessment needs another attempt. No decision was inferred from the error.</p>}
          {item.missingFacts.length > 0 && <ul aria-label="Missing information" className="mt-2 list-disc space-y-1 pl-5 text-sm leading-6 text-black/52 dark:text-white/52">{item.missingFacts.map((fact, index) => <li key={index}>{fact}</li>)}</ul>}
          {item.dismissed && <p className="mt-2 text-xs text-black/50 dark:text-white/50">Restoring allows assessment when new evidence arrives.</p>}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {item.chatId && item.agentRunId && <button className={buttonClass} onClick={() => openPlotConversation({ chatId: item.chatId!, agentId: item.agentRunId! })}>Review and discuss</button>}
          {!item.agentRunId && <button className={buttonClass} disabled={busy !== null || loading} onClick={() => void changeDismissal(item)}>{busy === item.id ? "Saving…" : item.dismissed ? "Restore" : "Dismiss"}</button>}
        </div>
      </div>
    </li>)}</ul>;
  }

  return <section className="min-h-[calc(100dvh-49px)] overflow-y-auto bg-[#f8fafc] px-6 pb-16 pt-14 dark:bg-[#18181b] lg:h-full lg:min-h-0 lg:px-12">
    <div className="mx-auto max-w-[960px]">
    <div className="flex items-start justify-between gap-4">
      <header className="max-w-[620px]"><h1 className="font-display text-[32px] font-normal leading-[1.08] tracking-[-0.025em] text-black/90 dark:text-white/92 sm:text-[36px]">{view === "overview" ? "Overview" : "Activity"}</h1><p className="mt-2 text-sm leading-6 text-black/52 dark:text-white/52">{view === "overview" ? "What Plot is preparing and what needs your attention." : "Connected changes and the reasons to prepare, hold, or exclude an update."}</p></header>
      <button className={buttonClass} disabled={loading || busy !== null} onClick={refresh}>Refresh</button>
    </div>
    {error && <p role="alert" className="mt-6 rounded-md border border-red-500/20 p-3 text-sm">{error}</p>}
    {loading && <p role="status" className={`${surfaceClass} mt-8 px-5 py-10 text-center text-sm text-black/45 dark:text-white/45`}>Loading updates…</p>}
    {view === "overview" && <nav aria-label="Overview actions" className="mt-6 flex flex-wrap gap-3">
      <button className={buttonClass} onClick={() => openPlotConversation()}>Ask Plot</button>
      <Link className={buttonClass} href="/updates">Review updates</Link>
      <Link className={buttonClass} href="/activity">View all activity</Link>
    </nav>}
    {data && <>
      {view === "overview" && <p className="mt-3 text-xs leading-5 text-black/50 dark:text-white/50">{results.length} draft activities · {pending.length} changes under consideration · {excluded.length} excluded or dismissed</p>}
      {data.mode === "OFF" && <p className="mt-3 text-xs leading-5 text-black/50 dark:text-white/50">Automatic assessment is not enabled for this workspace. <Link className="underline" href="/settings/integrations">Manage GitHub connections</Link>.</p>}
      {data.mode === "SHADOW" && <p className="mt-3 text-xs leading-5 text-black/50 dark:text-white/50">Assessment is in observation mode. These decisions do not control existing automations yet.</p>}
      {data.mode === "ACTIVE" && <p className="mt-3 text-xs leading-5 text-black/50 dark:text-white/50">Plot assesses connected changes before drafting. Publishing still requires your review.</p>}
      {!loading && data.items.length === 0 && <div className={`${surfaceClass} mt-8 px-5 py-10 text-center text-sm leading-6 text-black/45 dark:text-white/45`}><h2 className="font-medium text-black/65 dark:text-white/65">No assessed changes yet</h2><p className="mt-2 text-sm leading-6 text-black/52 dark:text-white/52">New connected activity will appear here after assessment when enabled. Not every change needs a customer update.</p></div>}
      {results.length > 0 && <section className="mt-8" aria-label="Draft activity"><h2 className="font-semibold">Draft activity</h2><p className="mt-1 text-sm text-black/50 dark:text-white/50">Open the activity to inspect evidence and review the draft.</p>{rows(view === "overview" ? results.slice(0, 5) : results)}</section>}
      {view === "activity" && pending.length > 0 && <section className="mt-8" aria-label="Changes under consideration"><h2 className="font-semibold">Changes under consideration</h2>{rows(pending)}</section>}
      {view === "activity" && excluded.length > 0 && <details className="mt-8 border-t border-black/10 pt-4 dark:border-white/10"><summary className="cursor-pointer text-sm font-medium">Excluded and dismissed ({excluded.length})</summary>{rows(excluded)}</details>}
    </>}
    </div>
  </section>;
}

function statusLabel(item: AutonomyHomeItem) {
  if (item.dismissed) return "Dismissed";
  if (item.goalState === "FAILED") return "Draft preparation failed";
  if (item.agentRunId) return item.goalState === "SUCCEEDED" || item.goalState === "REVIEW_REQUIRED" ? "Ready for review" : "Draft activity";
  if (item.lastErrorCode) return "Assessment interrupted";
  return { EXCLUDED: "No customer update needed", ACCUMULATING: "Gathering related changes", AWAITING_EVIDENCE: "Waiting for evidence", ELIGIBLE: "Suitable for a draft" }[item.disposition];
}
