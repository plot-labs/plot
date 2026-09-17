"use client";

import { ArrowDown01Icon, Tick02Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent } from "react";

import type { ChatModel, ChatReasoningEffort } from "@/lib/api-client";
import type { PromptModelOption } from "@/components/primitives/prompt-bar";

export type RoutineModelOption = Omit<PromptModelOption, "id"> & { id: ChatModel };

const ALL_REASONING_EFFORTS: readonly ChatReasoningEffort[] = [
  "none",
  "minimal",
  "low",
  "medium",
  "high",
  "xhigh",
  "max",
];

const EFFORT_OPTIONS: readonly { value: ChatReasoningEffort; label: string }[] = [
  { value: "none", label: "None" },
  { value: "minimal", label: "Minimal" },
  { value: "low", label: "Low" },
  { value: "medium", label: "Medium" },
  { value: "high", label: "High" },
  { value: "xhigh", label: "Extra high" },
  { value: "max", label: "Max" },
];

export function reasoningEffortsForRoutineModel(model: RoutineModelOption): readonly ChatReasoningEffort[] {
  return model.reasoningEfforts ?? ALL_REASONING_EFFORTS;
}

export function preferredReasoningEffortForRoutineModel(
  model: RoutineModelOption,
  supported: readonly ChatReasoningEffort[],
): ChatReasoningEffort | null {
  const preferred: (ChatReasoningEffort | undefined)[] = [
    model.reasoningDefault,
    "medium",
    "high",
    "low",
    "minimal",
    "none",
    "xhigh",
    "max",
  ];
  return preferred.find((effort): effort is ChatReasoningEffort => effort !== undefined && supported.includes(effort))
    ?? supported[0]
    ?? null;
}

type RoutineModelPickerProps = {
  models: readonly RoutineModelOption[];
  value: ChatModel;
  reasoningEffort: ChatReasoningEffort | null;
  onModelChange: (value: ChatModel) => void;
  onReasoningEffortChange: (value: ChatReasoningEffort) => void;
  disabled?: boolean;
};

