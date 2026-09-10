"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import type { AutonomyHome, AutonomyHomeItem } from "@plot/api-client";
import { RefreshCw } from "lucide-react";
import {
  WorkspaceHeader,
  workspaceIconButtonClass,
  workspaceListClass,
  workspaceNoticeClass,
  workspacePageClass,
  workspacePrimaryButtonClass,
  workspaceSectionClass,
  workspaceTextButtonClass,
} from "@/components/layout/workspace-page";
import { getSelectedWorkspaceId, plotApiClient, PlotApiError } from "@/lib/api-client";

const buttonClass = workspaceTextButtonClass;

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

  function rows(items: AutonomyHomeItem[]) {
    return <ul className={`mt-3 ${workspaceListClass}`}>{items.map((item) => <li key={item.id} className="px-6 py-4 transition hover:bg-white/70 dark:hover:bg-white/[0.04]">
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
          {item.chatId && item.agentRunId && <Link className={buttonClass} href={`/chat?chat=${encodeURIComponent(item.chatId)}&agent=${encodeURIComponent(item.agentRunId)}`}>Review and discuss</Link>}
          {!item.agentRunId && <button className={buttonClass} disabled={busy !== null || loading} onClick={() => void changeDismissal(item)}>{busy === item.id ? "Saving…" : item.dismissed ? "Restore" : "Dismiss"}</button>}
        </div>
      </div>
    </li>)}</ul>;
  }

  return <div className={workspacePageClass}>
    <section className={workspaceSectionClass} aria-labelledby="home-heading">
    <WorkspaceHeader
      id="home-heading"
      title={view === "overview" ? "Home" : "Activity"}
      description={view === "overview" ? "What Plot is preparing and what needs your attention." : "Connected changes and the reasons to prepare, hold, or exclude an update."}
      actions={<button type="button" className={workspaceIconButtonClass} disabled={loading || busy !== null} onClick={refresh} aria-label="Refresh updates" title="Refresh updates"><RefreshCw className={`size-3.5 ${loading ? "animate-spin" : ""}`} /></button>}
    >
      {view === "overview" && <nav aria-label="Home actions" className="mt-5 flex flex-wrap items-center gap-1.5">
        <Link className={workspacePrimaryButtonClass} href="/chat">New chat</Link>
        <Link className={workspaceTextButtonClass} href="/contents">Review updates</Link>
      </nav>}
    </WorkspaceHeader>
    {error && <p role="alert" className={`mx-6 mt-4 ${workspaceNoticeClass}`}>{error}</p>}
    {loading && <p role="status" className={`mx-6 mt-4 py-8 text-center ${workspaceNoticeClass}`}>Loading updates…</p>}
    {data && <>
      <div className="px-6 pt-4">
        {view === "overview" && <p className="text-[11px] leading-4 text-black/40 dark:text-white/42">{results.length} draft activities · {pending.length} changes under consideration · {excluded.length} excluded or dismissed</p>}
        <p className="mt-1.5 text-[11px] leading-4 text-black/40 dark:text-white/42">Plot assesses connected changes before drafting. Publishing still requires your review.</p>
      </div>
      {!loading && data.items.length === 0 && <div className={`mx-6 mt-4 px-4 py-8 text-center ${workspaceNoticeClass}`}><h2 className="text-[13px] font-medium text-black/65 dark:text-white/65">No assessed changes yet</h2><p className="mt-2 text-[12px] leading-5 text-black/48 dark:text-white/50">New connected activity will appear here after assessment. Not every change needs a customer update.</p></div>}
      {results.length > 0 && <section className="mt-6" aria-label="Draft activity"><div className="px-6"><h2 className="text-[14px] font-semibold text-black/78 dark:text-white/82">Draft activity</h2><p className="mt-1 text-[12px] text-black/42 dark:text-white/45">Open the activity to inspect evidence and review the draft.</p></div>{rows(view === "overview" ? results.slice(0, 5) : results)}</section>}
      {view === "activity" && pending.length > 0 && <section className="mt-6" aria-label="Changes under consideration"><h2 className="px-6 text-[14px] font-semibold text-black/78 dark:text-white/82">Changes under consideration</h2>{rows(pending)}</section>}
      {view === "activity" && excluded.length > 0 && <details className="mt-6"><summary className="mx-6 cursor-pointer text-[12px] font-medium text-black/58 dark:text-white/60">Excluded and dismissed ({excluded.length})</summary>{rows(excluded)}</details>}
    </>}
    </section>
  </div>;
}

function statusLabel(item: AutonomyHomeItem) {
  if (item.dismissed) return "Dismissed";
  if (item.goalState === "FAILED") return "Draft preparation failed";
  if (item.agentRunId) return item.goalState === "SUCCEEDED" || item.goalState === "REVIEW_REQUIRED" ? "Ready for review" : "Draft activity";
  if (item.lastErrorCode) return "Assessment interrupted";
  return { EXCLUDED: "No customer update needed", ACCUMULATING: "Gathering related changes", AWAITING_EVIDENCE: "Waiting for evidence", ELIGIBLE: "Suitable for a draft" }[item.disposition];
}
