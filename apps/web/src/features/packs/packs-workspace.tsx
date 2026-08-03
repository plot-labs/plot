"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";

import { CitedDraftEditor } from "@/features/citations/cited-draft-editor";
import { ExportDialog } from "@/features/citations/export-dialog";
import {
  plotApiClient,
  type ContentPack,
  type ContentPackSummary,
} from "@/lib/api-client";

export function PacksWorkspace() {
  return (
    <Suspense fallback={null}>
      <PacksWorkspaceContent />
    </Suspense>
  );
}

function PacksWorkspaceContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const requestedPackId = searchParams.get("pack");
  const [remotePackResult, setRemotePackResult] = useState<{
    requestedId: string;
    pack: ContentPack;
  } | null>(null);
  const [remotePackFailure, setRemotePackFailure] = useState<{
    requestedId: string;
    message: string;
  } | null>(null);
  const [generatedPacks, setGeneratedPacks] = useState<ContentPackSummary[]>([]);
  const remotePack =
    remotePackResult?.requestedId === requestedPackId
      ? remotePackResult.pack
      : null;
  const remotePackError =
    remotePackFailure?.requestedId === requestedPackId
      ? remotePackFailure.message
      : "";

  useEffect(() => {
    if (!requestedPackId) return;

    const controller = new AbortController();
    void plotApiClient
      .getContentPack(requestedPackId, { signal: controller.signal })
      .then((pack) =>
        setRemotePackResult({ requestedId: requestedPackId, pack }),
      )
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) {
          setRemotePackFailure({
            requestedId: requestedPackId,
            message:
              error instanceof Error
                ? error.message
                : "The generated pack could not be loaded.",
          });
        }
      });
    return () => controller.abort();
  }, [requestedPackId]);

  useEffect(() => {
    const controller = new AbortController();
    void plotApiClient
      .listContentPacks(0, 100, { signal: controller.signal })
      .then((page) => setGeneratedPacks(page.items))
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

  return (
    <div className="grid min-h-screen grid-cols-1 lg:h-screen lg:grid-cols-[360px_minmax(0,1fr)] lg:overflow-hidden">
      <section className="min-h-0 border-b border-black/10 bg-[#f6f7f9] px-6 pb-6 pt-14 dark:border-white/10 dark:bg-[#111113] lg:overflow-y-auto lg:border-r lg:border-b-0">
        <h1 className="font-serif text-[32px] font-normal leading-none tracking-normal text-black/90 dark:text-white/92">
          Packs
        </h1>
        <p className="mt-1 text-sm text-black/55 dark:text-white/55">
          Saved results from prior requests.
        </p>

        <div className="mt-7 space-y-2" role="listbox" aria-label="Packs">
          {generatedPacks.map((pack) => (
            <button
              key={pack.id}
              type="button"
              role="option"
              aria-selected={pack.id === requestedPackId}
              onClick={() =>
                router.push(`/packs?pack=${encodeURIComponent(pack.id)}`)
              }
              className={`w-full rounded-[12px] border px-4 py-3.5 text-left transition ${
                pack.id === requestedPackId
                  ? "border-black/20 bg-white dark:border-white/20 dark:bg-white/10"
                  : "border-black/10 bg-white/60 hover:bg-white dark:border-white/10 dark:bg-white/5"
              }`}
            >
              <div className="truncate font-medium text-black/82 dark:text-white/86">
                {pack.title ?? "Generated changelog"}
              </div>
              <div className="mt-2 text-xs text-black/45 dark:text-white/45">
                {pack.status}
              </div>
            </button>
          ))}
          {generatedPacks.length === 0 && (
            <div className="rounded-[12px] border border-dashed border-black/10 bg-white/45 p-4 text-sm leading-6 text-black/45 dark:border-white/10 dark:bg-white/[0.03] dark:text-white/45">
              No packs are available yet.
            </div>
          )}
        </div>
      </section>

      <section className="min-h-0 min-w-0 overflow-y-auto bg-[#f8fafc] px-6 py-10 dark:bg-[#18181b] lg:px-10">
        {remotePack ? (
          <GeneratedPackDetail
            pack={remotePack}
            onPackChange={(pack) =>
              setRemotePackResult({ requestedId: requestedPackId!, pack })
            }
          />
        ) : remotePackError ? (
          <div className="flex h-full items-center justify-center">
            <div
              role="alert"
              className="max-w-sm rounded-xl border border-rose-300/60 bg-rose-50 p-4 text-sm text-rose-900 dark:border-rose-400/25 dark:bg-rose-400/[0.08] dark:text-rose-200"
            >
              {remotePackError}
            </div>
          </div>
        ) : (
          <div className="flex h-full items-center justify-center">
            <div className="max-w-[300px] rounded-[12px] border border-dashed border-black/10 bg-white/45 p-5 text-sm leading-6 text-black/45 dark:border-white/10 dark:bg-white/[0.03] dark:text-white/45">
              {requestedPackId
                ? "Loading the selected pack…"
                : "Select a pack to inspect its draft and citations."}
            </div>
          </div>
        )}
      </section>
    </div>
  );
}

function GeneratedPackDetail({
  pack,
  onPackChange,
}: {
  pack: ContentPack;
  onPackChange: (pack: ContentPack) => void;
}) {
  return (
    <article className="mx-auto max-w-4xl space-y-4 pt-4 sm:pt-10">
      <div className="rounded-xl border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/[0.04]">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="min-w-0">
            <div className="text-xs font-semibold uppercase tracking-[0.08em] text-black/42 dark:text-white/42">
              Generated pack
            </div>
            <h1 className="mt-2 text-2xl font-semibold text-black/88 dark:text-white/90">
              {pack.title ?? "Generated changelog"}
            </h1>
            <p className="mt-1 text-sm text-black/52 dark:text-white/52">
              {pack.status}
            </p>
          </div>
          <ExportDialog pack={pack} client={plotApiClient} />
        </div>
      </div>
      <CitedDraftEditor
        pack={pack}
        onEditSentence={(sentence, body) =>
          plotApiClient.editSentence(pack.variant.id, sentence.id, {
            expectedRevisionNumber: sentence.revisionNumber,
            body,
          })
        }
        onPackChange={onPackChange}
      />
    </article>
  );
}
