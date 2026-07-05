"use client";

import { FileText, X } from "lucide-react";
import { useState } from "react";

import { getSourcesWorkspace } from "@/lib/api-client";

export function SourcesWorkspace() {
  const { references, drafts } = getSourcesWorkspace();
  const [selectedReferenceId, setSelectedReferenceId] = useState<string | null>(null);
  const selectedReference = references.find((reference) => reference.id === selectedReferenceId);
  const usedDrafts = selectedReference
    ? drafts.filter((draft) => selectedReference.usedInDraftIds.includes(draft.id))
    : [];

  return (
    <div className="grid min-h-screen grid-cols-1 lg:h-screen lg:grid-cols-[380px_minmax(0,1fr)] lg:overflow-hidden">
      <section className="min-h-0 border-b border-black/10 bg-[#f6f7f9] px-6 pb-6 pt-14 dark:border-white/10 dark:bg-[#111113] lg:overflow-y-auto lg:border-r lg:border-b-0">
        <h1 className="font-serif text-[32px] font-normal leading-none tracking-normal text-black/90 dark:text-white/92">
          Sources
        </h1>
        <p className="mt-1 text-sm text-black/55 dark:text-white/55">
          References Plot can use when drafting updates.
        </p>

        <div className="mt-7 space-y-2" role="listbox" aria-label="Sources">
          {references.map((reference) => (
            <button
              key={reference.id}
              type="button"
              role="option"
              aria-selected={reference.id === selectedReferenceId}
              onClick={() => setSelectedReferenceId(reference.id)}
              className={`w-full rounded-[12px] border px-4 py-3.5 text-left transition ${
                reference.id === selectedReferenceId
                  ? "border-black/20 bg-white dark:border-white/20 dark:bg-white/10"
                  : "border-black/10 bg-white/60 hover:bg-white dark:border-white/10 dark:bg-white/5 dark:hover:bg-white/10"
              }`}
            >
              <div className="flex items-center justify-between gap-3">
                <div className="font-medium text-black/82 dark:text-white/86">{reference.label}</div>
                <div className="rounded-[8px] bg-black/5 px-2 py-1 text-xs text-black/55 dark:bg-white/10 dark:text-white/55">
                  {reference.status}
                </div>
              </div>
              <div className="mt-1 text-sm text-black/55 dark:text-white/55">{reference.title}</div>
              <div className="mt-3 flex items-center justify-between text-xs text-black/40 dark:text-white/40">
                <span>{reference.sourceType}</span>
                <span>{reference.date}</span>
              </div>
            </button>
          ))}
          {references.length === 0 && (
            <div className="rounded-[12px] border border-black/10 bg-white/60 p-4 text-sm text-black/55 dark:border-white/10 dark:bg-white/5 dark:text-white/55">
              No sources are available yet.
            </div>
          )}
        </div>
      </section>

      <section className="min-h-0 min-w-0 overflow-y-auto bg-[#f8fafc] px-6 py-10 dark:bg-[#18181b] lg:px-10">
        {selectedReference ? (
          <article className="relative mx-auto max-w-3xl space-y-4 pt-10">
            <button
              type="button"
              onClick={() => setSelectedReferenceId(null)}
              aria-label="Close source detail"
              className="absolute right-0 top-0 inline-flex size-8 shrink-0 items-center justify-center rounded-xl text-black/45 transition hover:bg-black/5 hover:text-black/70 dark:text-white/45 dark:hover:bg-white/10 dark:hover:text-white/75"
            >
              <X className="size-4" />
            </button>
            <div className="rounded-[12px] border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/5">
              <div className="max-w-[calc(100%-48px)]">
                <div className="min-w-0">
                  <div className="text-xs font-medium text-black/40 dark:text-white/40">
                    {selectedReference.sourceType}
                  </div>
                  <h2 className="mt-2 text-[28px] font-semibold leading-tight tracking-normal text-black/88 dark:text-white/90">
                    {selectedReference.title}
                  </h2>
                  <p className="mt-1 text-sm text-black/55 dark:text-white/55">
                    {selectedReference.label} · {selectedReference.date}
                  </p>
                </div>
              </div>

              <p className="mt-5 text-sm leading-6 text-black/70 dark:text-white/70">
                {selectedReference.summary}
              </p>
            </div>

            <section>
              <h3 className="text-sm font-semibold">Used in drafts</h3>
              <div className="mt-3 grid gap-2">
                {usedDrafts.map((draft) => (
                  <div key={draft.id} className="rounded-[12px] border border-black/10 bg-white p-3 text-sm dark:border-white/10 dark:bg-white/5">
                    <div className="flex items-center gap-2">
                      <FileText className="size-4 shrink-0 text-black/35 dark:text-white/40" />
                      <div className="font-medium">{draft.title}</div>
                    </div>
                    <div className="mt-1 text-black/50 dark:text-white/50">{draft.status}</div>
                  </div>
                ))}
                {usedDrafts.length === 0 && (
                  <div className="rounded-[12px] border border-black/10 bg-white p-3 text-sm text-black/55 dark:border-white/10 dark:bg-white/5 dark:text-white/55">
                    This source has not been used in drafts yet.
                  </div>
                )}
              </div>
            </section>

            <section>
              <h3 className="text-sm font-semibold">Notes</h3>
              <ul className="mt-3 space-y-2 text-sm text-black/65 dark:text-white/65">
                {selectedReference.notes.map((note) => (
                  <li key={note} className="rounded-[12px] bg-black/5 px-3 py-2 dark:bg-white/10">
                    {note}
                  </li>
                ))}
              </ul>
            </section>
          </article>
        ) : (
          <div className="flex h-full items-center justify-center">
            <div className="max-w-[300px] rounded-[12px] border border-dashed border-black/10 bg-white/45 p-5 text-sm leading-6 text-black/45 dark:border-white/10 dark:bg-white/[0.03] dark:text-white/45">
              Select a source to inspect its summary and linked drafts.
            </div>
          </div>
        )}
      </section>
    </div>
  );
}
