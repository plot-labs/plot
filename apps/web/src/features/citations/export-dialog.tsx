"use client";

import { Check, Copy, Download, ShieldAlert, X } from "lucide-react";
import { useState } from "react";

import { PlotApiError, type ContentPack, type PlotApiClient } from "@plot/api-client";

type Disposition = "COPY" | "DOWNLOAD";

export function ExportDialog({ pack, client }: { pack: ContentPack; client: PlotApiClient }) {
  const [pending, setPending] = useState<Disposition | null>(null);
  const [confirmation, setConfirmation] = useState<{ disposition: Disposition; sentenceIds: string[]; revisionIds: string[] } | null>(null);
  const [message, setMessage] = useState("");

  async function requestExport(disposition: Disposition, acknowledgeUnresolved: boolean, acknowledgedRevisionIds: string[] = []) {
    if (pending) return;
    setPending(disposition);
    setMessage("");
    try {
      const result = await client.exportVariant(pack.variant.id, { acknowledgeUnresolved, acknowledgedRevisionIds, disposition });
      if (disposition === "COPY") {
        await navigator.clipboard.writeText(result.text);
      } else {
        downloadText(result.text, result.filename, result.mediaType);
      }
      setConfirmation(null);
      setMessage(disposition === "COPY" ? "Changelog copied." : "Changelog downloaded.");
    } catch (error) {
      if (error instanceof PlotApiError && error.code === "EXPORT_CONFIRMATION_REQUIRED") {
        const sentenceIds = Array.isArray(error.details?.sentenceIds)
          ? error.details.sentenceIds.filter((id): id is string => typeof id === "string")
          : [];
        const revisionIds = Array.isArray(error.details?.revisionIds)
          ? error.details.revisionIds.filter((id): id is string => typeof id === "string")
          : [];
        setConfirmation({ disposition, sentenceIds, revisionIds });
        setMessage("Explicit confirmation is required before export.");
      } else {
        setMessage(error instanceof Error ? error.message : "The changelog could not be exported.");
      }
    } finally {
      setPending(null);
    }
  }

  const affectedSentences = confirmation?.sentenceIds
    .map((id) => pack.variant.sentences.find((sentence) => sentence.id === id))
    .filter((sentence) => sentence !== undefined) ?? [];

  return (
    <section aria-label="Export changelog" className="flex min-w-0 flex-col items-end gap-2 sm:max-w-[24rem]">
      <div className="flex items-center gap-1.5">
        <button
          type="button"
          disabled={Boolean(pending)}
          onClick={() => void requestExport("COPY", false)}
          title="Copy changelog"
          aria-label="Copy changelog"
          className="inline-flex size-10 items-center justify-center rounded-lg text-black/45 transition hover:bg-black/5 hover:text-black/75 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 focus-visible:ring-offset-2 focus-visible:ring-offset-white disabled:pointer-events-none disabled:opacity-40 dark:text-white/45 dark:hover:bg-white/10 dark:hover:text-white/75 dark:focus-visible:ring-offset-[#18181b]"
        >
          <Copy aria-hidden="true" className="size-4" />
        </button>
        <button
          type="button"
          disabled={Boolean(pending)}
          onClick={() => void requestExport("DOWNLOAD", false)}
          title="Download changelog"
          aria-label="Download changelog"
          className="inline-flex size-10 items-center justify-center rounded-lg text-black/45 transition hover:bg-black/5 hover:text-black/75 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 focus-visible:ring-offset-2 focus-visible:ring-offset-white disabled:pointer-events-none disabled:opacity-40 dark:text-white/45 dark:hover:bg-white/10 dark:hover:text-white/75 dark:focus-visible:ring-offset-[#18181b]"
        >
          <Download aria-hidden="true" className="size-4" />
        </button>
      </div>

      {confirmation ? (
        <div role="alertdialog" aria-labelledby="export-warning-title" aria-describedby="export-warning-description" className="w-full rounded-lg border border-amber-300/70 bg-amber-50 p-3 dark:border-amber-400/25 dark:bg-amber-400/[0.07]">
          <div className="flex items-start gap-2">
            <ShieldAlert className="mt-0.5 size-4 shrink-0 text-amber-700 dark:text-amber-300" />
            <div className="min-w-0 flex-1">
              <h3 id="export-warning-title" className="text-sm font-semibold">Unresolved sentences will be exported</h3>
              <div id="export-warning-description" className="mt-1 text-xs leading-5 text-black/62 dark:text-white/62">
                <p>Review affected sentences before continuing.</p>
                {affectedSentences.length ? (
                  <ul className="mt-2 space-y-1">
                    {affectedSentences.map((sentence) => (
                      <li key={sentence.id}>
                        <button
                          type="button"
                          onClick={() => focusSentence(sentence.id)}
                          className="block max-w-full truncate rounded-sm text-left font-medium underline underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500 focus-visible:ring-offset-1"
                        >
                          Sentence {sentence.orderIndex + 1} — “{sentence.body}”
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="mt-1">Affected sentence details are unavailable.</p>
                )}
              </div>
            </div>
            <button
              type="button"
              onClick={() => setConfirmation(null)}
              className="inline-flex size-9 shrink-0 items-center justify-center rounded-lg text-black/55 transition hover:bg-black/5 hover:text-black/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500 focus-visible:ring-offset-1 dark:text-white/55 dark:hover:bg-white/10 dark:hover:text-white/85"
              aria-label="Cancel export warning"
              title="Cancel export warning"
            >
              <X aria-hidden="true" className="size-4" />
            </button>
          </div>
          <button autoFocus type="button" disabled={Boolean(pending)} onClick={() => void requestExport(confirmation.disposition, true, confirmation.revisionIds)} className="mt-3 inline-flex min-h-11 items-center gap-2 rounded-lg bg-amber-950 px-3 text-sm font-semibold text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500 focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-40 dark:bg-amber-200 dark:text-amber-950">
            <Check aria-hidden="true" className="size-4" /> Confirm and {confirmation.disposition === "COPY" ? "copy" : "download"}
          </button>
        </div>
      ) : null}
      {message ? <p role="status" className="max-w-full text-right text-xs text-black/58 dark:text-white/58" aria-live="polite">{message}</p> : null}
    </section>
  );
}

function focusSentence(sentenceId: string) {
  const sentence = document.getElementById(`sentence-${sentenceId}`);
  sentence?.scrollIntoView?.({ block: "center", behavior: "smooth" });
  sentence?.focus();
}

function downloadText(text: string, filename: string, mediaType: string) {
  const url = URL.createObjectURL(new Blob([text], { type: mediaType }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}
