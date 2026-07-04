"use client";

import { X } from "lucide-react";
import { useState } from "react";

import { getPacksWorkspace } from "@/lib/api-client";

export function PacksWorkspace() {
  const { packs, drafts, references } = getPacksWorkspace();

  function getFirstDraftIdForPack(packId: string | undefined) {
    const pack = packs.find((item) => item.id === packId);

    return pack?.draftIds.find((draftId) => drafts.some((draft) => draft.id === draftId));
  }

  const [selectedPackId, setSelectedPackId] = useState<string | null>(null);
  const [selectedDraftId, setSelectedDraftId] = useState<string | undefined>(undefined);
  const selectedPack = packs.find((pack) => pack.id === selectedPackId);
  const packDrafts = drafts.filter((draft) => selectedPack?.draftIds.includes(draft.id));
  const selectedDraft = packDrafts.find((draft) => draft.id === selectedDraftId);
  const usedReferences = references.filter((reference) => selectedDraft?.referenceIds.includes(reference.id));

  function selectPack(packId: string) {
    setSelectedPackId(packId);
    setSelectedDraftId(getFirstDraftIdForPack(packId));
  }

  function closePackDetail() {
    setSelectedPackId(null);
    setSelectedDraftId(undefined);
  }

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
          {packs.map((pack) => (
            <button
              key={pack.id}
              type="button"
              role="option"
              aria-selected={pack.id === selectedPackId}
              onClick={() => selectPack(pack.id)}
              className={`w-full rounded-[12px] border px-4 py-3.5 text-left transition ${
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
          {packs.length === 0 && (
            <div className="rounded-[12px] border border-black/10 bg-white/60 p-4 text-sm text-black/55 dark:border-white/10 dark:bg-white/5 dark:text-white/55">
              No packs are available yet.
            </div>
          )}
        </div>
      </section>

      <section className="min-h-0 min-w-0 overflow-y-auto bg-[#f8fafc] px-6 py-10 dark:bg-[#18181b] lg:px-10">
        {selectedPack ? (
          <article className="mx-auto max-w-4xl space-y-6">
            <div className="flex items-start justify-between gap-4">
              <div>
                <div className="text-xs uppercase text-black/45 dark:text-white/45">Saved result</div>
                <h2 className="mt-2 text-3xl font-semibold">{selectedPack.title}</h2>
                <p className="mt-1 text-sm text-black/55 dark:text-white/55">{selectedPack.request}</p>
              </div>
              <button
                type="button"
                onClick={closePackDetail}
                aria-label="Close pack detail"
                className="inline-flex size-8 shrink-0 items-center justify-center rounded-xl text-black/45 transition hover:bg-black/5 hover:text-black/70 dark:text-white/45 dark:hover:bg-white/10 dark:hover:text-white/75"
              >
                <X className="size-4" />
              </button>
            </div>

            <div className="grid gap-3 md:grid-cols-2" role="tablist" aria-label="Drafts in pack">
              {packDrafts.map((draft) => (
                <button
                  key={draft.id}
                  type="button"
                  id={`draft-tab-${draft.id}`}
                  role="tab"
                  aria-selected={draft.id === selectedDraftId}
                  aria-controls={`draft-panel-${draft.id}`}
                  onClick={() => setSelectedDraftId(draft.id)}
                  className={`rounded-[12px] border p-4 text-left transition ${
                    draft.id === selectedDraftId
                      ? "border-black/20 bg-white dark:border-white/20 dark:bg-white/10"
                      : "border-black/10 bg-white/70 hover:bg-white dark:border-white/10 dark:bg-white/5 dark:hover:bg-white/10"
                  }`}
                >
                  <div className="font-medium">{draft.title}</div>
                  <div className="mt-1 text-sm text-black/55 dark:text-white/55">{draft.status}</div>
                </button>
              ))}
              {packDrafts.length === 0 && (
                <div className="rounded-[12px] border border-black/10 bg-white p-4 text-sm text-black/55 dark:border-white/10 dark:bg-white/5 dark:text-white/55">
                  This pack does not include any drafts yet.
                </div>
              )}
            </div>

            {selectedDraft ? (
              <section
                id={`draft-panel-${selectedDraft.id}`}
                role="tabpanel"
                aria-labelledby={`draft-tab-${selectedDraft.id}`}
                className="rounded-[12px] border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/5"
              >
                <div className="text-sm font-semibold">{selectedDraft.filename}</div>
                <div className="mt-4 whitespace-pre-line text-sm leading-6 text-black/75 dark:text-white/75">
                  {selectedDraft.body}
                </div>
              </section>
            ) : (
              <section className="rounded-[12px] border border-black/10 bg-white p-5 text-sm text-black/55 dark:border-white/10 dark:bg-white/5 dark:text-white/55">
                Select a draft to preview its content.
              </section>
            )}

            <section>
              <h3 className="text-sm font-semibold">References</h3>
              <div className="mt-3 grid gap-2">
                {usedReferences.map((reference) => (
                  <div key={reference.id} className="rounded-[12px] border border-black/10 bg-white p-3 text-sm dark:border-white/10 dark:bg-white/5">
                    <div className="font-medium">{reference.label}</div>
                    <div className="mt-1 text-black/55 dark:text-white/55">{reference.summary}</div>
                  </div>
                ))}
                {usedReferences.length === 0 && (
                  <div className="rounded-[12px] border border-black/10 bg-white p-3 text-sm text-black/55 dark:border-white/10 dark:bg-white/5 dark:text-white/55">
                    No references are linked to this draft.
                  </div>
                )}
              </div>
            </section>
          </article>
        ) : (
          <div className="h-full" aria-hidden="true" />
        )}
      </section>
    </div>
  );
}
