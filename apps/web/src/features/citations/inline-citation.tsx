"use client";

import { ShieldAlert } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";

import type { GenerationCitation } from "@plot/api-client";
import { EvidencePopover, providerName } from "./evidence-popover";

export function InlineCitation({ citation }: { citation: GenerationCitation }) {
  const [open, setOpen] = useState(false);
  const popoverId = useId();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const stale = citation.status === "STALE";
  const sourceLost = citation.sourceAccess === "LOST";

  useEffect(() => {
    if (!open) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      setOpen(false);
      triggerRef.current?.focus();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open]);

  function closeAndRestoreFocus() {
    setOpen(false);
    triggerRef.current?.focus();
  }

  return (
    <div className="relative inline-flex align-baseline">
      <button
        ref={triggerRef}
        type="button"
        aria-expanded={open}
        aria-controls={popoverId}
        onClick={() => setOpen((current) => !current)}
        className={`mx-0.5 inline-flex min-h-8 items-center gap-1 rounded-md border px-2 py-1 text-[11px] font-semibold leading-none transition focus-visible:outline-2 focus-visible:outline-offset-2 ${
          stale || sourceLost
            ? "border-amber-300 bg-amber-50 text-amber-900 hover:bg-amber-100 focus-visible:outline-amber-700 dark:border-amber-400/35 dark:bg-amber-400/10 dark:text-amber-100 dark:hover:bg-amber-400/15 dark:focus-visible:outline-amber-300"
            : "border-black/10 bg-black/[0.035] text-black/62 hover:border-black/20 hover:bg-black/[0.07] focus-visible:outline-black dark:border-white/12 dark:bg-white/[0.07] dark:text-white/68 dark:hover:bg-white/[0.12] dark:focus-visible:outline-white"
        }`}
      >
        {stale || sourceLost ? <ShieldAlert className="size-3.5" aria-hidden="true" /> : null}
        {stale ? "Unverified · " : ""}{sourceLost ? "Source access lost · " : ""}{providerName(citation.provider)} · {citation.sourceLabel}
      </button>
      {open ? <EvidencePopover citation={citation} id={popoverId} onClose={closeAndRestoreFocus} /> : null}
    </div>
  );
}
