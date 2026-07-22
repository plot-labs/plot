"use client";

import { ExternalLink, LoaderCircle, RefreshCw, X } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";

import { plotApiClient, type GenerationReference } from "@/lib/api-client";

export function SourcesWorkspace() {
  const [sources, setSources] = useState<GenerationReference[]>([]);
  const [selectedSourceId, setSelectedSourceId] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [message, setMessage] = useState<string | null>(null);
  const [reloadNonce, setReloadNonce] = useState(0);
  const selectedSource = sources.find((source) => source.id === selectedSourceId) ?? null;

  const retry = () => {
    setMessage(null);
    setIsLoading(true);
    setReloadNonce((value) => value + 1);
  };

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const nextSources = await plotApiClient.listGenerationReferences();
        if (cancelled) return;
        setSources(nextSources);
        setSelectedSourceId((current) => nextSources.some((source) => source.id === current) ? current : null);
      } catch {
        if (!cancelled) setMessage("Could not load Writing Blocks. Try again.");
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }

    queueMicrotask(() => { void load(); });
    return () => { cancelled = true; };
  }, [reloadNonce]);

  return (
    <div className="grid min-h-screen grid-cols-1 lg:h-screen lg:grid-cols-[380px_minmax(0,1fr)] lg:overflow-hidden">
      <section className="min-h-0 border-b border-black/10 bg-[#f6f7f9] px-6 pb-6 pt-14 dark:border-white/10 dark:bg-[#111113] lg:overflow-y-auto lg:border-r lg:border-b-0">
        <h1 className="font-serif text-[32px] font-normal leading-none tracking-normal text-black/90 dark:text-white/92">Sources</h1>
        <p className="mt-1 text-sm text-black/55 dark:text-white/55">Writing Blocks Plot can use as evidence.</p>

        {message && (
          <div role="alert" className="mt-5 rounded-[10px] border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-400/30 dark:bg-amber-500/10 dark:text-amber-100">
            <div>{message}</div>
            <button type="button" onClick={retry} className="mt-2 inline-flex items-center gap-2 font-semibold underline underline-offset-2">
              <RefreshCw className="size-3.5" /> Retry
            </button>
          </div>
        )}

        {isLoading ? <Loading /> : sources.length > 0 ? (
          <div className="mt-7 space-y-2" role="listbox" aria-label="Writing Blocks">
            {sources.map((source) => (
              <button
                key={source.id}
                type="button"
                role="option"
                aria-selected={source.id === selectedSourceId}
                onClick={() => setSelectedSourceId(source.id)}
                className={`w-full rounded-[12px] border px-4 py-3.5 text-left transition ${source.id === selectedSourceId ? "border-black/20 bg-white dark:border-white/20 dark:bg-white/10" : "border-black/10 bg-white/60 hover:bg-white dark:border-white/10 dark:bg-white/5 dark:hover:bg-white/10"}`}
              >
                <div className="font-medium text-black/82 dark:text-white/86">{source.title || "Untitled pull request"}</div>
                <div className="mt-1 text-sm text-black/55 dark:text-white/55">{source.repositoryLabel}</div>
                <div className="mt-3 text-xs text-black/40 dark:text-white/40">{formatDate(source.sourceCreatedAt)}</div>
              </button>
            ))}
          </div>
        ) : (
          <div className="mt-7 rounded-[12px] border border-dashed border-black/10 bg-white/60 p-5 text-sm leading-5 text-black/55 dark:border-white/10 dark:bg-white/5 dark:text-white/55">
            <div>No Writing Blocks have been collected yet.</div>
            <Link href="/integrations" className="mt-3 inline-flex font-semibold text-black/72 underline underline-offset-4 dark:text-white/75">
              Set up GitHub in Integrations
            </Link>
          </div>
        )}
      </section>

      <section className="min-h-0 min-w-0 overflow-y-auto bg-[#f8fafc] px-6 py-10 dark:bg-[#18181b] lg:px-10">
        {selectedSource ? (
          <article className="relative mx-auto max-w-3xl space-y-4 pt-10">
            <button type="button" onClick={() => setSelectedSourceId(null)} aria-label="Close source detail" className="absolute right-0 top-0 inline-flex size-8 items-center justify-center rounded-xl text-black/45 hover:bg-black/5 dark:text-white/45 dark:hover:bg-white/10">
              <X className="size-4" />
            </button>
            <div className="rounded-[12px] border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/5">
              <div className="text-xs font-medium text-black/40 dark:text-white/40">Pull request · {selectedSource.repositoryLabel}</div>
              <h2 className="mt-2 text-[28px] font-semibold leading-tight text-black/88 dark:text-white/90">{selectedSource.title || "Untitled pull request"}</h2>
              <p className="mt-1 text-sm text-black/55 dark:text-white/55">{formatDate(selectedSource.sourceCreatedAt)}</p>
              {selectedSource.body && <p className="mt-5 whitespace-pre-wrap text-sm leading-6 text-black/70 dark:text-white/70">{selectedSource.body}</p>}
              {selectedSource.originalUrl && (
                <a href={selectedSource.originalUrl} target="_blank" rel="noreferrer" className="mt-5 inline-flex items-center gap-2 text-sm font-medium text-black/70 underline underline-offset-4 dark:text-white/75">
                  View on GitHub <ExternalLink className="size-3.5" />
                </a>
              )}
            </div>
          </article>
        ) : (
          <div className="flex h-full items-center justify-center">
            <div className="max-w-[300px] rounded-[12px] border border-dashed border-black/10 bg-white/45 p-5 text-sm leading-6 text-black/45 dark:border-white/10 dark:bg-white/[0.03] dark:text-white/45">
              Select a Writing Block to inspect its original pull request.
            </div>
          </div>
        )}
      </section>
    </div>
  );
}

function Loading() {
  return (
    <div className="mt-7 flex items-center gap-2 text-sm text-black/55 dark:text-white/55">
      <LoaderCircle className="size-4 animate-spin" /> Loading Writing Blocks…
    </div>
  );
}

function formatDate(value: string | null) {
  return value ? new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(new Date(value)) : "No date";
}
