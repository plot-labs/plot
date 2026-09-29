"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { Add01Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";

import { workspacePageClass } from "@/components/layout/workspace-page";
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
  const requestedArtifactId = searchParams.get("artifact");
  const [remoteArtifactResult, setRemoteArtifactResult] = useState<{ requestedId: string; artifact: Artifact } | null>(null);
  const [remoteArtifactFailure, setRemoteArtifactFailure] = useState<{ requestedId: string; message: string } | null>(null);
  const [artifacts, setArtifacts] = useState<ArtifactSummary[]>([]);
  const [artifactListStatus, setArtifactListStatus] = useState<ArtifactListStatus>("loading");
  const [totalItems, setTotalItems] = useState(0);
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
    <div className={workspacePageClass}>
      <section className="mx-auto min-h-full w-full max-w-[1180px] px-6 pb-12 pt-10 sm:px-10 lg:px-12" aria-labelledby="contents-heading">
        <header className="flex flex-wrap items-start justify-between gap-5">
          <div>
            <h1 id="contents-heading" className="font-display text-[38px] leading-none tracking-[-0.025em] text-black/90 dark:text-white/92">Contents</h1>
            <p className="mt-3 text-[14px] leading-6 text-black/48 dark:text-white/50">Your customer updates, from first draft to published post.</p>
          </div>
          <Link
            href="/chat"
            aria-label="Create content"
            style={{
              background: "linear-gradient(to bottom, rgba(0, 0, 0, 0.78), rgba(0, 0, 0, 0.88))",
              backdropFilter: "saturate(200%) blur(40px)",
              WebkitBackdropFilter: "saturate(200%) blur(40px)",
              border: "1px solid rgba(255, 255, 255, 0.18)",
              boxShadow: "inset 0 1px 1px rgba(255, 255, 255, 0.25), inset 0 -1px 1px rgba(0, 0, 0, 0.1), 0 8px 24px rgba(0, 0, 0, 0.12), 0 2px 6px rgba(0, 0, 0, 0.08)",
              color: "#FFFFFF",
            }}
            className="inline-flex h-8.5 shrink-0 items-center gap-1.5 rounded-full px-3.5 text-[12px] font-semibold text-white transition-all duration-200 hover:opacity-90 active:scale-[0.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20"
          >
            <HugeiconsIcon icon={Add01Icon} size={15} color="currentColor" strokeWidth={1.5} aria-hidden="true" />
            Create
          </Link>
        </header>

        <div className="mt-10 overflow-hidden rounded-[14px] border border-black/[0.09] bg-white dark:border-white/10 dark:bg-white/[0.035]">
          <div className="hidden grid-cols-[minmax(0,1fr)_110px_145px] gap-4 border-b border-black/[0.07] bg-black/[0.025] px-6 py-3 text-[12px] font-medium text-black/45 dark:border-white/[0.08] dark:bg-white/[0.035] dark:text-white/45 md:grid">
            <span>Name</span><span>Status</span><span>Updated</span>
          </div>
          {artifactListStatus === "loading" ? (
            <ArtifactListLoading />
          ) : artifactListStatus === "error" ? (
            <div role="alert" className="px-5 py-10 text-center text-sm text-black/48 dark:text-white/48">
              Artifacts could not be loaded. Refresh the page to try again.
            </div>
          ) : artifacts.length === 0 ? (
            <div className="px-5 py-10 text-center text-sm leading-6 text-black/45 dark:text-white/45">
              No contents yet. Start in Chat to create your first draft.
            </div>
          ) : (
            <div className="divide-y divide-black/[0.07] dark:divide-white/[0.08]" aria-label="Contents">
              {artifacts.map((artifact) => {
                const updatedLabel = formatRelativeUpdatedAt(artifact.updatedAt);
                return (
                  <Link
                    key={artifact.id}
                    href={`/contents?artifact=${encodeURIComponent(artifact.id)}`}
                    className="grid min-h-[76px] grid-cols-1 gap-2 px-6 py-4 text-left transition hover:bg-black/[0.025] focus-visible:bg-black/[0.025] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-black/20 dark:hover:bg-white/[0.04] dark:focus-visible:bg-white/[0.04] dark:focus-visible:ring-white/25 md:grid-cols-[minmax(0,1fr)_110px_145px] md:items-center md:gap-4"
                  >
                    <span className="min-w-0 line-clamp-2 text-[14px] font-medium leading-5 text-black/85 dark:text-white/88" title={artifact.title ?? "Generated artifact"}>
                      {artifact.title ?? "Generated artifact"}
                    </span>
                    <span className="hidden md:block"><span className="inline-flex rounded-[5px] border border-black/10 px-2 py-0.5 text-[12px] text-black/60 dark:border-white/15 dark:text-white/62">{artifact.published === true ? "Published" : artifact.published === false ? "Draft" : "Unknown"}</span></span>
                    <span className="flex items-center gap-2 text-[12px] text-black/45 dark:text-white/45 md:block">
                      <span className="md:hidden">{artifact.published === true ? "Published" : artifact.published === false ? "Draft" : "Unknown"} · </span>
                      <time dateTime={artifact.updatedAt} title={formatAbsoluteTime(artifact.updatedAt)}>{updatedLabel}</time>
                    </span>
                  </Link>
                );
              })}
            </div>
          )}
          {artifactListStatus === "ready" && artifacts.length > 0 && (
            <div className="border-t border-black/[0.07] bg-black/[0.025] px-6 py-3 text-[12px] text-black/45 dark:border-white/[0.08] dark:bg-white/[0.035] dark:text-white/45">
              {totalItems > artifacts.length
                ? `Showing ${artifacts.length} most recently updated of ${totalItems} contents`
                : `${artifacts.length} contents`}
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

function ArtifactListLoading() {
  return (
    <div aria-label="Loading artifacts" className="divide-y divide-black/[0.07] dark:divide-white/[0.08]">
      {[0, 1, 2].map((row) => (
        <div key={row} className="flex min-h-[76px] items-center justify-between gap-6 px-6 py-5">
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

function GeneratedArtifactDetail({ artifact }: { artifact: Artifact }) {
  return (
    <ArtifactCanvasWorkspace
      artifact={artifact}
      client={plotApiClient}
      onSaveArtifact={(input) => plotApiClient.saveArtifactVariant(artifact.variant.id, input)}
    />
  );
}
