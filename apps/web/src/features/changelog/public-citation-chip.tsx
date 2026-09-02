"use client";

import { GithubIcon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import { ExternalLink } from "lucide-react";
import { useId, useState } from "react";

import type { PublicChangelogCitation } from "@plot/api-client";
import { isSafeHttpUrl } from "@/lib/safe-url";

type PublicCitationReference = {
  citation: PublicChangelogCitation;
  number: number;
};

type PublicCitationChipProps = {
  references: PublicCitationReference[];
};

export function PublicCitationChip({ references }: PublicCitationChipProps) {
  const safeReferences = references.filter(({ citation }) => isSafeHttpUrl(citation.originalUrl));
  const [open, setOpen] = useState(false);
  const popoverId = useId();

  if (!safeReferences.length) return null;

  const numbers = safeReferences.map(({ number }) => number).join(", ");

  return (
    <span
      className="relative ml-1 inline-flex align-baseline"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onBlur={(event) => {
        const relatedTarget = event.relatedTarget;
        if (!(relatedTarget instanceof Node) || !event.currentTarget.contains(relatedTarget)) setOpen(false);
      }}
      onKeyDown={(event) => {
        if (event.key !== "Escape") return;
        event.preventDefault();
        setOpen(false);
        event.currentTarget.querySelector("button")?.focus();
      }}
    >
      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={popoverId}
        aria-label={`Show citation${safeReferences.length === 1 ? "" : "s"} ${numbers}`}
        onClick={() => setOpen((current) => !current)}
        onFocus={() => setOpen(true)}
        className="inline-flex min-h-6 items-center rounded-full border border-[#ef3f2c]/25 bg-[#fff4f1] px-1.5 align-middle font-mono text-[11px] font-semibold leading-none text-[#c73728] transition hover:border-[#ef3f2c]/45 hover:bg-[#ffeae5] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#ef3f2c]/35 focus-visible:ring-offset-2"
      >
        [{numbers}]
      </button>

      {open ? (
        <div
          id={popoverId}
          role="dialog"
          aria-label="Citation sources"
          className="absolute bottom-[calc(100%+8px)] left-0 z-20 w-[min(320px,calc(100vw-48px))] rounded-xl border border-black/10 bg-white p-3 text-left shadow-[0_14px_40px_rgba(0,0,0,0.16)]"
        >
          <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-black/42">Sources</p>
          <ol className="mt-2 space-y-1.5">
            {safeReferences.map(({ citation, number }) => (
              <li key={`${number}-${citation.originalUrl}`}>
                <a
                  href={citation.originalUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="flex items-start gap-2 rounded-lg px-2 py-1.5 text-sm text-black/72 transition hover:bg-black/[0.04] hover:text-black focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#ef3f2c]/30"
                >
                  <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-black/[0.05] font-mono text-[10px] font-semibold text-black/55">
                    {number}
                  </span>
                  {citation.provider === "GITHUB" ? (
                    <HugeiconsIcon
                      icon={GithubIcon}
                      size={14}
                      className="mt-0.5 shrink-0 text-black/48"
                      aria-hidden="true"
                    />
                  ) : (
                    <ExternalLink className="mt-0.5 size-3.5 shrink-0 text-black/48" aria-hidden="true" />
                  )}
                  <span className="min-w-0">
                    <span className="block truncate font-medium">{citation.sourceLabel}</span>
                    <span className="block truncate text-xs text-black/42">{citation.provider}</span>
                  </span>
                  <ExternalLink className="mt-0.5 ml-auto size-3.5 shrink-0 text-black/35" aria-hidden="true" />
                </a>
              </li>
            ))}
          </ol>
        </div>
      ) : null}
    </span>
  );
}
