"use client";

import { useState } from "react";

import { getPacksWorkspace } from "@/lib/api-client";

export function PacksWorkspace() {
  const { packs, drafts, references } = getPacksWorkspace();
  const [selectedPackId, setSelectedPackId] = useState(packs[0]?.id);
  const selectedPack = packs.find((pack) => pack.id === selectedPackId);
  const packDrafts = drafts.filter((draft) => selectedPack?.draftIds.includes(draft.id));
  const selectedDraft = packDrafts[0];
  const usedReferences = references.filter((reference) => selectedDraft?.referenceIds.includes(reference.id));

  return (
    <div className="grid h-[calc(100vh-3rem)] grid-cols-[360px_1fr] overflow-hidden">
      <section className="border-r border-black/10 bg-[#f8f5ef] p-6 dark:border-white/10 dark:bg-[#141414]">
        <h1 className="text-2xl font-semibold">Packs</h1>
        <p className="mt-1 text-sm text-black/55 dark:text-white/55">
          Saved results from prior requests.
        </p>

        <div className="mt-6 space-y-2">
          {packs.map((pack) => (
            <button
              key={pack.id}
              type="button"
              onClick={() => setSelectedPackId(pack.id)}
              className={`w-full rounded-lg border p-4 text-left transition ${
                pack.id === selectedPackId
                  ? "border-black/20 bg-white dark:border-white/20 dark:bg-white/10"
                  : "border-black/10 bg-white/60 hover:bg-white dark:border-white/10 dark:bg-white/5 dark:hover:bg-white/10"
              }`}
            >
              <div className="font-medium">{pack.title}</div>
              <div className="mt-1 text-sm text-black/55 dark:text-white/55">{pack.request}</div>
              <div className="mt-3 flex items-center justify-between text-xs text-black/45 dark:text-white/45">
                <span>{pack.status}</span>
                <span>{pack.updatedAt}</span>
              </div>
            </button>
          ))}
        </div>
      </section>

      <section className="overflow-y-auto bg-[#fbfaf6] p-8 dark:bg-[#181818]">
        {selectedPack && selectedDraft && (
          <article className="mx-auto max-w-4xl space-y-6">
            <div>
              <div className="text-xs uppercase text-black/45 dark:text-white/45">Saved result</div>
              <h2 className="mt-2 text-3xl font-semibold">{selectedPack.title}</h2>
              <p className="mt-1 text-sm text-black/55 dark:text-white/55">{selectedPack.request}</p>
            </div>

            <div className="grid gap-3 md:grid-cols-2">
              {packDrafts.map((draft) => (
                <div key={draft.id} className="rounded-lg border border-black/10 bg-white p-4 dark:border-white/10 dark:bg-white/5">
                  <div className="font-medium">{draft.title}</div>
                  <div className="mt-1 text-sm text-black/55 dark:text-white/55">{draft.status}</div>
                </div>
              ))}
            </div>

            <section className="rounded-xl border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/5">
              <div className="text-sm font-semibold">{selectedDraft.filename}</div>
              <div className="mt-4 whitespace-pre-line text-sm leading-6 text-black/75 dark:text-white/75">
                {selectedDraft.body}
              </div>
            </section>

            <section>
              <h3 className="text-sm font-semibold">References</h3>
              <div className="mt-3 grid gap-2">
                {usedReferences.map((reference) => (
                  <div key={reference.id} className="rounded-lg border border-black/10 bg-white p-3 text-sm dark:border-white/10 dark:bg-white/5">
                    <div className="font-medium">{reference.label}</div>
                    <div className="mt-1 text-black/55 dark:text-white/55">{reference.summary}</div>
                  </div>
                ))}
              </div>
            </section>
          </article>
        )}
      </section>
    </div>
  );
}