export function RoutineModelPicker({
  models,
  value,
  reasoningEffort,
  onModelChange,
  onReasoningEffortChange,
  disabled = false,
}: RoutineModelPickerProps) {
  const selectedModel = models.find((model) => model.id === value) ?? models[0]!;
  const [openPicker, setOpenPicker] = useState<"model" | "effort" | null>(null);
  const [activeModelIndex, setActiveModelIndex] = useState(0);
  const [activeEffortIndex, setActiveEffortIndex] = useState(0);
  const [placement, setPlacement] = useState<"top" | "bottom">("bottom");
  const [effortPlacement, setEffortPlacement] = useState<"top" | "bottom">("bottom");
  const [effortSide, setEffortSide] = useState<"left" | "right">("right");
  const rootRef = useRef<HTMLDivElement>(null);
  const modelRowRef = useRef<HTMLDivElement>(null);
  const effortRowRef = useRef<HTMLDivElement>(null);
  const modelTriggerRef = useRef<HTMLButtonElement>(null);
  const effortTriggerRef = useRef<HTMLButtonElement>(null);
  const modelListRef = useRef<HTMLDivElement>(null);
  const effortListRef = useRef<HTMLDivElement>(null);
  const modelListId = useId();
  const effortListId = useId();

  const reasoningEfforts = reasoningEffortsForRoutineModel(selectedModel);
  const effortOptions = EFFORT_OPTIONS.filter((option) => reasoningEfforts.includes(option.value));
  const selectedEffort = effortOptions.find((option) => option.value === reasoningEffort)
    ?? effortOptions.find((option) => option.value === selectedModel.reasoningDefault)
    ?? effortOptions[0];

  useEffect(() => {
    if (!openPicker) return;
    const listRef = openPicker === "model" ? modelListRef : effortListRef;
    listRef.current?.focus();

    function dismissIfOutside(event: Event) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) setOpenPicker(null);
    }

    document.addEventListener("pointerdown", dismissIfOutside, true);
    return () => document.removeEventListener("pointerdown", dismissIfOutside, true);
  }, [openPicker]);

  useLayoutEffect(() => {
    if (!openPicker) return;
    const row = openPicker === "model" ? modelRowRef.current : effortRowRef.current;
    const trigger = openPicker === "model" ? modelTriggerRef.current : effortTriggerRef.current;
    if (!row || !trigger) return;

    const triggerRect = trigger.getBoundingClientRect();
    const spaceBelow = window.innerHeight - triggerRect.bottom;
    const spaceAbove = triggerRect.top;
    const menuHeight = Math.min(360, window.innerHeight * 0.7);
    setPlacement(spaceBelow < menuHeight && spaceAbove > spaceBelow ? "top" : "bottom");

    if (openPicker === "effort") {
      const menuWidth = 190;
      setEffortSide(window.innerWidth - triggerRect.right >= menuWidth + 8 ? "right" : "left");
      setEffortPlacement(spaceBelow >= menuHeight || spaceBelow >= spaceAbove ? "bottom" : "top");
    }
  }, [openPicker, selectedModel.id, effortOptions.length]);

  function openModelList() {
    setActiveModelIndex(Math.max(models.findIndex((model) => model.id === value), 0));
    setOpenPicker("model");
  }

  function openEffortList() {
    setActiveEffortIndex(Math.max(effortOptions.findIndex((option) => option.value === selectedEffort?.value), 0));
    setOpenPicker("effort");
  }

  function selectModel(model: RoutineModelOption) {
    onModelChange(model.id);
    setOpenPicker(null);
    modelTriggerRef.current?.focus();
  }

  function selectEffort(effort: ChatReasoningEffort) {
    onReasoningEffortChange(effort);
    setOpenPicker(null);
    effortTriggerRef.current?.focus();
  }

  function handleListKeyDown(event: KeyboardEvent<HTMLDivElement>, kind: "model" | "effort") {
    if (event.key === "Escape" || event.key === "Tab") {
      setOpenPicker(null);
      if (event.key === "Escape") (kind === "model" ? modelTriggerRef : effortTriggerRef).current?.focus();
      return;
    }
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      if (kind === "model" && models[activeModelIndex]) selectModel(models[activeModelIndex]);
      else if (effortOptions[activeEffortIndex]) selectEffort(effortOptions[activeEffortIndex].value);
      return;
    }
    if (![
      "ArrowDown",
      "ArrowUp",
      "Home",
      "End",
    ].includes(event.key)) return;
    event.preventDefault();
    if (kind === "model") {
      setActiveModelIndex((current) => moveIndex(current, event.key, models.length));
    } else {
      setActiveEffortIndex((current) => moveIndex(current, event.key, effortOptions.length));
    }
  }

  return (
    <div
      ref={rootRef}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpenPicker(null);
      }}
      className="relative divide-y divide-black/[0.06] rounded-[12px] border border-black/10 bg-white dark:divide-white/[0.06] dark:border-white/12 dark:bg-white/[0.04]"
    >
      <div ref={modelRowRef} className="relative flex min-h-[44px] items-center justify-between px-3.5 py-2 text-[13px]">
        <span className="font-normal text-black/75 dark:text-white/80">Model</span>
        <button
          ref={modelTriggerRef}
          type="button"
          disabled={disabled}
          aria-label={`Automation model: ${selectedModel.label}`}
          aria-haspopup="listbox"
          aria-expanded={openPicker === "model"}
          aria-controls={modelListId}
          onClick={() => openPicker === "model" ? setOpenPicker(null) : openModelList()}
          onKeyDown={(event) => {
            if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
            event.preventDefault();
            openModelList();
          }}
          className="inline-flex max-w-[75%] items-center gap-1.5 rounded-[6px] px-2 py-1 font-normal text-black/75 outline-none transition hover:bg-black/[0.04] hover:text-black focus-visible:ring-2 focus-visible:ring-black/15 disabled:cursor-not-allowed disabled:opacity-50 dark:text-white/80 dark:hover:bg-white/[0.08] dark:hover:text-white dark:focus-visible:ring-white/15"
        >
          <span className="truncate">{selectedModel.label}</span>
          <HugeiconsIcon icon={ArrowDown01Icon} size={14} color="currentColor" strokeWidth={1.5} aria-hidden="true" className={`shrink-0 text-black/40 transition dark:text-white/42 ${openPicker === "model" ? "rotate-180" : ""}`} />
        </button>

        {openPicker === "model" ? (
          <div
            ref={modelListRef}
            id={modelListId}
            role="listbox"
            tabIndex={0}
            aria-label="Automation model"
            aria-activedescendant={`${modelListId}-option-${activeModelIndex}`}
            onKeyDown={(event) => handleListKeyDown(event, "model")}
            className={`absolute right-3 z-50 min-w-[230px] max-h-[min(360px,70vh)] overflow-y-auto rounded-[12px] border border-black/10 bg-white p-1.5 shadow-[0_14px_40px_rgb(15_23_42_/_0.14)] dark:border-white/12 dark:bg-[#202125] dark:shadow-black/40 ${placement === "top" ? "bottom-[calc(100%+4px)]" : "top-[calc(100%+4px)]"}`}
          >
            {models.map((model, index) => {
              const isSelected = model.id === value;
              const isActive = index === activeModelIndex;
              return (
                <button
                  key={model.id}
                  id={`${modelListId}-option-${index}`}
                  type="button"
                  role="option"
                  tabIndex={-1}
                  aria-selected={isSelected}
                  onClick={() => selectModel(model)}
                  onPointerMove={() => setActiveModelIndex(index)}
                  className={`flex w-full items-center justify-between gap-2 rounded-[9px] px-2.5 py-2 text-left text-[13px] outline-none transition ${isSelected ? "bg-[#eef1f4] text-black/88 dark:bg-white/[0.08] dark:text-white/92" : "text-black/72 hover:bg-black/[0.04] dark:text-white/75 dark:hover:bg-white/[0.07]"} ${isActive ? "ring-2 ring-inset ring-black/15 dark:ring-white/15" : ""}`}
                >
                  <span className="min-w-0 truncate font-medium">{model.label}</span>
                  {isSelected ? <HugeiconsIcon icon={Tick02Icon} size={16} color="currentColor" strokeWidth={1.5} className="shrink-0 text-black/65 dark:text-white/75" aria-hidden="true" /> : null}
                </button>
              );
            })}
          </div>
        ) : null}
      </div>

      {selectedEffort ? (
        <div ref={effortRowRef} className="relative flex min-h-[44px] items-center justify-between px-3.5 py-2 text-[13px]">
          <span className="font-normal text-black/75 dark:text-white/80">Effort</span>
          <button
            ref={effortTriggerRef}
            type="button"
            disabled={disabled}
            aria-label={`Automation reasoning effort: ${selectedEffort.label}`}
            aria-haspopup="listbox"
            aria-expanded={openPicker === "effort"}
            aria-controls={effortListId}
            onClick={() => openPicker === "effort" ? setOpenPicker(null) : openEffortList()}
            onKeyDown={(event) => {
              if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
              event.preventDefault();
              openEffortList();
            }}
            className="inline-flex items-center gap-1.5 rounded-[6px] px-2 py-1 font-normal text-black/75 outline-none transition hover:bg-black/[0.04] hover:text-black focus-visible:ring-2 focus-visible:ring-black/15 disabled:cursor-not-allowed disabled:opacity-50 dark:text-white/80 dark:hover:bg-white/[0.08] dark:hover:text-white dark:focus-visible:ring-white/15"
          >
            <span>{selectedEffort.label}</span>
            <HugeiconsIcon icon={ArrowDown01Icon} size={14} color="currentColor" strokeWidth={1.5} aria-hidden="true" className={`shrink-0 text-black/40 transition dark:text-white/42 ${openPicker === "effort" ? "rotate-180" : ""}`} />
          </button>

          {openPicker === "effort" ? (
            <div
              ref={effortListRef}
              id={effortListId}
              role="listbox"
              tabIndex={0}
              aria-label="Automation reasoning effort"
              aria-activedescendant={`${effortListId}-option-${activeEffortIndex}`}
              onKeyDown={(event) => handleListKeyDown(event, "effort")}
              className={`absolute z-50 min-w-[180px] max-h-[min(300px,60vh)] overflow-y-auto rounded-[12px] border border-black/10 bg-white p-1.5 shadow-[0_14px_40px_rgb(15_23_42_/_0.14)] dark:border-white/12 dark:bg-[#202125] dark:shadow-black/40 ${effortPlacement === "top" ? "bottom-0" : "top-0"} ${effortSide === "right" ? "left-[calc(100%+8px)]" : "right-[calc(100%+8px)]"}`}
            >
              {effortOptions.map((option, index) => {
                const isSelected = option.value === selectedEffort.value;
                const isActive = index === activeEffortIndex;
                return (
                  <button
                    key={option.value}
                    id={`${effortListId}-option-${index}`}
                    type="button"
                    role="option"
                    tabIndex={-1}
                    aria-selected={isSelected}
                    onClick={() => selectEffort(option.value)}
                    onPointerMove={() => setActiveEffortIndex(index)}
                    className={`flex w-full items-center justify-between gap-2 rounded-[9px] px-2.5 py-2 text-left text-[13px] outline-none transition ${isSelected ? "bg-[#eef1f4] text-black/88 dark:bg-white/[0.08] dark:text-white/92" : "text-black/72 hover:bg-black/[0.04] dark:text-white/75 dark:hover:bg-white/[0.07]"} ${isActive ? "ring-2 ring-inset ring-black/15 dark:ring-white/15" : ""}`}
                  >
                    <span className="font-medium">{option.label}</span>
                    {isSelected ? <HugeiconsIcon icon={Tick02Icon} size={16} color="currentColor" strokeWidth={1.5} className="shrink-0 text-black/65 dark:text-white/75" aria-hidden="true" /> : null}
                  </button>
                );
              })}
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

function moveIndex(current: number, key: string, length: number) {
  if (!length) return 0;
  if (key === "Home") return 0;
  if (key === "End") return length - 1;
  return (current + (key === "ArrowDown" ? 1 : -1) + length) % length;
}
