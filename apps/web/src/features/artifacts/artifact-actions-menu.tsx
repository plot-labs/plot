"use client";

import { Ellipsis, type LucideIcon } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type ReactNode, type RefObject } from "react";

type ArtifactActionsMenuProps = {
  triggerRef?: RefObject<HTMLButtonElement | null>;
  children: (close: () => void) => ReactNode;
};

export function ArtifactActionsMenu({ triggerRef, children }: ArtifactActionsMenuProps) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const fallbackTriggerRef = useRef<HTMLButtonElement>(null);
  const buttonRef = triggerRef ?? fallbackTriggerRef;
  const close = useCallback(() => setOpen(false), []);

  useEffect(() => {
    if (!open) return;

    function dismiss(event: Event) {
      if (event.target instanceof Node && !containerRef.current?.contains(event.target)) {
        setOpen(false);
      }
    }

    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        setOpen(false);
        buttonRef.current?.focus();
      }
    }

    document.addEventListener("pointerdown", dismiss, true);
    document.addEventListener("click", dismiss, true);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", dismiss, true);
      document.removeEventListener("click", dismiss, true);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [buttonRef, open]);

  return (
    <div ref={containerRef} className="relative flex shrink-0 items-center gap-2">
      <button
        ref={buttonRef}
        type="button"
        aria-label="Artifact actions"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className="glass-button glass-icon inline-flex size-9 items-center justify-center"
      >
        <Ellipsis aria-hidden="true" className="size-4" />
      </button>
      {open ? (
        <div role="menu" aria-label="Artifact actions" className="glass-layer absolute right-0 top-full z-40 mt-2 w-[204px] rounded-[8px] border border-black/10 p-2 text-[13px] text-[#18181b] dark:border-white/10 dark:text-white">
          {children(close)}
        </div>
      ) : null}
    </div>
  );
}

export function ArtifactMenuButton({ icon: Icon, children, onClick }: { icon: LucideIcon; children: string; onClick: () => void }) {
  return (
    <button type="button" role="menuitem" onClick={onClick} className="glass-control flex h-8 w-full items-center gap-2 rounded-[4px] px-2.5 text-left">
      <Icon aria-hidden="true" className="size-4" />
      {children}
    </button>
  );
}
