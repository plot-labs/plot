"use client";

import { ArrowDown01Icon, Tick02Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import { useEffect, useRef, useState } from "react";

export type SourceOption = { id: string; displayName: string };

type SourceRepositoryPickerProps = {
  sources: SourceOption[];
  value: string;
  onChange: (value: string) => void;
};

export function SourceRepositoryPicker({ sources, value, onChange }: SourceRepositoryPickerProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const selectedSource = sources.find((source) => source.id === value) ?? sources[0];

  useEffect(() => {
    if (!open) return;

    function dismissIfOutside(event: Event) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) {
        setOpen(false);
      }
    }

    document.addEventListener("pointerdown", dismissIfOutside, true);
    return () => {
      document.removeEventListener("pointerdown", dismissIfOutside, true);
    };
  }, [open]);

  return (
    <div
      ref={rootRef}
      className="relative w-full"
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) {
          setOpen(false);
        }
      }}
    >
      <button
        type="button"
        disabled={!sources.length}
        aria-label="Source repository"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
        onKeyDown={(event) => {
          if (event.key === "Escape") setOpen(false);
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            setOpen(true);
          }
        }}
        className="flex h-10 w-full items-center justify-between gap-2.5 rounded-[9px] border border-black/10 bg-white px-3 text-sm font-normal text-black/80 outline-none transition hover:border-black/20 focus-visible:border-black/25 focus-visible:ring-2 focus-visible:ring-black/[0.05] disabled:cursor-not-allowed disabled:opacity-50 dark:border-white/12 dark:bg-white/[0.06] dark:text-white/85 dark:hover:border-white/20"
      >
        <span className="truncate">{selectedSource ? selectedSource.displayName : "Connect GitHub first"}</span>
        <HugeiconsIcon
          icon={ArrowDown01Icon}
          size={15}
          color="currentColor"
          strokeWidth={1.5}
          aria-hidden="true"
          className={`shrink-0 text-black/40 transition dark:text-white/42 ${open ? "rotate-180" : ""}`}
        />
      </button>

      {open && sources.length > 0 ? (
        <div
          role="listbox"
          aria-label="Source repository options"
          className="absolute inset-x-0 top-[calc(100%+4px)] z-50 max-h-[240px] overflow-y-auto rounded-[12px] border border-black/10 bg-white p-1.5 shadow-[0_14px_40px_rgb(15_23_42_/_0.14)] dark:border-white/12 dark:bg-[#202125] dark:shadow-black/40"
        >
          {sources.map((source) => {
            const isSelected = source.id === value;
            return (
              <button
                key={source.id}
                type="button"
                role="option"
                aria-selected={isSelected}
                onClick={() => {
                  onChange(source.id);
                  setOpen(false);
                }}
                className={`flex w-full items-center justify-between gap-2.5 rounded-[9px] px-2.5 py-2 text-left text-sm outline-none transition ${isSelected ? "bg-[#eef1f4] text-black/88 font-medium dark:bg-white/[0.08] dark:text-white/92" : "text-black/75 hover:bg-black/[0.04] dark:text-white/78 dark:hover:bg-white/[0.07]"}`}
              >
                <span className="truncate">{source.displayName}</span>
                {isSelected ? (
                  <HugeiconsIcon
                    icon={Tick02Icon}
                    size={16}
                    color="currentColor"
                    strokeWidth={1.5}
                    className="shrink-0 text-black/65 dark:text-white/75"
                    aria-hidden="true"
                  />
                ) : null}
              </button>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}
