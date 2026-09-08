"use client";

import Link from "next/link";
import { FormEvent } from "react";
import { LoaderCircle } from "lucide-react";

import {
  formatReleaseActivityDetail,
  formatReleaseActivityLabel,
  isFullCommitSha,
  isReleaseActivityInFlight,
  normalizeCommitSha,
} from "./release-activity-utils";
import { useRoutineReleaseActivity } from "./use-routine-release-activity";

type RoutineReleaseActivityProps = {
  sourceScopeId: string;
  routineName: string;
  releaseRequestId?: string | null;
};

export function RoutineReleaseActivity({
  sourceScopeId,
  routineName,
  releaseRequestId = null,
}: RoutineReleaseActivityProps) {
  const { activity, isLoading, error, retry, retrying, selectRange, savingRange } =
    useRoutineReleaseActivity(sourceScopeId, releaseRequestId);

  if (!activity && !isLoading && !error) return null;

  const inFlight = activity ? isReleaseActivityInFlight(activity.status) : false;
  const showSpinner = isLoading || retrying || savingRange || inFlight;
  const label = activity ? formatReleaseActivityLabel(activity) : null;
  const detail = activity ? formatReleaseActivityDetail(activity) : null;
  const pinnedHead = Boolean(activity?.headSha);

  function submitRange(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!activity || activity.status !== "NEEDS_RANGE") return;
    const data = new FormData(event.currentTarget);
    const nextBase = normalizeCommitSha(String(data.get("baseSha") ?? ""));
    const nextHead = normalizeCommitSha(String(data.get("headSha") ?? activity.headSha ?? ""));
    if (!isFullCommitSha(nextBase) || !isFullCommitSha(nextHead)) return;
    void selectRange({ baseSha: nextBase, headSha: nextHead });
  }

  return (
    <div
      role="status"
      aria-label={`Latest release for ${routineName}`}
      className="mt-2 flex items-start justify-between gap-3 text-[11px] text-black/38 dark:text-white/40"
    >
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-1.5">
          {showSpinner ? <LoaderCircle className="size-3 shrink-0 animate-spin" aria-hidden="true" /> : null}
          <span>
            {isLoading && !activity ? "Latest release: Loading…" : `Latest release: ${label}`}
          </span>
        </p>
        {detail ? <p className="mt-1 leading-4 text-black/45 dark:text-white/45">{detail}</p> : null}
        {activity?.status === "NEEDS_RANGE" ? (
          <form key={activity.id} onSubmit={submitRange} className="mt-2 space-y-1.5">
            {pinnedHead ? (
              <>
                <p className="truncate font-mono text-[10px] text-black/35 dark:text-white/38">
                  Tag head {activity.headSha}
                </p>
                <input type="hidden" name="headSha" value={activity.headSha ?? ""} />
              </>
            ) : (
              <label className="block">
                <span className="sr-only">Head commit SHA</span>
                <input
                  name="headSha"
                  defaultValue=""
                  spellCheck={false}
                  autoComplete="off"
                  placeholder="Head commit SHA"
                  aria-label={`Head commit SHA for ${routineName}`}
                  className="h-7 w-full rounded-[7px] border border-black/10 bg-white px-2 font-mono text-[11px] text-black/70 outline-none focus:border-black/25 dark:border-white/12 dark:bg-white/[0.06] dark:text-white/80"
                />
              </label>
            )}
            <label className="block">
              <span className="sr-only">Previous commit SHA</span>
              <input
                name="baseSha"
                defaultValue={activity.baseSha ?? ""}
                spellCheck={false}
                autoComplete="off"
                placeholder="Previous commit SHA"
                aria-label={`Previous commit SHA for ${routineName}`}
                className="h-7 w-full rounded-[7px] border border-black/10 bg-white px-2 font-mono text-[11px] text-black/70 outline-none focus:border-black/25 dark:border-white/12 dark:bg-white/[0.06] dark:text-white/80"
              />
            </label>
            <button
              type="submit"
              disabled={savingRange}
              aria-label={`Generate draft from range for ${routineName}`}
              className="inline-flex h-7 items-center rounded-[7px] px-2 text-[11px] font-medium text-black/55 transition hover:bg-black/[0.04] hover:text-black/78 disabled:cursor-wait disabled:opacity-50 dark:text-white/58 dark:hover:bg-white/10 dark:hover:text-white/82"
            >
              Generate draft
            </button>
          </form>
        ) : null}
        {error ? <p role="alert" className="mt-1 text-black/55 dark:text-white/60">{error}</p> : null}
      </div>
      <div className="flex shrink-0 items-center gap-1">
        {activity?.status === "READY" && activity.artifactId ? (
          <Link
            href={`/artifacts?artifact=${encodeURIComponent(activity.artifactId)}`}
            aria-label={`Open artifact for ${routineName} release ${activity.tagName}`}
            className="inline-flex h-7 items-center rounded-[7px] px-2 text-[11px] font-medium text-black/55 transition hover:bg-black/[0.04] hover:text-black/78 dark:text-white/58 dark:hover:bg-white/10 dark:hover:text-white/82"
          >
            Open artifact
          </Link>
        ) : null}
        {activity?.status === "FAILED" ? (
          <button
            type="button"
            onClick={() => { void retry(); }}
            disabled={retrying}
            aria-label={`Retry release draft for ${routineName}`}
            className="inline-flex h-7 items-center rounded-[7px] px-2 text-[11px] font-medium text-black/55 transition hover:bg-black/[0.04] hover:text-black/78 disabled:cursor-wait disabled:opacity-50 dark:text-white/58 dark:hover:bg-white/10 dark:hover:text-white/82"
          >
            Retry
          </button>
        ) : null}
      </div>
    </div>
  );
}
