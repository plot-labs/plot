"use client";

import {
  ArrowDown01Icon,
  Calendar03Icon,
  Clock01Icon,
  GitBranchIcon,
  Package01Icon,
  Tag01Icon,
  Tick02Icon,
} from "@hugeicons/core-free-icons";
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react";
import { useEffect, useId, useRef, useState } from "react";

import type { RoutineCadence } from "@/lib/api-client";

type TriggerOption = {
  value: RoutineCadence;
  label: string;
  description: string;
  icon: IconSvgElement;
};

const triggerGroups: ReadonlyArray<{ label: string; options: TriggerOption[] }> = [
  {
    label: "Time",
    options: [
      { value: "DAILY", label: "Every day", description: "Run once each day", icon: Clock01Icon },
      { value: "WEEKLY", label: "Every week", description: "Run on a selected weekday", icon: Calendar03Icon },
    ],
  },
  {
    label: "Event",
    options: [
      { value: "ON_GITHUB_CHANGE", label: "Push to default branch", description: "Run after GitHub pushes", icon: GitBranchIcon },
      { value: "ON_GITHUB_RELEASE", label: "Release published", description: "Run for each published release", icon: Package01Icon },
      { value: "ON_GIT_TAG", label: "Git tag pushed", description: "Run for each new tag", icon: Tag01Icon },
    ],
  },
];

const triggerOptions = triggerGroups.flatMap((group) => group.options);

type RoutineTriggerPickerProps = {
  value: RoutineCadence;
  onChange: (value: RoutineCadence) => void;
};

export function RoutineTriggerPicker({ value, onChange }: RoutineTriggerPickerProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const listboxRef = useRef<HTMLDivElement>(null);
  const listboxId = useId();
  const selected = triggerOptions.find((option) => option.value === value) ?? triggerOptions[0];

  useEffect(() => {
    if (!open) return;

    const selectedOption = listboxRef.current?.querySelector<HTMLElement>("[role='option'][aria-selected='true']");
    selectedOption?.focus();

    function dismissIfOutside(event: Event) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) setOpen(false);
    }

    function handleEscape(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      event.preventDefault();
      setOpen(false);
      triggerRef.current?.focus();
    }

    document.addEventListener("pointerdown", dismissIfOutside, true);
    document.addEventListener("click", dismissIfOutside, true);
    document.addEventListener("keydown", handleEscape);
    return () => {
      document.removeEventListener("pointerdown", dismissIfOutside, true);
      document.removeEventListener("click", dismissIfOutside, true);
      document.removeEventListener("keydown", handleEscape);
    };
  }, [open]);

  function select(option: TriggerOption) {
    onChange(option.value);
    setOpen(false);
    triggerRef.current?.focus();
  }

  function moveOptionFocus(event: React.KeyboardEvent<HTMLDivElement>) {
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const options = Array.from(listboxRef.current?.querySelectorAll<HTMLElement>("[role='option']") ?? []);
    const current = options.indexOf(document.activeElement as HTMLElement);
    const next = event.key === "Home"
      ? 0
      : event.key === "End"
        ? options.length - 1
        : (current + (event.key === "ArrowDown" ? 1 : -1) + options.length) % options.length;
    options[next]?.focus();
  }

  return (
    <div ref={rootRef} className="relative">
      <button
        ref={triggerRef}
        type="button"
        aria-label={`Trigger: ${selected.label}`}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listboxId}
        onClick={() => setOpen((current) => !current)}
        onKeyDown={(event) => {
          if (event.key !== "ArrowDown") return;
          event.preventDefault();
          setOpen(true);
        }}
        className="flex min-h-12 w-full items-center gap-3 rounded-[9px] border border-black/10 bg-white px-3 text-left transition hover:border-black/20 focus-visible:border-black/25 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/[0.05] dark:border-white/12 dark:bg-white/[0.06] dark:hover:border-white/20 dark:focus-visible:border-white/25"
      >
        <span className="flex size-8 shrink-0 items-center justify-center rounded-[9px] bg-[#eef1f4] text-black/60 dark:bg-white/[0.08] dark:text-white/65">
          <HugeiconsIcon icon={selected.icon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[13px] font-medium text-black/78 dark:text-white/82">{selected.label}</span>
          <span className="block truncate text-[11px] font-normal text-black/42 dark:text-white/42">{selected.description}</span>
        </span>
        <HugeiconsIcon
          icon={ArrowDown01Icon}
          size={15}
          color="currentColor"
          strokeWidth={1.5}
          aria-hidden="true"
          className={`shrink-0 text-black/38 transition dark:text-white/42 ${open ? "rotate-180" : ""}`}
        />
      </button>

      {open ? (
        <div
          ref={listboxRef}
          id={listboxId}
          role="listbox"
          aria-label="Routine trigger"
          onKeyDown={moveOptionFocus}
          className="absolute inset-x-0 top-[calc(100%+6px)] z-50 max-h-[min(360px,70vh)] overflow-y-auto rounded-[12px] border border-black/10 bg-white p-1.5 shadow-[0_14px_40px_rgb(15_23_42_/_0.14)] dark:border-white/12 dark:bg-[#202125] dark:shadow-black/40"
        >
          {triggerGroups.map((group, groupIndex) => (
            <div
              key={group.label}
              role="group"
              aria-label={group.label}
              className={groupIndex ? "mt-1 border-t border-black/[0.07] pt-1 dark:border-white/[0.08]" : undefined}
            >
              <div className="px-2 pb-1 pt-1.5 text-[10px] font-semibold uppercase tracking-[0.14em] text-black/35 dark:text-white/38">
                {group.label}
              </div>
              {group.options.map((option) => {
                const active = option.value === value;
                return (
                  <button
                    key={option.value}
                    type="button"
                    role="option"
                    aria-selected={active}
                    onClick={() => select(option)}
                    className={`flex w-full items-center gap-3 rounded-[9px] px-2 py-2 text-left outline-none transition focus-visible:ring-2 focus-visible:ring-black/15 ${active ? "bg-[#eef1f4] text-black/88 dark:bg-white/[0.08] dark:text-white/92" : "text-black/72 hover:bg-black/[0.04] dark:text-white/75 dark:hover:bg-white/[0.07]"}`}
                  >
                    <span className={`flex size-8 shrink-0 items-center justify-center rounded-[9px] ${active ? "bg-white text-black/65 shadow-sm dark:bg-white/[0.12] dark:text-white/75" : "bg-black/[0.04] text-black/45 dark:bg-white/[0.07] dark:text-white/48"}`}>
                      <HugeiconsIcon icon={option.icon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13px] font-medium">{option.label}</span>
                      <span className="block truncate text-[11px] font-normal text-black/42 dark:text-white/42">{option.description}</span>
                    </span>
                    {active ? <HugeiconsIcon icon={Tick02Icon} size={16} color="currentColor" strokeWidth={1.5} className="shrink-0 text-black/65 dark:text-white/75" aria-hidden="true" /> : null}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}
