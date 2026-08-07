"use client";

import { Check, Copy, Download, ShieldAlert, X } from "lucide-react";
import { useState } from "react";

import { PlotApiError, type Artifact, type PlotApiClient } from "@plot/api-client";

type Disposition = "COPY" | "DOWNLOAD";
type ExportWarning = { key: string; sentenceNumber: number; excerpt: string };

export function ExportDialog({ pack, client, presentation = "buttons" }: { pack: Artifact; client: PlotApiClient; presentation?: "buttons" | "menu" }) {
  const [pending, setPending] = useState<Disposition | null>(null);
  const [includeSources, setIncludeSources] = useState(false);
  const [confirmation, setConfirmation] = useState<{ disposition: Disposition; warnings: ExportWarning[] } | null>(null);
  const [message, setMessage] = useState("");

  async function requestExport(disposition: Disposition, acknowledgeUnresolved: boolean, acknowledgedWarningKeys: string[] = []) {
    if (pending) return;
    setPending(disposition);
    setMessage("");
    try {
      const result = await client.exportArtifactVariant(pack.variant.id, {
        expectedRevisionNumber: pack.variant.revisionNumber,
        includeSources,
        acknowledgeUnresolved,
        acknowledgedWarningKeys,
        disposition,
      });
      if (disposition === "COPY") {
        await navigator.clipboard.writeText(result.text);
      } else {
        downloadText(result.text, result.filename, result.mediaType);
      }
      setConfirmation(null);
      setMessage(disposition === "COPY" ? "Artifact copied." : "Artifact downloaded.");
    } catch (error) {
      if (error instanceof PlotApiError && error.code === "EXPORT_CONFIRMATION_REQUIRED") {
        const warnings = Array.isArray(error.details?.warnings)
          ? error.details.warnings.filter(isExportWarning)
          : [];
        setConfirmation({ disposition, warnings });
        setMessage("Explicit confirmation is required before export.");
      } else {
        setMessage(error instanceof Error ? error.message : "The artifact could not be exported.");
      }
    } finally {
      setPending(null);
    }
  }

  if (presentation === "menu") {
    return (
      <div role="none" className="relative border-t border-black/[0.06] pt-1 dark:border-white/10">
        <button
          type="button"
          role="menuitemcheckbox"
          aria-checked={includeSources}
          onClick={() => setIncludeSources((current) => !current)}
          className="flex h-8 w-full items-center gap-2 rounded-[4px] px-2.5 text-left text-xs text-black/58 transition hover:bg-black/[0.04] focus-visible:bg-black/[0.04] focus-visible:outline-none dark:text-white/58 dark:hover:bg-white/10 dark:focus-visible:bg-white/10"
        >
          <span aria-hidden="true" className="inline-flex size-3.5 items-center justify-center rounded-[3px] border border-black/20 dark:border-white/20">
            {includeSources ? <Check className="size-2.5" /> : null}
          </span>
          Sources in Markdown
        </button>
        <button type="button" role="menuitem" disabled={Boolean(pending)} onClick={() => void requestExport("COPY", false)} className="flex h-8 w-full items-center gap-2 rounded-[4px] px-2.5 text-left transition hover:bg-black/[0.04] focus-visible:bg-black/[0.04] focus-visible:outline-none disabled:opacity-40 dark:hover:bg-white/10 dark:focus-visible:bg-white/10">
          <Copy aria-hidden="true" className="size-4" /> Copy Markdown
        </button>
        <button type="button" role="menuitem" disabled={Boolean(pending)} onClick={() => void requestExport("DOWNLOAD", false)} className="flex h-8 w-full items-center gap-2 rounded-[4px] px-2.5 text-left transition hover:bg-black/[0.04] focus-visible:bg-black/[0.04] focus-visible:outline-none disabled:opacity-40 dark:hover:bg-white/10 dark:focus-visible:bg-white/10">
          <Download aria-hidden="true" className="size-4" /> Download Markdown
        </button>
        {confirmation ? <ExportConfirmation confirmation={confirmation} pending={pending} pack={pack} onCancel={() => setConfirmation(null)} onConfirm={() => void requestExport(confirmation.disposition, true, confirmation.warnings.map((warning) => warning.key))} /> : null}
        {message ? <p role="status" aria-live="polite" className="px-2.5 py-1 text-xs text-black/58 dark:text-white/58">{message}</p> : null}
      </div>
    );
  }

  return (
    <section aria-label="Export artifact" className="flex min-w-0 flex-col items-end gap-2 sm:max-w-[24rem]">
      <label className="inline-flex min-h-10 items-center gap-2 self-end text-xs font-medium text-black/58 dark:text-white/58">
        <input
          type="checkbox"
          checked={includeSources}
          onChange={(event) => setIncludeSources(event.target.checked)}
          className="size-4 rounded border-black/20 accent-black dark:border-white/20 dark:accent-white"
        />
        Include Sources in Markdown
      </label>
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
              <h3 id="export-warning-title" className="text-sm font-semibold">Unresolved statements will be exported</h3>
              <div id="export-warning-description" className="mt-1 text-xs leading-5 text-black/62 dark:text-white/62">
                <p>Review affected statements before continuing.</p>
                {confirmation.warnings.length ? (
                  <ul className="mt-2 space-y-1">
                    {confirmation.warnings.map((warning) => (
                      <li key={warning.key}>
                        <button
                          type="button"
                          onClick={() => focusStatement(pack, warning.sentenceNumber)}
                          className="block max-w-full truncate rounded-sm text-left font-medium underline underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500 focus-visible:ring-offset-1"
                        >
                          Statement {warning.sentenceNumber} — “{warning.excerpt}”
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="mt-1">Affected statement details are unavailable.</p>
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
          <button
            autoFocus
            type="button"
            disabled={Boolean(pending)}
            onClick={() => void requestExport(confirmation.disposition, true, confirmation.warnings.map((warning) => warning.key))}
            className="mt-3 inline-flex min-h-11 items-center gap-2 rounded-lg bg-amber-950 px-3 text-sm font-semibold text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500 focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-40 dark:bg-amber-200 dark:text-amber-950"
          >
            <Check aria-hidden="true" className="size-4" /> Confirm and {confirmation.disposition === "COPY" ? "copy" : "download"}
          </button>
        </div>
      ) : null}
      {message ? <p role="status" className="max-w-full text-right text-xs text-black/58 dark:text-white/58" aria-live="polite">{message}</p> : null}
    </section>
  );
}

function ExportConfirmation({ confirmation, pending, pack, onCancel, onConfirm }: { confirmation: { disposition: Disposition; warnings: ExportWarning[] }; pending: Disposition | null; pack: Artifact; onCancel: () => void; onConfirm: () => void }) {
  return (
    <div role="alertdialog" aria-labelledby="menu-export-warning-title" aria-describedby="menu-export-warning-description" className="absolute right-[-8px] top-[calc(100%+12px)] w-[min(360px,calc(100vw-32px))] rounded-lg border border-amber-300/70 bg-amber-50 p-3 shadow-[0_12px_32px_rgba(0,0,0,0.14)] dark:border-amber-400/25 dark:bg-[#2b2820]">
      <div className="flex items-start gap-2">
        <ShieldAlert className="mt-0.5 size-4 shrink-0 text-amber-700 dark:text-amber-300" />
        <div className="min-w-0 flex-1">
          <h3 id="menu-export-warning-title" className="text-sm font-semibold">Unresolved statements will be exported</h3>
          <div id="menu-export-warning-description" className="mt-1 text-xs leading-5 text-black/62 dark:text-white/62">
            <p>Review affected statements before continuing.</p>
            {confirmation.warnings.length ? <ul className="mt-2 space-y-1">{confirmation.warnings.map((warning) => <li key={warning.key}><button type="button" onClick={() => focusStatement(pack, warning.sentenceNumber)} className="block max-w-full truncate text-left font-medium underline underline-offset-2">Statement {warning.sentenceNumber} — “{warning.excerpt}”</button></li>)}</ul> : <p className="mt-1">Affected statement details are unavailable.</p>}
          </div>
        </div>
        <button type="button" onClick={onCancel} aria-label="Cancel export warning" className="inline-flex size-8 items-center justify-center rounded-lg"><X aria-hidden="true" className="size-4" /></button>
      </div>
      <button autoFocus type="button" disabled={Boolean(pending)} onClick={onConfirm} className="mt-3 inline-flex min-h-10 items-center gap-2 rounded-lg bg-amber-950 px-3 text-sm font-semibold text-white disabled:opacity-40 dark:bg-amber-200 dark:text-amber-950"><Check aria-hidden="true" className="size-4" /> Confirm and {confirmation.disposition === "COPY" ? "copy" : "download"}</button>
    </div>
  );
}

function focusStatement(pack: Artifact, sentenceNumber: number) {
  const sentenceId = [...pack.variant.sentences].sort((a, b) => a.orderIndex - b.orderIndex)[sentenceNumber - 1]?.id;
  const sentence = sentenceId
    ? document.querySelector<HTMLElement>(`[data-statement-id="${sentenceId}"]`)
    : null;
  sentence?.scrollIntoView?.({ block: "center", behavior: "smooth" });
  sentence?.focus();
  if (sentence) {
    sentence.dataset.statementHighlight = "true";
    window.setTimeout(() => {
      if (sentence.isConnected) delete sentence.dataset.statementHighlight;
    }, 2_000);
  }
}

function isExportWarning(value: unknown): value is ExportWarning {
  if (!value || typeof value !== "object") return false;
  const warning = value as Record<string, unknown>;
  return typeof warning.key === "string" && typeof warning.sentenceNumber === "number" && typeof warning.excerpt === "string";
}

function downloadText(text: string, filename: string, mediaType: string) {
  const url = URL.createObjectURL(new Blob([text], { type: mediaType }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}
