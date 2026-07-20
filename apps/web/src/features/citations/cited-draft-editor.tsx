"use client";

import { Pencil, X } from "lucide-react";
import { useState } from "react";

import type { ContentPack, GenerationSentence } from "@plot/api-client";
import { InlineCitation } from "./inline-citation";

type CitedDraftEditorProps = {
  pack: ContentPack;
  onEditSentence: (sentence: GenerationSentence, body: string) => Promise<ContentPack>;
  onPackChange?: (pack: ContentPack) => void;
};

export function CitedDraftEditor({ pack, onEditSentence, onPackChange }: CitedDraftEditorProps) {
  const [editedPack, setEditedPack] = useState<ContentPack | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editBody, setEditBody] = useState("");
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");

  const currentPack = editedPack?.id === pack.id ? editedPack : pack;
  const sentences = [...currentPack.variant.sentences]
    .filter((sentence) =>
      sentence.verdict === "SUPPORTED" ||
      sentence.verdict === "NOT_REQUIRED" ||
      sentence.verdict === "USER_MODIFIED"
    )
    .sort((a, b) => a.orderIndex - b.orderIndex);

  function beginEdit(sentence: GenerationSentence) {
    setEditingId(sentence.id);
    setEditBody(sentence.body);
    setMessage("");
  }

  function cancelEdit() {
    setEditingId(null);
    setEditBody("");
    setMessage("Edit canceled.");
  }

  async function save(sentence: GenerationSentence) {
    const body = editBody.trim();
    if (!body || saving) return;
    setSaving(true);
    setMessage("");
    try {
      const updated = await onEditSentence(sentence, body);
      setEditedPack(updated);
      onPackChange?.(updated);
      setEditingId(null);
      setMessage(`Sentence ${sentence.orderIndex + 1} saved and marked unverified.`);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "The sentence could not be saved.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <section aria-label="Cited draft" className="rounded-xl border border-black/10 bg-white dark:border-white/10 dark:bg-white/[0.04]">
      <ol className="space-y-4 px-4 py-5 sm:px-6">
        {sentences.map((sentence) => {
          const number = sentence.orderIndex + 1;
          const editing = editingId === sentence.id;
          return (
            <li key={sentence.id} data-sentence-id={sentence.id} className="group">
              {editing ? (
                <div>
                  <label htmlFor={`sentence-edit-${sentence.id}`} className="text-xs font-semibold text-black/55 dark:text-white/55">
                    Sentence {number} text
                  </label>
                  <textarea
                    id={`sentence-edit-${sentence.id}`}
                    autoFocus
                    value={editBody}
                    onChange={(event) => setEditBody(event.target.value)}
                    className="mt-2 min-h-24 w-full resize-y rounded-lg border border-black/15 bg-white p-3 text-[15px] leading-6 outline-none focus:border-black/45 dark:border-white/15 dark:bg-[#18181b] dark:focus:border-white/45"
                  />
                  <div className="mt-2 flex flex-wrap gap-2">
                    <button type="button" disabled={saving || !editBody.trim()} onClick={() => void save(sentence)} className="min-h-10 rounded-lg bg-black px-3 text-sm font-semibold text-white disabled:opacity-40 dark:bg-white dark:text-black" aria-label={`Save sentence ${number}`}>
                      {saving ? "Saving…" : "Save"}
                    </button>
                    <button type="button" disabled={saving} onClick={cancelEdit} className="inline-flex min-h-10 items-center gap-1 rounded-lg border border-black/10 px-3 text-sm font-medium dark:border-white/15" aria-label={`Cancel sentence ${number} edit`}>
                      <X className="size-4" /> Cancel
                    </button>
                  </div>
                </div>
              ) : (
                <div className="flex items-start gap-3">
                  <div className="min-w-0 flex-1">
                    <p className="text-[15px] leading-7 text-black/82 dark:text-white/82">
                      {sentence.body}{" "}
                      {sentence.verdict === "SUPPORTED"
                        ? sentence.citations.filter((citation) => citation.status !== "REMOVED").map((citation) => (
                            <InlineCitation key={citation.evidenceId} citation={citation} />
                          ))
                        : null}
                    </p>
                  </div>
                  <button type="button" onClick={() => beginEdit(sentence)} className="inline-flex size-10 shrink-0 items-center justify-center rounded-lg text-black/42 opacity-100 hover:bg-black/5 hover:text-black/75 dark:text-white/42 dark:hover:bg-white/10 dark:hover:text-white/75 sm:opacity-0 sm:group-hover:opacity-100 sm:focus-visible:opacity-100" aria-label={`Edit sentence ${number}`}>
                    <Pencil className="size-4" />
                  </button>
                </div>
              )}
            </li>
          );
        })}
        {sentences.length === 0 ? (
          <li className="text-sm leading-6 text-black/55 dark:text-white/55">
            No source-backed claims were publishable from the selected references.
          </li>
        ) : null}
      </ol>
      {message ? (
        <p role="status" className="border-t border-black/[0.07] px-4 py-3 text-xs text-black/58 dark:border-white/10 dark:text-white/58 sm:px-6" aria-live="polite">
          {message}
        </p>
      ) : null}
    </section>
  );
}
