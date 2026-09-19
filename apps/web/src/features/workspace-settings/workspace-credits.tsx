"use client";

import { useEffect, useMemo, useState } from "react";
import { LoaderCircle } from "lucide-react";

import {
  getSelectedWorkspaceId,
  plotApiClient,
  type CreditUsageEvent,
  type WorkspaceCreditOverview,
} from "@/lib/api-client";

const numberFormat = new Intl.NumberFormat("en-US");
const dateFormat = new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" });

export function WorkspaceCredits() {
  const [overview, setOverview] = useState<WorkspaceCreditOverview | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadNonce, setReloadNonce] = useState(0);

  useEffect(() => {
    const handleWorkspaceChanged = () => {
      setOverview(null);
      setError(null);
      setIsLoading(true);
      setReloadNonce((value) => value + 1);
    };

    window.addEventListener("plot:workspace-changed", handleWorkspaceChanged);
    return () => window.removeEventListener("plot:workspace-changed", handleWorkspaceChanged);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const workspaceId = getSelectedWorkspaceId();
    if (!workspaceId) {
      queueMicrotask(() => {
        if (cancelled) return;
        setError("Select a workspace to view its credits.");
        setIsLoading(false);
      });
      return () => { cancelled = true; };
    }

    plotApiClient.getCreditOverview()
      .then((value) => {
        if (!cancelled) setOverview(value);
      })
      .catch(() => {
        if (!cancelled) setError("Credit balance could not be loaded.");
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => { cancelled = true; };
  }, [reloadNonce]);

  const usageEvents = useMemo(
    () => [...(overview?.usageEvents ?? [])].sort((left, right) => right.timestamp.localeCompare(left.timestamp)),
    [overview],
  );
  const usagePercent = overview && overview.creditedUnits > 0
    ? Math.min(100, Math.max(0, (overview.consumedUnits / overview.creditedUnits) * 100))
    : 0;

  return (
    <div className="h-full overflow-y-auto bg-[#f4f6f8] px-5 py-8 dark:bg-[#101112] sm:px-8 sm:py-10 lg:px-10">
      <div className="mx-auto max-w-[760px] pb-16">
        <header className="max-w-[620px]">
          <h1 className="font-display text-[32px] font-normal leading-[1.08] tracking-[-0.025em] text-black/90 dark:text-white/92 sm:text-[36px]">
            Credits
          </h1>
          <p className="mt-2 text-[14px] leading-6 text-black/52 dark:text-white/50">
            Monitor your AI credit balance and usage.
          </p>
        </header>

        {isLoading ? (
          <div className="mt-8 flex items-center gap-2 text-sm text-black/45 dark:text-white/45" role="status">
            <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
            Loading credits…
          </div>
        ) : error ? (
          <p className="mt-8 text-sm text-red-700 dark:text-red-300" role="alert">{error}</p>
        ) : overview ? (
          <>
            <section className="mt-8 grid gap-4 sm:grid-cols-3" aria-label="Credit summary">
              <CreditSummaryCard label="Current Balance" value={`${numberFormat.format(overview.balance)} credits`} />
              <CreditSummaryCard label="Used This Period" value={`${numberFormat.format(overview.consumedUnits)} credits`} />
              <CreditSummaryCard label="Usage" value={formatPercent(usagePercent)} />
            </section>

            <section className="mt-8 overflow-hidden rounded-[14px] border border-black/[0.09] bg-white shadow-[0_1px_2px_rgb(15_23_42_/_0.025)] dark:border-white/10 dark:bg-white/[0.045]" aria-labelledby="credit-usage-heading">
              <div className="border-b border-black/[0.07] px-5 py-5 dark:border-white/[0.08] sm:px-6">
                <h2 id="credit-usage-heading" className="text-[15px] font-semibold text-black/82 dark:text-white/86">Usage</h2>
                <p className="mt-1 text-[13px] leading-5 text-black/48 dark:text-white/48">
                  Credits used by this workspace during the current billing period.
                </p>
                <div className="mt-5 h-2 overflow-hidden rounded-full bg-black/[0.07] dark:bg-white/[0.10]" role="progressbar" aria-label="Credit usage" aria-valuemin={0} aria-valuemax={100} aria-valuenow={usagePercent}>
                  <div className="h-full rounded-full bg-[#ef3f2c] transition-[width]" style={{ width: `${usagePercent}%` }} />
                </div>
                <div className="mt-2 flex justify-between text-[12px] text-black/45 dark:text-white/45">
                  <span>{numberFormat.format(overview.consumedUnits)} used</span>
                  <span>{numberFormat.format(overview.creditedUnits)} credited</span>
                </div>
              </div>

              <div className="px-5 py-5 sm:px-6">
                <h3 className="text-[13px] font-semibold text-black/70 dark:text-white/72">Recent activity</h3>
                {usageEvents.length ? (
                  <div className="mt-4 overflow-x-auto">
                    <table className="w-full min-w-[520px] text-left text-[13px]">
                      <thead className="text-[11px] font-medium uppercase tracking-[0.08em] text-black/38 dark:text-white/38">
                        <tr>
                          <th className="pb-3 pr-4 font-medium">Date</th>
                          <th className="pb-3 pr-4 font-medium">Model</th>
                          <th className="pb-3 text-right font-medium">Credits</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-black/[0.06] dark:divide-white/[0.08]">
                        {usageEvents.map((event) => <UsageEventRow key={event.id} event={event} />)}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p className="mt-4 rounded-[10px] border border-dashed border-black/10 px-4 py-8 text-center text-[13px] text-black/45 dark:border-white/12 dark:text-white/45">
                    No usage events yet.
                  </p>
                )}
              </div>
            </section>

            <p className="mt-4 text-[12px] leading-5 text-black/42 dark:text-white/42">
              Credits are shared by this workspace and deducted from measured AI usage after each completed model call.
            </p>
          </>
        ) : null}
      </div>
    </div>
  );
}

function CreditSummaryCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[14px] border border-black/[0.09] bg-white px-5 py-5 shadow-[0_1px_2px_rgb(15_23_42_/_0.025)] dark:border-white/10 dark:bg-white/[0.045]">
      <p className="text-[12px] font-medium text-black/45 dark:text-white/45">{label}</p>
      <p className="mt-3 text-[20px] font-semibold tracking-[-0.02em] text-black/82 dark:text-white/86">{value}</p>
    </div>
  );
}

function UsageEventRow({ event }: { event: CreditUsageEvent }) {
  return (
    <tr>
      <td className="py-3 pr-4 text-black/55 dark:text-white/55">{formatDate(event.timestamp)}</td>
      <td className="max-w-[260px] truncate py-3 pr-4 font-medium text-black/72 dark:text-white/72" title={event.model ?? undefined}>
        {event.model ?? event.provider ?? "AI usage"}
      </td>
      <td className="py-3 text-right font-medium text-black/72 dark:text-white/72">{numberFormat.format(event.credits)}</td>
    </tr>
  );
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unknown" : dateFormat.format(date);
}

function formatPercent(value: number) {
  if (value === 0) return "0%";
  if (value < 0.01) return "<0.01%";
  return `${value.toFixed(value < 1 ? 2 : 0)}%`;
}
