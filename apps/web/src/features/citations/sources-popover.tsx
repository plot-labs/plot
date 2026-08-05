"use client";

import { Citation } from "@astryxdesign/core/Citation";
import { ExternalLink, X } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";

import type { ContentSource } from "@plot/api-client";

type SourcesPopoverProps = {
  sources: ContentSource[];
};

export function SourcesPopover({ sources }: SourcesPopoverProps) {
  const uniqueSources = sources.filter((source, index, all) =>
    all.findIndex((candidate) => candidate.originalUrl === source.originalUrl) === index,
  );
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const restoreFocusRef = useRef<HTMLElement | null>(null);
  const popoverId = useId();

  useEffect(() => {
    if (!open) {
      const element = restoreFocusRef.current;
      restoreFocusRef.current = null;
      if (element && document.contains(element)) element.focus();
      return;
    }

    restoreFocusRef.current = document.activeElement instanceof HTMLElement && document.activeElement !== document.body
      ? document.activeElement
      : triggerRef.current;
    const dialog = dialogRef.current;
    if (!dialog) return;

    const focusable = () => focusableElements(dialog);
    function dismissIfOutside(event: Event) {
      const target = event.target;
      if (!(target instanceof Node)) return;
      if (dialog?.contains(target) || triggerRef.current?.contains(target)) return;
      event.preventDefault();
      setOpen(false);
    }

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        setOpen(false);
        return;
      }
      if (event.key !== "Tab") return;
      const elements = focusable();
      if (!elements.length) {
        event.preventDefault();
        dialog?.focus();
        return;
      }
      const currentIndex = elements.indexOf(document.activeElement as HTMLElement);
      if (event.shiftKey) {
        if (currentIndex <= 0) {
          event.preventDefault();
          elements[elements.length - 1]?.focus();
        }
      } else if (currentIndex === -1 || currentIndex === elements.length - 1) {
        event.preventDefault();
        elements[0]?.focus();
      }
    }
    document.addEventListener("pointerdown", dismissIfOutside, true);
    document.addEventListener("click", dismissIfOutside, true);
    document.addEventListener("keydown", onKeyDown);
    dialog.focus();
    return () => {
      document.removeEventListener("pointerdown", dismissIfOutside, true);
      document.removeEventListener("click", dismissIfOutside, true);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  function close() {
    setOpen(false);
  }

  return (
    <div className="relative">
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={popoverId}
        onClick={() => setOpen((current) => !current)}
        className="inline-flex min-h-10 items-center gap-2 rounded-lg border border-black/10 bg-white px-3 text-sm font-semibold text-black/70 transition hover:border-black/20 hover:bg-black/[0.03] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 focus-visible:ring-offset-2 dark:border-white/15 dark:bg-white/[0.05] dark:text-white/75 dark:hover:border-white/25 dark:hover:bg-white/[0.1]"
      >
        <ExternalLink aria-hidden="true" className="size-4" />
        Sources{uniqueSources.length ? ` · ${uniqueSources.length}` : ""}
      </button>

      {open ? (
        <div
          ref={dialogRef}
          id={popoverId}
          role="dialog"
          aria-modal="true"
          aria-labelledby={`${popoverId}-title`}
          tabIndex={-1}
          className="fixed inset-x-3 bottom-3 z-50 max-h-[min(70vh,34rem)] overflow-y-auto rounded-2xl border border-black/12 bg-white p-4 text-left shadow-[0_24px_80px_rgba(0,0,0,0.2)] dark:border-white/15 dark:bg-[#202024] sm:absolute sm:right-0 sm:top-[calc(100%+10px)] sm:bottom-auto sm:w-[min(360px,calc(100vw-32px))]"
        >
          <div className="flex items-start gap-3">
            <div className="min-w-0 flex-1">
              <h2 id={`${popoverId}-title`} className="text-sm font-semibold text-black/85 dark:text-white/90">
                Sources
              </h2>
              <p className="mt-1 text-xs leading-5 text-black/50 dark:text-white/52">
                Sources attached to this artifact.
              </p>
            </div>
            <button
              type="button"
              onClick={close}
              aria-label="Close sources"
              className="inline-flex size-9 shrink-0 items-center justify-center rounded-lg text-black/50 hover:bg-black/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 dark:text-white/55 dark:hover:bg-white/10"
            >
              <X aria-hidden="true" className="size-4" />
            </button>
          </div>

          {uniqueSources.length ? (
            <ol className="mt-4 space-y-2" aria-label="Current sources">
              {uniqueSources.map((source, index) => (
                <li key={source.evidenceId} className="rounded-xl border border-black/[0.08] px-3 py-2.5 dark:border-white/10">
                  <Citation
                    source={{ title: source.sourceLabel, url: source.originalUrl }}
                    number={index + 1}
                    variant="label"
                    className="max-w-full"
                  />
                </li>
              ))}
            </ol>
          ) : (
            <p className="mt-4 rounded-xl border border-dashed border-black/10 px-3 py-4 text-sm leading-6 text-black/52 dark:border-white/12 dark:text-white/55">
              No current sources are available for this artifact.
            </p>
          )}
        </div>
      ) : null}
    </div>
  );
}

function focusableElements(dialog: HTMLElement): HTMLElement[] {
  return Array.from(dialog.querySelectorAll<HTMLElement>(
    'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )).filter((element) => element.getAttribute("aria-hidden") !== "true");
}
