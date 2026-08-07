"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";

import { ArtifactCanvasWorkspace } from "@/features/artifacts/artifact-canvas-workspace";
import { plotApiClient, type Artifact, type ArtifactSummary } from "@/lib/api-client";

export function ArtifactsWorkspace() {
  return <Suspense fallback={null}><ArtifactsWorkspaceContent /></Suspense>;
}

function ArtifactsWorkspaceContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const requestedArtifactId = searchParams.get("artifact");
  const [remoteArtifactResult, setRemoteArtifactResult] = useState<{ requestedId: string; artifact: Artifact } | null>(null);
  const [remoteArtifactFailure, setRemoteArtifactFailure] = useState<{ requestedId: string; message: string } | null>(null);
  const [artifacts, setArtifacts] = useState<ArtifactSummary[]>([]);
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
    const controller = new AbortController();
    void plotApiClient.listArtifacts(0, 100, { signal: controller.signal })
      .then((page) => setArtifacts(page.items))
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

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
    <div className="grid min-h-[calc(100dvh-49px)] grid-cols-1 lg:h-full lg:min-h-0 lg:grid-cols-[360px_minmax(0,1fr)] lg:overflow-hidden">
      <section className="min-h-0 border-b border-black/10 bg-[#f6f7f9] px-6 pb-6 pt-14 dark:border-white/10 dark:bg-[#111113] lg:overflow-y-auto lg:border-r lg:border-b-0">
        <h1 className="font-display text-[32px] font-normal leading-none tracking-normal text-black/90 dark:text-white/92">Artifacts</h1>
        <p className="mt-1 text-sm text-black/55 dark:text-white/55">Saved results from prior requests.</p>
        <div className="mt-7 space-y-2" role="listbox" aria-label="Artifacts">
          {artifacts.map((artifact) => (
            <button
              key={artifact.id}
              type="button"
              role="option"
              aria-selected={artifact.id === requestedArtifactId}
              onClick={() => router.push(`/artifacts?artifact=${encodeURIComponent(artifact.id)}`)}
              className={`w-full rounded-[12px] border px-4 py-3.5 text-left transition ${artifact.id === requestedArtifactId ? "border-black/20 bg-white dark:border-white/20 dark:bg-white/10" : "border-black/10 bg-white/60 hover:bg-white dark:border-white/10 dark:bg-white/5"}`}
            >
              <div className="truncate font-medium text-black/82 dark:text-white/86">{artifact.title ?? "Generated artifact"}</div>
              <div className="mt-2 text-xs text-black/45 dark:text-white/45">{artifact.status}</div>
            </button>
          ))}
          {artifacts.length === 0 ? <div className="rounded-[12px] border border-dashed border-black/10 bg-white/45 p-4 text-sm leading-6 text-black/45 dark:border-white/10 dark:bg-white/[0.03] dark:text-white/45">No artifacts are available yet.</div> : null}
        </div>
      </section>

      <section className="min-h-0 min-w-0 overflow-y-auto bg-[#f8fafc] px-6 py-10 dark:bg-[#18181b] lg:px-10">
        <div className="flex h-full items-center justify-center"><div className="max-w-[300px] rounded-[12px] border border-dashed border-black/10 bg-white/45 p-5 text-sm leading-6 text-black/45 dark:border-white/10 dark:bg-white/[0.03] dark:text-white/45">Select an artifact to inspect its draft and citations.</div></div>
      </section>
    </div>
  );
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
