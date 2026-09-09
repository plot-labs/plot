"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import Link from "next/link";

import { ArtifactCanvasWorkspace } from "@/features/artifacts/artifact-canvas-workspace";
import { plotApiClient, type Artifact, type ArtifactSummary } from "@/lib/api-client";

type ArtifactListStatus = "error" | "loading" | "ready";

const relativeTimeFormatter = new Intl.RelativeTimeFormat("en", { numeric: "always" });
const absoluteTimeFormatter = new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" });

export function ArtifactsWorkspace() {
  return <Suspense fallback={null}><ArtifactsWorkspaceContent /></Suspense>;
}

function ArtifactsWorkspaceContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const requestedArtifactId = searchParams.get("artifact");
  const requestedView = searchParams.get("view");
  const view = requestedView === "draft" || requestedView === "published" ? requestedView : "all";
  const [remoteArtifactResult, setRemoteArtifactResult] = useState<{ requestedId: string; artifact: Artifact } | null>(null);
  const [remoteArtifactFailure, setRemoteArtifactFailure] = useState<{ requestedId: string; message: string } | null>(null);
  const [artifacts, setArtifacts] = useState<ArtifactSummary[]>([]);
  const [artifactListStatus, setArtifactListStatus] = useState<ArtifactListStatus>("loading");
  const [totalItems, setTotalItems] = useState(0);
  const visibleArtifacts = artifacts.filter((artifact) => view === "all" || (view === "published" ? artifact.published === true : artifact.published === false));
  const remoteArtifact = remoteArtifactResult?.requestedId === requestedArtifactId ? remoteArtifactResult.artifact : null;
  const remoteArtifactError = remoteArtifactFailure?.requestedId === requestedArtifactId ? remoteArtifactFailure.message : "";

  useEffect(() => {
    if (!requestedArtifactId) return;
    const controller = new AbortController();
    void plotApiClient.getArtifact(requestedArtifactId, { signal: controller.signal })
      .then((artifact) => setRemoteArtifactResult({ requestedId: requestedArtifactId, artifact }))
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) {
          setRemoteArtifactFailure({ requestedId: requestedArtifactId, message: error instanceof Error ? error.message : "The artifact could not be loaded." });
        }
      });
    return () => controller.abort();
  }, [requestedArtifactId]);

  useEffect(() => {
    if (requestedArtifactId) return;
    const controller = new AbortController();
    void plotApiClient.listArtifacts(0, 100, { signal: controller.signal })
      .then((page) => {
        setArtifacts(page.items);
        setTotalItems(page.totalItems);
        setArtifactListStatus("ready");
      })
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) {
          setArtifactListStatus("error");
        }
      });
    return () => controller.abort();
  }, [requestedArtifactId]);

  if (requestedArtifactId) {
    return (
      <div className="h-full min-h-[calc(100dvh-49px)] lg:min-h-0">
        {remoteArtifact ? <GeneratedArtifactDetail key={remoteArtifact.id} artifact={remoteArtifact} /> : remoteArtifactError ? (
          <div className="flex h-full min-h-[inherit] items-center justify-center bg-[#eef0f3] px-6"><div role="alert" className="max-w-sm rounded-xl border border-rose-300/60 bg-rose-50 p-4 text-sm text-rose-900 dark:border-rose-400/25 dark:bg-rose-400/[0.08] dark:text-rose-200">{remoteArtifactError}</div></div>
        ) : (
          <div className="flex h-full min-h-[inherit] items-center justify-center bg-[#eef0f3] text-sm text-black/45 dark:bg-[#18181b] dark:text-white/45">Loading the selected artifact…</div>
        )}
      </div>
    );
  }

  return (
    <section className="min-h-[calc(100dvh-49px)] overflow-y-auto bg-[#f8fafc] px-6 pb-16 pt-14 dark:bg-[#18181b] lg:h-full lg:min-h-0 lg:px-12">
      <div className="mx-auto max-w-[960px]">
        <header className="max-w-[620px]">
          <h1 className="font-display text-[32px] font-normal leading-[1.08] tracking-[-0.025em] text-black/90 dark:text-white/92 sm:text-[36px]">Library</h1>
          <p className="mt-2 text-sm leading-6 text-black/52 dark:text-white/52">Find your artifacts, continue a draft, or revisit published work.</p>
        </header>

        <nav aria-label="Library views" className="mt-6 flex gap-2 text-sm">
          {([ ["all", "All artifacts"], ["draft", "Draft"], ["published", "Published"] ] as const).map(([value, label]) => (
            <Link key={value} href={value === "all" ? "/artifacts" : `/artifacts?view=${value}`} aria-current={view === value ? "page" : undefined}
              className={`rounded-lg px-3 py-2 transition ${view === value ? "bg-black/10 font-medium dark:bg-white/15" : "text-black/55 hover:bg-black/5 dark:text-white/55 dark:hover:bg-white/10"}`}>
              {label}
            </Link>
          ))}
        </nav>
        <p className="mt-3 text-xs text-black/50 dark:text-white/50">Published means currently live on your public changelog. Draft means not currently published.</p>
        {artifactListStatus === "ready" && artifacts.some((artifact) => artifact.published === undefined) && <p role="status" className="mt-2 text-xs text-black/50 dark:text-white/50">Some publication statuses are unavailable. Those artifacts appear only in All artifacts.</p>}
        {totalItems > artifacts.length && <p className="mt-2 text-xs text-black/50 dark:text-white/50">Views show the {artifacts.length} most recently updated artifacts of {totalItems}.</p>}

        <div className="mt-8 overflow-hidden rounded-[14px] border border-black/[0.09] bg-white/80 shadow-[0_1px_2px_rgb(15_23_42_/_0.025)] dark:border-white/10 dark:bg-white/[0.04]">
          {artifactListStatus === "loading" ? (
            <ArtifactListLoading />
          ) : artifactListStatus === "error" ? (
            <div role="alert" className="px-5 py-10 text-center text-sm text-black/48 dark:text-white/48">
              Artifacts could not be loaded. Refresh the page to try again.
            </div>
          ) : visibleArtifacts.length === 0 ? (
            <div className="px-5 py-10 text-center text-sm leading-6 text-black/45 dark:text-white/45">
              {view === "all" ? "No artifacts yet. Start in Work to create your first draft." : view === "draft" ? "No unpublished drafts in this view." : "No published artifacts in this view. Publish a reviewed changelog from its editor."}
            </div>
          ) : (
            <div className="divide-y divide-black/[0.07] dark:divide-white/[0.08]" role="listbox" aria-label="Artifacts">
              {visibleArtifacts.map((artifact) => {
                const updatedLabel = formatRelativeUpdatedAt(artifact.updatedAt);
                return (
                  <button
                    key={artifact.id}
                    type="button"
                    role="option"
                    aria-selected="false"
                    onClick={() => router.push(`/artifacts?artifact=${encodeURIComponent(artifact.id)}`)}
                    className="grid w-full grid-cols-1 gap-3 px-5 py-4 text-left transition hover:bg-black/[0.025] focus-visible:bg-black/[0.025] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-black/20 active:bg-black/[0.045] dark:hover:bg-white/[0.045] dark:focus-visible:bg-white/[0.045] dark:focus-visible:ring-white/25 dark:active:bg-white/[0.07] sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center sm:px-6 sm:py-5"
                  >
                    <span className="min-w-0 truncate text-[15px] font-semibold tracking-[-0.01em] text-black/82 dark:text-white/86">
                      {artifact.title ?? "Generated artifact"}
                    </span>
                    <span className="flex shrink-0 items-center gap-3 text-xs text-black/42 dark:text-white/42">
                      <span>{artifact.published === true ? "Published" : artifact.published === false ? "Draft" : "Publication unknown"}</span>
                      <span>{contentTypeLabel(artifact.contentType)}</span>
                      <time dateTime={artifact.updatedAt} title={formatAbsoluteTime(artifact.updatedAt)}>
                        {updatedLabel}
                      </time>
                    </span>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </section>
  );
}

function ArtifactListLoading() {
  return (
    <div aria-label="Loading artifacts" className="divide-y divide-black/[0.07] dark:divide-white/[0.08]">
      {[0, 1, 2].map((row) => (
        <div key={row} className="flex items-center justify-between gap-6 px-6 py-5">
          <div className="h-4 min-w-0 w-full max-w-[320px] flex-1 animate-pulse rounded bg-black/[0.07] dark:bg-white/10" />
          <div className="h-3 w-24 animate-pulse rounded bg-black/[0.05] dark:bg-white/[0.07]" />
        </div>
      ))}
    </div>
  );
}

function formatRelativeUpdatedAt(value: string, now = Date.now()) {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return "Updated recently";

  const deltaMs = timestamp - now;
  const absoluteDelta = Math.abs(deltaMs);
  if (absoluteDelta < 60_000) return "Updated just now";

  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ["year", 365 * 24 * 60 * 60 * 1_000],
    ["month", 30 * 24 * 60 * 60 * 1_000],
    ["week", 7 * 24 * 60 * 60 * 1_000],
    ["day", 24 * 60 * 60 * 1_000],
    ["hour", 60 * 60 * 1_000],
    ["minute", 60 * 1_000],
  ];
  const [unit, unitMs] = units.find(([, milliseconds]) => absoluteDelta >= milliseconds) ?? units[units.length - 1]!;
  const relativeValue = Math.round(deltaMs / unitMs);
  return `Updated ${relativeTimeFormatter.format(relativeValue, unit)}`;
}

function formatAbsoluteTime(value: string) {
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? absoluteTimeFormatter.format(timestamp) : "Update time unavailable";
}

function contentTypeLabel(contentType: ArtifactSummary["contentType"]) {
  return contentType === "LAUNCH_ANNOUNCEMENT" ? "Launch announcement" : "Changelog";
}

function GeneratedArtifactDetail({ artifact }: { artifact: Artifact }) {
  return (
    <ArtifactCanvasWorkspace
      artifact={artifact}
      client={plotApiClient}
      onSaveArtifact={(input) => plotApiClient.saveArtifactVariant(artifact.variant.id, input)}
    />
  );
}
