"use client";

import { Check, ChevronDown, Copy, Download, ShieldAlert, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { PlotApiError, type Artifact, type PlotApiClient } from "@plot/api-client";

type Disposition = "COPY" | "DOWNLOAD";
type ExportWarning = { key: string; sentenceNumber: number; excerpt: string };

export function ExportDialog({ pack, client, presentation = "buttons" }: { pack: Artifact; client: PlotApiClient; presentation?: "buttons" | "menu" | "copy" }) {
  const [pending, setPending] = useState<Disposition | null>(null);
  const [includeSources, setIncludeSources] = useState(false);
  const [confirmation, setConfirmation] = useState<{ disposition: Disposition; warnings: ExportWarning[] } | null>(null);
  const [message, setMessage] = useState("");
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const dropdownTriggerRef = useRef<HTMLButtonElement>(null);
  const contentNoun = pack.contentType === "LAUNCH_ANNOUNCEMENT"
    ? "launch announcement"
    : pack.contentType === "CHANGELOG"
      ? "changelog"
      : "content";
  const copyLabel = `Copy ${contentNoun}`;
  const downloadLabel = `Download ${contentNoun}`;

  useEffect(() => {
    if (!dropdownOpen) return;

    function handleDismiss(event: Event) {
      if (event.target instanceof Node && !dropdownRef.current?.contains(event.target)) {
        setDropdownOpen(false);
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        setDropdownOpen(false);
        dropdownTriggerRef.current?.focus();
      }
    }

    document.addEventListener("pointerdown", handleDismiss, true);
    document.addEventListener("click", handleDismiss, true);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("pointerdown", handleDismiss, true);
      document.removeEventListener("click", handleDismiss, true);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [dropdownOpen]);

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
        try {
          await navigator.clipboard.writeText(result.text);
          await reportDelivery(result.exportId, "CLIPBOARD_WRITE_SUCCEEDED");
          setConfirmation(null);
          setMessage("Content copied.");
        } catch (error) {
          await reportDelivery(result.exportId, "CLIPBOARD_WRITE_FAILED");
          setConfirmation(null);
          setMessage(error instanceof Error ? error.message : "Clipboard write failed.");
        }
      } else {
        downloadText(result.text, result.filename, result.mediaType);
        await reportDelivery(result.exportId, "DOWNLOAD_STARTED");
        setConfirmation(null);
        setMessage("Download started.");
      }
    } catch (error) {
      if (error instanceof PlotApiError && error.code === "EXPORT_CONFIRMATION_REQUIRED") {
        const warnings = Array.isArray(error.details?.warnings)
          ? error.details.warnings.filter(isExportWarning)
          : [];
        setConfirmation({ disposition, warnings });
        setMessage("Explicit confirmation is required before export.");
      } else {
        setMessage(error instanceof Error ? error.message : "The content could not be exported.");
      }
    } finally {
      setPending(null);
    }
  }

  async function reportDelivery(
    exportId: string,
    kind: "CLIPBOARD_WRITE_SUCCEEDED" | "CLIPBOARD_WRITE_FAILED" | "DOWNLOAD_STARTED",
  ) {
    if (typeof client.recordProductDeliveryEvent !== "function") return;
    try {
      await client.recordProductDeliveryEvent(pack.variant.id, {
        kind,
        exportId,
        clientEventId: crypto.randomUUID(),
      });
    } catch {
      // Delivery reporting must not block copy/download UX.
    }
  }

  if (presentation === "copy") {
    return (
      <div ref={dropdownRef} className="relative inline-flex items-center">
        <div className="glass-card glass-split inline-flex h-8.5 items-stretch rounded-full border border-black/15 transition dark:border-white/15">
          <button aria-busy={pending === "COPY"}
            type="button"
            disabled={Boolean(pending)}
            onClick={() => void requestExport("COPY", false)}
            title={copyLabel}
            aria-label={copyLabel}
            className="glass-button min-w-[96px]"
          >
            <Copy aria-hidden="true" className="size-3.5 text-black/60 dark:text-white/60" />
            <span>{pending === "COPY" ? "Copying…" : message === "Content copied." ? "Copied" : "Copy"}</span>
          </button>
          <div className="w-px self-stretch bg-black/15 dark:bg-white/15" aria-hidden="true" />
          <div className="relative inline-flex h-full">
            <button
              ref={dropdownTriggerRef}
              type="button"
              disabled={Boolean(pending)}
              aria-label="Export options"
              aria-haspopup="menu"
              aria-expanded={dropdownOpen}
              onClick={() => setDropdownOpen((open) => !open)}
              className="glass-button inline-flex items-center justify-center"
            >
              <ChevronDown aria-hidden="true" className="size-3 text-black/60 dark:text-white/60" />
            </button>

            {dropdownOpen ? (
              <div
                role="menu"
                aria-label="Export options"
                className="glass-layer absolute right-0 top-[calc(100%+4px)] z-50 min-w-[136px] rounded-[8px] border border-black/10 p-1 dark:border-white/10"
              >
                <button aria-busy={pending === "DOWNLOAD"}
                  type="button"
                  role="menuitem"
                  disabled={Boolean(pending)}
                  onClick={() => {
                    setDropdownOpen(false);
                    void requestExport("DOWNLOAD", false);
                  }}
                  className="glass-control flex h-7.5 w-full items-center gap-2 rounded-[6px] px-2 text-left text-xs font-medium"
                >
                  <Download aria-hidden="true" className="size-3.5 shrink-0 text-black/60 dark:text-white/60" />
                  <span>Download .md</span>
                </button>
              </div>
            ) : null}
          </div>
        </div>

        {confirmation ? (
          <ExportConfirmation
            confirmation={confirmation}
            pending={pending}
            pack={pack}
            onCancel={() => setConfirmation(null)}
            onConfirm={() => void requestExport(confirmation.disposition, true, confirmation.warnings.map((warning) => warning.key))}
          />
        ) : null}
        {message ? <span role="status" aria-live="polite" className="sr-only">{message}</span> : null}
      </div>
    );
  }

  if (presentation === "menu") {
    return (
      <div role="none" className="relative border-t border-black/[0.06] pt-1 dark:border-white/10">
        <button
          type="button"
          role="menuitemcheckbox"
          aria-checked={includeSources}
          onClick={() => setIncludeSources((current) => !current)}
          className="glass-control flex h-8 w-full items-center gap-2 rounded-[4px] px-2.5 text-left text-xs"
        >
          <span aria-hidden="true" className="inline-flex size-3.5 items-center justify-center rounded-[3px] border border-black/20 dark:border-white/20">
            {includeSources ? <Check className="size-2.5" /> : null}
          </span>
          Sources in Markdown
        </button>
        {includeSources ? (
          <p className="px-2.5 pb-1 text-[11px] leading-4 text-black/50 dark:text-white/50">
            Markdown Sources can include private repository labels and URLs. Hosted publish only shows public citations.
          </p>
        ) : null}
        <button aria-busy={pending === "COPY"} type="button" role="menuitem" disabled={Boolean(pending)} onClick={() => void requestExport("COPY", false)} className="glass-control flex h-8 w-full items-center gap-2 rounded-[4px] px-2.5 text-left">
          <Copy aria-hidden="true" className="size-4" /> Copy Markdown
        </button>
        <button aria-busy={pending === "DOWNLOAD"} type="button" role="menuitem" disabled={Boolean(pending)} onClick={() => void requestExport("DOWNLOAD", false)} className="glass-control flex h-8 w-full items-center gap-2 rounded-[4px] px-2.5 text-left">
          <Download aria-hidden="true" className="size-4" /> Download Markdown
        </button>
        {confirmation ? <ExportConfirmation confirmation={confirmation} pending={pending} pack={pack} onCancel={() => setConfirmation(null)} onConfirm={() => void requestExport(confirmation.disposition, true, confirmation.warnings.map((warning) => warning.key))} /> : null}
        {message ? <p role="status" aria-live="polite" className="px-2.5 py-1 text-xs text-black/58 dark:text-white/58">{message}</p> : null}
      </div>
    );
  }

  return (
    <section aria-label="Export content" className="flex min-w-0 flex-col items-end gap-2 sm:max-w-[24rem]">
      <label className="inline-flex min-h-10 items-center gap-2 self-end text-xs font-medium text-black/58 dark:text-white/58">
        <input
          type="checkbox"
          checked={includeSources}
          onChange={(event) => setIncludeSources(event.target.checked)}
          className="size-4 rounded border-black/20 accent-black dark:border-white/20 dark:accent-white"
        />
        Include Sources in Markdown
      </label>
      {includeSources ? (
        <p className="max-w-[24rem] text-right text-[11px] leading-4 text-black/50 dark:text-white/50">
          Markdown Sources can include private repository labels and URLs. Hosted publish only shows public citations, and Plot does not strip secrets from the body.
        </p>
      ) : null}
      <div className="flex items-center gap-1.5">
        <button aria-busy={pending === "COPY"}
          type="button"
          disabled={Boolean(pending)}
          onClick={() => void requestExport("COPY", false)}
          title={copyLabel}
          aria-label={copyLabel}
          className="glass-button glass-icon inline-flex size-10 items-center justify-center"
        >
          <Copy aria-hidden="true" className="size-4" />
        </button>
        <button aria-busy={pending === "DOWNLOAD"}
          type="button"
          disabled={Boolean(pending)}
          onClick={() => void requestExport("DOWNLOAD", false)}
          title={downloadLabel}
          aria-label={downloadLabel}
          className="glass-button glass-icon inline-flex size-10 items-center justify-center"
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
                          className="glass-button block max-w-full truncate text-left underline underline-offset-2"
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
              className="glass-button glass-icon inline-flex size-9 shrink-0 items-center justify-center"
              aria-label="Cancel export warning"
              title="Cancel export warning"
            >
              <X aria-hidden="true" className="size-4" />
            </button>
          </div>
          <button aria-busy={Boolean(pending)}
            autoFocus
            type="button"
            disabled={Boolean(pending)}
            onClick={() => void requestExport(confirmation.disposition, true, confirmation.warnings.map((warning) => warning.key))}
            className="glass-button glass-primary mt-3 inline-flex items-center gap-2"
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
            {confirmation.warnings.length ? <ul className="mt-2 space-y-1">{confirmation.warnings.map((warning) => <li key={warning.key}><button type="button" onClick={() => focusStatement(pack, warning.sentenceNumber)} className="glass-button block max-w-full truncate text-left underline underline-offset-2">Statement {warning.sentenceNumber} — “{warning.excerpt}”</button></li>)}</ul> : <p className="mt-1">Affected statement details are unavailable.</p>}
          </div>
        </div>
        <button type="button" onClick={onCancel} aria-label="Cancel export warning" className="glass-button glass-icon inline-flex size-8 items-center justify-center"><X aria-hidden="true" className="size-4" /></button>
      </div>
      <button aria-busy={Boolean(pending)} autoFocus type="button" disabled={Boolean(pending)} onClick={onConfirm} className="glass-button glass-primary mt-3 inline-flex items-center gap-2"><Check aria-hidden="true" className="size-4" /> Confirm and {confirmation.disposition === "COPY" ? "copy" : "download"}</button>
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
