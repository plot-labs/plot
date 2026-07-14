import { ExternalLink, X } from "lucide-react";

import type { GenerationCitation } from "@plot/api-client";

type EvidencePopoverProps = {
  citation: GenerationCitation;
  id: string;
  onClose: () => void;
};

export function EvidencePopover({ citation, id, onClose }: EvidencePopoverProps) {
  const originalUrl = safeHttpUrl(citation.originalUrl);

  return (
    <div
      id={id}
      role="region"
      aria-label={`${citation.sourceLabel} evidence`}
      className="absolute left-0 top-[calc(100%+8px)] z-30 w-[min(320px,calc(100vw-40px))] rounded-xl border border-black/12 bg-white p-3 text-left shadow-[0_18px_48px_rgba(0,0,0,0.16)] dark:border-white/15 dark:bg-[#202024]"
    >
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-black/45 dark:text-white/45">
            Saved evidence snapshot
          </div>
          <div className="mt-1 text-xs font-medium text-black/75 dark:text-white/78">
            {providerName(citation.provider)} · {citation.sourceLabel}
          </div>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="inline-flex size-9 shrink-0 items-center justify-center rounded-lg text-black/45 hover:bg-black/5 dark:text-white/45 dark:hover:bg-white/10"
          aria-label="Close evidence"
        >
          <X className="size-4" />
        </button>
      </div>
      <p className="mt-3 text-sm leading-5 text-black/72 dark:text-white/72">
        {citation.snapshotExcerpt ?? "No snapshot excerpt is available."}
      </p>
      {originalUrl ? (
        <a
          href={originalUrl}
          target="_blank"
          rel="noreferrer"
          className="mt-3 inline-flex min-h-9 items-center gap-1.5 rounded-lg text-xs font-semibold text-blue-700 underline-offset-4 hover:underline dark:text-blue-300"
        >
          Open original <ExternalLink className="size-3.5" />
        </a>
      ) : (
        <p className="mt-3 text-xs text-black/45 dark:text-white/45">Original link unavailable</p>
      )}
    </div>
  );
}

export function providerName(provider: GenerationCitation["provider"]) {
  return provider === "GITHUB" ? "GitHub" : provider === "SLACK" ? "Slack" : "Linear";
}

function safeHttpUrl(value: string): string | null {
  try {
    const url = new URL(value);
    return url.protocol === "https:" || url.protocol === "http:" ? url.toString() : null;
  } catch {
    return null;
  }
}
