"use client";

import { ArrowDown01Icon, Tick02Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import { useEffect, useId, useRef, useState } from "react";

import type { RoutineCadence } from "@/lib/api-client";

type TriggerOption = {
  value: RoutineCadence;
  label: string;
};

const triggerGroups: ReadonlyArray<{ label: string; options: TriggerOption[] }> = [
  {
    label: "Time",
    options: [
      { value: "DAILY", label: "Daily" },
      { value: "WEEKLY", label: "Weekly" },
    ],
  },
  {
    label: "Event",
    options: [
      { value: "ON_GITHUB_CHANGE", label: "Push to default branch" },
      { value: "ON_GITHUB_RELEASE", label: "Release published" },
      { value: "ON_GIT_TAG", label: "Git tag pushed" },
    ],
  },
];

const daysOfWeek = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

const triggerOptions = triggerGroups.flatMap((group) => group.options);

type RoutineTriggerPickerProps = {
  value: RoutineCadence;
  onChange: (value: RoutineCadence) => void;
  day?: string;
  onDayChange?: (day: string) => void;
};

export function RoutineTriggerPicker({
  value,
  onChange,
  day: propDay,
  onDayChange,
}: RoutineTriggerPickerProps) {
  const [openPicker, setOpenPicker] = useState<"repeat" | "day" | null>(null);
  const [internalDay, setInternalDay] = useState("Monday");
  const activeDay = propDay ?? internalDay;

  const selectedIndex = Math.max(triggerOptions.findIndex((option) => option.value === value), 0);
  const selectedDayIndex = Math.max(daysOfWeek.indexOf(activeDay), 0);

  const [activeIndex, setActiveIndex] = useState(selectedIndex);
  const [activeDayIndex, setActiveDayIndex] = useState(selectedDayIndex);

  const rootRef = useRef<HTMLDivElement>(null);
  const repeatTriggerRef = useRef<HTMLButtonElement>(null);
  const dayTriggerRef = useRef<HTMLButtonElement>(null);
  const listboxRef = useRef<HTMLDivElement>(null);
  const listboxId = useId();
  const selected = triggerOptions[selectedIndex];

  useEffect(() => {
    if (!openPicker) return;

    listboxRef.current?.focus();

    function dismissIfOutside(event: Event) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) setOpenPicker(null);
    }

    document.addEventListener("pointerdown", dismissIfOutside, true);
    return () => {
      document.removeEventListener("pointerdown", dismissIfOutside, true);
    };
  }, [openPicker]);

  function openRepeatListbox() {
    setActiveIndex(selectedIndex);
    setOpenPicker("repeat");
  }

  function openDayListbox() {
    setActiveDayIndex(selectedDayIndex);
    setOpenPicker("day");
  }

  function selectOption(option: TriggerOption) {
    onChange(option.value);
    setOpenPicker(null);
    repeatTriggerRef.current?.focus();
  }

  function selectDay(dayName: string) {
    setInternalDay(dayName);
    onDayChange?.(dayName);
    setOpenPicker(null);
    dayTriggerRef.current?.focus();
  }

  function handleRepeatKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape" || event.key === "Tab") {
      setOpenPicker(null);
      if (event.key === "Escape") repeatTriggerRef.current?.focus();
      return;
    }

    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      selectOption(triggerOptions[activeIndex]);
      return;
    }

    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    setActiveIndex((current) => {
      if (event.key === "Home") return 0;
      if (event.key === "End") return triggerOptions.length - 1;
      return (current + (event.key === "ArrowDown" ? 1 : -1) + triggerOptions.length) % triggerOptions.length;
    });
  }

  function handleDayKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape" || event.key === "Tab") {
      setOpenPicker(null);
      if (event.key === "Escape") dayTriggerRef.current?.focus();
      return;
    }

    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      selectDay(daysOfWeek[activeDayIndex]);
      return;
    }

    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    setActiveDayIndex((current) => {
      if (event.key === "Home") return 0;
      if (event.key === "End") return daysOfWeek.length - 1;
      return (current + (event.key === "ArrowDown" ? 1 : -1) + daysOfWeek.length) % daysOfWeek.length;
    });
  }

  return (
    <div
      ref={rootRef}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpenPicker(null);
      }}
      className="relative rounded-[12px] border border-black/10 bg-white divide-y divide-black/[0.06] dark:border-white/12 dark:bg-white/[0.04] dark:divide-white/[0.06]"
    >
      {/* Row 1: Repeat / Frequency Trigger Selection */}
      <div className="relative flex min-h-[44px] items-center justify-between px-3.5 py-2 text-[13px]">
        <span className="font-normal text-black/75 dark:text-white/80">Repeat</span>
        <button
          ref={repeatTriggerRef}
          type="button"
          aria-label={`Trigger: ${selected.label}`}
          aria-haspopup="listbox"
          aria-expanded={openPicker === "repeat"}
          aria-controls={listboxId}
          onClick={() => {
            if (openPicker === "repeat") setOpenPicker(null);
            else openRepeatListbox();
          }}
          onKeyDown={(event) => {
            if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
            event.preventDefault();
            openRepeatListbox();
          }}
          className="inline-flex items-center gap-1.5 rounded-[6px] px-2 py-1 font-normal text-black/75 hover:bg-black/[0.04] hover:text-black dark:text-white/80 dark:hover:bg-white/[0.08] dark:hover:text-white transition outline-none focus-visible:ring-2 focus-visible:ring-black/15 dark:focus-visible:ring-white/15"
        >
          <span>{selected.label}</span>
          <HugeiconsIcon
            icon={ArrowDown01Icon}
            size={14}
            color="currentColor"
            strokeWidth={1.5}
            aria-hidden="true"
            className={`shrink-0 text-black/40 transition dark:text-white/42 ${openPicker === "repeat" ? "rotate-180" : ""}`}
          />
        </button>

        {openPicker === "repeat" ? (
          <div
            ref={listboxRef}
            id={listboxId}
            role="listbox"
            tabIndex={0}
            aria-label="Routine trigger"
            aria-activedescendant={`${listboxId}-option-${activeIndex}`}
            onKeyDown={handleRepeatKeyDown}
            className="absolute right-3 top-[calc(100%+4px)] z-50 min-w-[200px] max-h-[min(360px,70vh)] overflow-y-auto rounded-[12px] border border-black/10 bg-white p-1.5 shadow-[0_14px_40px_rgb(15_23_42_/_0.14)] dark:border-white/12 dark:bg-[#202125] dark:shadow-black/40"
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
                  const optionIndex = triggerOptions.indexOf(option);
                  const active = optionIndex === activeIndex;
                  const isSelected = option.value === value;
                  return (
                    <button
                      key={option.value}
                      type="button"
                      id={`${listboxId}-option-${optionIndex}`}
                      role="option"
                      tabIndex={-1}
                      aria-selected={isSelected}
                      onClick={() => selectOption(option)}
                      onPointerMove={() => setActiveIndex(optionIndex)}
                      className={`flex w-full items-center justify-between gap-2.5 rounded-[9px] px-2.5 py-2 text-left outline-none transition ${isSelected ? "bg-[#eef1f4] text-black/88 dark:bg-white/[0.08] dark:text-white/92" : "text-black/72 hover:bg-black/[0.04] dark:text-white/75 dark:hover:bg-white/[0.07]"} ${active ? "ring-2 ring-inset ring-black/15 dark:ring-white/15" : ""}`}
                    >
                      <span className="min-w-0 flex-1 truncate text-[13px] font-medium">{option.label}</span>
                      {isSelected ? <HugeiconsIcon icon={Tick02Icon} size={16} color="currentColor" strokeWidth={1.5} className="shrink-0 text-black/65 dark:text-white/75" aria-hidden="true" /> : null}
                    </button>
                  );
                })}
              </div>
            ))}
          </div>
        ) : null}
      </div>

      {/* Row 2: Day Selection (Shown when Weekly is selected) */}
      {value === "WEEKLY" ? (
        <div className="relative flex min-h-[44px] items-center justify-between px-3.5 py-2 text-[13px]">
          <span className="font-normal text-black/75 dark:text-white/80">On</span>
          <button
            ref={dayTriggerRef}
            type="button"
            aria-label={`Day: ${activeDay}`}
            aria-haspopup="listbox"
            aria-expanded={openPicker === "day"}
            aria-controls={`${listboxId}-day`}
            onClick={() => {
              if (openPicker === "day") setOpenPicker(null);
              else openDayListbox();
            }}
            onKeyDown={(event) => {
              if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
              event.preventDefault();
              openDayListbox();
            }}
            className="inline-flex items-center gap-1.5 rounded-[6px] px-2 py-1 font-normal text-black/75 hover:bg-black/[0.04] hover:text-black dark:text-white/80 dark:hover:bg-white/[0.08] dark:hover:text-white transition outline-none focus-visible:ring-2 focus-visible:ring-black/15 dark:focus-visible:ring-white/15"
          >
            <span>{activeDay}</span>
            <HugeiconsIcon
              icon={ArrowDown01Icon}
              size={14}
              color="currentColor"
              strokeWidth={1.5}
              aria-hidden="true"
              className={`shrink-0 text-black/40 transition dark:text-white/42 ${openPicker === "day" ? "rotate-180" : ""}`}
            />
          </button>

          {openPicker === "day" ? (
            <div
              ref={listboxRef}
              id={`${listboxId}-day`}
              role="listbox"
              tabIndex={0}
              aria-label="Day of week"
              aria-activedescendant={`${listboxId}-day-option-${activeDayIndex}`}
              onKeyDown={handleDayKeyDown}
              className="absolute right-3 top-[calc(100%+4px)] z-50 min-w-[160px] max-h-[min(320px,60vh)] overflow-y-auto rounded-[12px] border border-black/10 bg-white p-1.5 shadow-[0_14px_40px_rgb(15_23_42_/_0.14)] dark:border-white/12 dark:bg-[#202125] dark:shadow-black/40"
            >
              {daysOfWeek.map((dayName, dayIndex) => {
                const active = dayIndex === activeDayIndex;
                const isSelected = dayName === activeDay;
                return (
                  <button
                    key={dayName}
                    type="button"
                    id={`${listboxId}-day-option-${dayIndex}`}
                    role="option"
                    tabIndex={-1}
                    aria-selected={isSelected}
                    onClick={() => selectDay(dayName)}
                    onPointerMove={() => setActiveDayIndex(dayIndex)}
                    className={`flex w-full items-center justify-between gap-2.5 rounded-[9px] px-2.5 py-2 text-left outline-none transition ${isSelected ? "bg-[#eef1f4] text-black/88 dark:bg-white/[0.08] dark:text-white/92" : "text-black/72 hover:bg-black/[0.04] dark:text-white/75 dark:hover:bg-white/[0.07]"} ${active ? "ring-2 ring-inset ring-black/15 dark:ring-white/15" : ""}`}
                  >
                    <span className="min-w-0 flex-1 truncate text-[13px] font-medium">{dayName}</span>
                    {isSelected ? <HugeiconsIcon icon={Tick02Icon} size={16} color="currentColor" strokeWidth={1.5} className="shrink-0 text-black/65 dark:text-white/75" aria-hidden="true" /> : null}
                  </button>
                );
              })}
            </div>
          ) : null}
        </div>
      ) : null}

      {/* Row 3: Execution Mode */}
      <div className="flex min-h-[44px] items-center justify-between px-3.5 py-2 text-[13px]">
        <span className="font-normal text-black/75 dark:text-white/80">Execution</span>
        <span className="text-[13px] font-normal text-black/45 dark:text-white/45">Automatic draft</span>
      </div>
    </div>
  );
}
