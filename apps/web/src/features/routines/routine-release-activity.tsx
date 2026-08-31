"use client";

import Link from "next/link";
import { LoaderCircle } from "lucide-react";

import {
  formatReleaseActivityDetail,
  formatReleaseActivityLabel,
  isReleaseActivityInFlight,
} from "./release-activity-utils";
import { useRoutineReleaseActivity } from "./use-routine-release-activity";

type RoutineReleaseActivityProps = {
  sourceScopeId: string;
  routineName: string;
};

export function RoutineReleaseActivity({ sourceScopeId, routineName }: RoutineReleaseActivityProps) {
  const { activity, isLoading, error, retry, retrying } = useRoutineReleaseActivity(sourceScopeId);

  if (!activity && !isLoading && !error) return null;

  const inFlight = activity ? isReleaseActivityInFlight(activity.status) : false;
  const showSpinner = isLoading || retrying || inFlight;
  const label = activity ? formatReleaseActivityLabel(activity) : null;
  const detail = activity ? formatReleaseActivityDetail(activity) : null;

  return (
    <div
      role="status"
      aria-label={`Latest release for ${routineName}`}
      className="mt-2 flex items-start justify-between gap-3 text-[11px] text-black/38 dark:text-white/40"
    >
      <div className="min-w-0">
        <p className="flex items-center gap-1.5">
          {showSpinner ? <LoaderCircle className="size-3 shrink-0 animate-spin" aria-hidden="true" /> : null}
          <span>
            {isLoading && !activity ? "Latest release: Loading…" : `Latest release: ${label}`}
          </span>
        </p>
        {detail ? <p className="mt-1 leading-4 text-black/45 dark:text-white/45">{detail}</p> : null}
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
