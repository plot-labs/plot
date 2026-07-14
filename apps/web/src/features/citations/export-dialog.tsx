"use client";

import { Check, Copy, Download, ShieldAlert, X } from "lucide-react";
import { useState } from "react";

import { PlotApiError, type ContentPack, type PlotApiClient } from "@plot/api-client";

type Disposition = "COPY" | "DOWNLOAD";

export function ExportDialog({ pack, client }: { pack: ContentPack; client: PlotApiClient }) {
  const [pending, setPending] = useState<Disposition | null>(null);
  const [confirmation, setConfirmation] = useState<{ disposition: Disposition; sentenceIds: string[] } | null>(null);
  const [message, setMessage] = useState("");

  async function requestExport(disposition: Disposition, acknowledgeUnresolved: boolean) {
    if (pending) return;
    setPending(disposition);
    setMessage("");
    try {
      const result = await client.exportVariant(pack.variant.id, { acknowledgeUnresolved, disposition });
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
        setConfirmation({ disposition, sentenceIds });
        setMessage("Explicit confirmation is required before export.");
      } else {
        setMessage(error instanceof Error ? error.message : "The changelog could not be exported.");
      }
    } finally {
      setPending(null);
    }
  }

  return (
    <section aria-label="Export changelog" className="rounded-xl border border-black/10 bg-white p-4 dark:border-white/10 dark:bg-white/[0.04]">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 className="text-sm font-semibold">Export changelog</h2>
          <p className="mt-1 text-xs leading-5 text-black/52 dark:text-white/52">Copy and download use the same reviewed export.</p>
        </div>
        <div className="flex gap-2">
          <button type="button" disabled={Boolean(pending)} onClick={() => void requestExport("COPY", false)} className="inline-flex min-h-11 flex-1 items-center justify-center gap-2 rounded-lg bg-black px-3 text-sm font-semibold text-white disabled:opacity-40 dark:bg-white dark:text-black sm:flex-none" aria-label="Copy changelog">
            <Copy className="size-4" /> Copy
          </button>
          <button type="button" disabled={Boolean(pending)} onClick={() => void requestExport("DOWNLOAD", false)} className="inline-flex min-h-11 flex-1 items-center justify-center gap-2 rounded-lg border border-black/12 px-3 text-sm font-semibold disabled:opacity-40 dark:border-white/15 sm:flex-none" aria-label="Download changelog">
            <Download className="size-4" /> Download
          </button>
        </div>
      </div>

      {confirmation ? (
        <div role="alertdialog" aria-labelledby="export-warning-title" aria-describedby="export-warning-description" className="mt-4 rounded-lg border border-amber-300/70 bg-amber-50 p-3 dark:border-amber-400/25 dark:bg-amber-400/[0.07]">
          <div className="flex items-start gap-2">
            <ShieldAlert className="mt-0.5 size-4 shrink-0 text-amber-700 dark:text-amber-300" />
            <div className="min-w-0 flex-1">
              <h3 id="export-warning-title" className="text-sm font-semibold">Unresolved sentences will be exported</h3>
              <p id="export-warning-description" className="mt-1 text-xs leading-5 text-black/62 dark:text-white/62">
                Review affected sentence IDs: {confirmation.sentenceIds.length ? confirmation.sentenceIds.join(", ") : "unknown"}.
              </p>
            </div>
            <button type="button" onClick={() => setConfirmation(null)} className="inline-flex size-9 items-center justify-center rounded-lg" aria-label="Cancel export warning"><X className="size-4" /></button>
          </div>
          <button autoFocus type="button" disabled={Boolean(pending)} onClick={() => void requestExport(confirmation.disposition, true)} className="mt-3 inline-flex min-h-11 items-center gap-2 rounded-lg bg-amber-950 px-3 text-sm font-semibold text-white disabled:opacity-40 dark:bg-amber-200 dark:text-amber-950">
            <Check className="size-4" /> Confirm and {confirmation.disposition === "COPY" ? "copy" : "download"}
          </button>
        </div>
      ) : null}
      {message ? <p className="mt-3 text-xs text-black/58 dark:text-white/58" aria-live="polite">{message}</p> : null}
    </section>
  );
}

function downloadText(text: string, filename: string, mediaType: string) {
  const url = URL.createObjectURL(new Blob([text], { type: mediaType }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}
