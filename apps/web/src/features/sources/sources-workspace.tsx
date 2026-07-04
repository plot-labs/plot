"use client";

import { useState } from "react";

import { getSourcesWorkspace } from "@/lib/api-client";

export function SourcesWorkspace() {
  const { references, drafts } = getSourcesWorkspace();
  const [selectedReferenceId, setSelectedReferenceId] = useState(references[0]?.id);
  const selectedReference = references.find((reference) => reference.id === selectedReferenceId);
  const usedDrafts = selectedReference
    ? drafts.filter((draft) => selectedReference.usedInDraftIds.includes(draft.id))
    : [];

  return (
    <div className="grid min-h-screen grid-cols-1 lg:h-screen lg:grid-cols-[minmax(320px,420px)_minmax(0,1fr)] lg:overflow-hidden">
      <section className="min-h-0 border-b border-black/10 bg-[#f6f7f9] p-6 dark:border-white/10 dark:bg-[#111113] lg:overflow-y-auto lg:border-r lg:border-b-0">
        <h1 className="font-serif text-[38px] font-normal leading-tight tracking-normal text-black/90 dark:text-white/92">
          Sources
        </h1>
        <p className="mt-1 text-sm text-black/55 dark:text-white/55">
          References Plot can use when drafting updates.
        </p>

        <div className="mt-6 space-y-2" role="listbox" aria-label="Sources">
          {references.map((reference) => (
            <button
              key={reference.id}
              type="button"
              role="option"
              aria-selected={reference.id === selectedReferenceId}
              onClick={() => setSelectedReferenceId(reference.id)}
              className={`w-full rounded-[12px] border p-4 text-left transition ${
                reference.id === selectedReferenceId
                  ? "border-black/20 bg-white dark:border-white/20 dark:bg-white/10"
                  : "border-black/10 bg-white/60 hover:bg-white dark:border-white/10 dark:bg-white/5 dark:hover:bg-white/10"
              }`}
            >
              <div className="flex items-center justify-between gap-3">
                <div className="font-medium">{reference.label}</div>
                <div className="rounded-full bg-black/5 px-2 py-1 text-xs text-black/55 dark:bg-white/10 dark:text-white/55">
                  {reference.status}
                </div>
              </div>
              <div className="mt-1 text-sm text-black/55 dark:text-white/55">{reference.title}</div>
            </button>
          ))}
          {references.length === 0 && (
            <div className="rounded-[12px] border border-black/10 bg-white/60 p-4 text-sm text-black/55 dark:border-white/10 dark:bg-white/5 dark:text-white/55">
              No sources are available yet.
            </div>
          )}
        </div>
      </section>

      <section className="min-h-0 min-w-0 overflow-y-auto bg-[#f8fafc] p-6 dark:bg-[#18181b] lg:p-8">
        {selectedReference ? (
          <article className="mx-auto max-w-3xl space-y-6">
            <div>
              <div className="text-xs uppercase text-black/45 dark:text-white/45">
                {selectedReference.sourceType}
              </div>
              <h2 className="mt-2 text-3xl font-semibold">{selectedReference.title}</h2>
              <p className="mt-1 text-sm text-black/55 dark:text-white/55">
                {selectedReference.label} · {selectedReference.date}
              </p>
            </div>

            <p className="text-sm leading-6 text-black/70 dark:text-white/70">
              {selectedReference.summary}
            </p>

            <section>
              <h3 className="text-sm font-semibold">Used in drafts</h3>
              <div className="mt-3 grid gap-2">
                {usedDrafts.map((draft) => (
                  <div key={draft.id} className="rounded-[12px] border border-black/10 bg-white p-3 text-sm dark:border-white/10 dark:bg-white/5">
                    {draft.title}
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
          <div className="mx-auto max-w-3xl py-16 text-sm text-black/55 dark:text-white/55">
            {references.length === 0
              ? "No sources are available yet."
              : "Select a source to review its summary and linked drafts."}
          </div>
        )}
      </section>
    </div>
  );
}
