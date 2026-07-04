"use client";

import { useState } from "react";

import { getSourcesWorkspace } from "@/lib/api-client";

export function SourcesWorkspace() {
  const { references, drafts } = getSourcesWorkspace();
  const [selectedReferenceId, setSelectedReferenceId] = useState(references[0]?.id);
  const selectedReference = references.find((reference) => reference.id === selectedReferenceId);

  return (
    <div className="grid h-[calc(100vh-3rem)] grid-cols-[minmax(360px,420px)_1fr] overflow-hidden">
      <section className="border-r border-black/10 bg-[#f8f5ef] p-6 dark:border-white/10 dark:bg-[#141414]">
        <h1 className="text-2xl font-semibold">Sources</h1>
        <p className="mt-1 text-sm text-black/55 dark:text-white/55">
          References Plot can use when drafting updates.
        </p>

        <div className="mt-6 space-y-2">
          {references.map((reference) => (
            <button
              key={reference.id}
              type="button"
              onClick={() => setSelectedReferenceId(reference.id)}
              className={`w-full rounded-lg border p-4 text-left transition ${
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
        </div>
      </section>

      <section className="overflow-y-auto bg-[#fbfaf6] p-8 dark:bg-[#181818]">
        {selectedReference && (
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
                {drafts
                  .filter((draft) => selectedReference.usedInDraftIds.includes(draft.id))
                  .map((draft) => (
                    <div key={draft.id} className="rounded-lg border border-black/10 bg-white p-3 text-sm dark:border-white/10 dark:bg-white/5">
                      {draft.title}
                    </div>
                  ))}
              </div>
            </section>

            <section>
              <h3 className="text-sm font-semibold">Notes</h3>
              <ul className="mt-3 space-y-2 text-sm text-black/65 dark:text-white/65">
                {selectedReference.notes.map((note) => (
                  <li key={note} className="rounded-lg bg-black/5 px-3 py-2 dark:bg-white/10">
                    {note}
                  </li>
                ))}
              </ul>
            </section>
          </article>
        )}
      </section>
    </div>
  );
}
