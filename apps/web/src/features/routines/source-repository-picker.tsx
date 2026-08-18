"use client";

import { ArrowDown01Icon, Tick02Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import { LockKeyhole, Search } from "lucide-react";
import { useEffect, useRef, useState } from "react";

export type SourceOption = { id: string; displayName: string; visibility?: string };

type SourceRepositoryPickerProps = {
  sources: SourceOption[];
  value: string;
  onChange: (value: string) => void;
};

export function SourceRepositoryPicker({ sources, value, onChange }: SourceRepositoryPickerProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const rootRef = useRef<HTMLDivElement>(null);
  const selectedSource = sources.find((source) => source.id === value) ?? sources[0];
  const filteredSources = sources.filter((source) => source.displayName.toLowerCase().includes(query.trim().toLowerCase()));

  useEffect(() => {
    if (!open) return;

    function dismissIfOutside(event: Event) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) {
        setOpen(false);
        setQuery("");
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
          setQuery("");
        }
      }}
    >
      <button
        type="button"
        disabled={!sources.length}
        aria-label="Source repository"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => {
          setOpen((prev) => !prev);
          if (open) setQuery("");
        }}
        onKeyDown={(event) => {
          if (event.key === "Escape") setOpen(false);
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            setOpen(true);
          }
        }}
        className="flex h-10 w-full items-center justify-between gap-2.5 rounded-[9px] border border-black/10 bg-white px-3 text-sm font-normal text-black/80 outline-none transition hover:border-black/20 focus-visible:border-black/25 focus-visible:ring-2 focus-visible:ring-black/[0.05] disabled:cursor-not-allowed disabled:opacity-50 dark:border-white/12 dark:bg-white/[0.06] dark:text-white/85 dark:hover:border-white/20"
      >
        <span className="flex min-w-0 items-center gap-1.5"><span className="truncate">{selectedSource ? selectedSource.displayName : "Connect GitHub first"}</span>{selectedSource?.visibility === "PRIVATE" && <LockKeyhole aria-label="Private repository" className="size-3 shrink-0 text-black/35 dark:text-white/38" strokeWidth={1.6} />}</span>
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
        <div className="absolute inset-x-0 top-[calc(100%+4px)] z-50 overflow-hidden rounded-[13px] border border-white/70 bg-white/78 p-1.5 shadow-[0_18px_50px_rgb(15_23_42_/_0.16),inset_0_1px_0_rgb(255_255_255_/_0.9)] backdrop-blur-2xl dark:border-white/12 dark:bg-[#202125]/88 dark:shadow-[0_18px_50px_rgb(0_0_0_/_0.45),inset_0_1px_0_rgb(255_255_255_/_0.08)]">
          <div className="relative m-1 mb-1.5">
            <Search aria-hidden="true" className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-black/32 dark:text-white/34" strokeWidth={1.7} />
            <input autoFocus type="search" value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => { if (event.key === "Escape") { setOpen(false); setQuery(""); } }} aria-label="Search repositories" placeholder="Search repositories" className="h-9 w-full rounded-[9px] border border-white/80 bg-white/55 pl-8 pr-3 text-[13px] text-black/78 outline-none shadow-[inset_0_1px_2px_rgb(15_23_42_/_0.06),0_1px_0_rgb(255_255_255_/_0.75)] placeholder:text-black/32 focus:border-black/15 focus:bg-white/72 dark:border-white/10 dark:bg-white/[0.055] dark:text-white/82 dark:placeholder:text-white/30 dark:focus:border-white/18 dark:focus:bg-white/[0.075]" />
          </div>
          <div role="listbox" aria-label="Source repository options" className="max-h-[210px] overflow-y-auto">
          {filteredSources.map((source) => {
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
                  setQuery("");
                }}
                className={`flex w-full items-center justify-between gap-2.5 rounded-[9px] px-2.5 py-2 text-left text-sm outline-none transition ${isSelected ? "bg-[#eef1f4] text-black/88 font-medium dark:bg-white/[0.08] dark:text-white/92" : "text-black/75 hover:bg-black/[0.04] dark:text-white/78 dark:hover:bg-white/[0.07]"}`}
              >
                <span className="flex min-w-0 flex-1 items-center gap-1.5"><span className="truncate">{source.displayName}</span>{source.visibility === "PRIVATE" && <LockKeyhole aria-label="Private repository" className="size-3 shrink-0 text-black/35 dark:text-white/38" strokeWidth={1.6} />}</span>
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
          {!filteredSources.length && <p className="px-3 py-6 text-center text-[12px] text-black/40 dark:text-white/40">No repositories found.</p>}
          </div>
        </div>
      ) : null}
    </div>
  );
}
