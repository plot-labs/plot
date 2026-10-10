"use client";

import { useLayoutEffect, useRef, useState, type RefObject } from "react";

import type { ChatReasoningEffort } from "@plot/api-client";
import { ModelSelectorInput, ModelSelectorItem, ModelSelectorList, ModelSelectorName } from "@/components/ai-elements/model-selector";
import type { PromptModelOption } from "@/components/primitives/prompt-bar";
import { GlidingHighlight, Icon } from "@/components/primitives/prompt-bar-icon";
import { Command } from "@/components/ui/command";
import { ChatModelIcon } from "@/features/chat/chat-model-icon";

type EffortOption = { value: ChatReasoningEffort; label: string };

const EFFORT_MENU_WIDTH = 208;

/**
 * The contents of the model popover: search, the model list, and the reasoning
 * effort submenu. It mounts with the popover, so the search text, hover and
 * open submenu reset every time the popover closes.
 */
export function PromptModelMenu({
  models,
  selectedModelId,
  onSelectModel,
  onDismiss,
  effortOptions,
  selectedEffort,
  onSelectEffort,
  popoverRef,
  placementKey,
}: {
  models: readonly PromptModelOption[];
  selectedModelId: string;
  onSelectModel: (model: PromptModelOption) => void;
  /** Escape in the search field */
  onDismiss: () => void;
  /** null hides the effort control */
  effortOptions: readonly EffortOption[] | null;
  selectedEffort: EffortOption;
  onSelectEffort: (effort: ChatReasoningEffort) => void;
  /** the popover surface, measured to decide which side the effort submenu opens on */
  popoverRef: RefObject<HTMLDivElement | null>;
  /** changes whenever the popover is repositioned */
  placementKey: string;
}) {
  const [query, setQuery] = useState("");
  const [hovered, setHovered] = useState<number | null>(null);
  const [rowBox, setRowBox] = useState<{ top: number; height: number } | null>(null);
  const [effortOpen, setEffortOpen] = useState(false);
  const [effortSide, setEffortSide] = useState<"left" | "right">("right");
  const rowRefs = useRef<(HTMLDivElement | null)[]>([]);

  const filteredModels = models.filter((item) =>
    `${item.label} ${item.provider} ${item.description}`.toLowerCase().includes(query.trim().toLowerCase())
  );
  const selectedIndex = filteredModels.findIndex((item) => item.id === selectedModelId);

  /* the highlight floats to the hovered row, falling back to the selected model */
  useLayoutEffect(() => {
    const target = rowRefs.current[hovered ?? selectedIndex];
    if (target) setRowBox({ top: target.offsetTop, height: target.offsetHeight });
  }, [hovered, selectedIndex, filteredModels.length]);

  useLayoutEffect(() => {
    if (!effortOpen || !popoverRef.current) return;
    const menuRect = popoverRef.current.getBoundingClientRect();
    setEffortSide(window.innerWidth - menuRect.right >= EFFORT_MENU_WIDTH + 8 ? "right" : "left");
  }, [effortOpen, placementKey, effortOptions?.length, popoverRef]);

  return (
    <div onMouseLeave={() => setHovered(null)}>
      <Command shouldFilter={false} className="overflow-visible bg-transparent text-ink [&_[cmdk-input-wrapper]]:contents [&_[cmdk-input-wrapper]>svg]:hidden">
        <div className="border-b border-line p-2">
          <div className="flex h-8 items-center gap-2 rounded-[7px] bg-field px-2.5 text-ink-3">
            <Icon size={14}><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></Icon>
            <ModelSelectorInput
              autoFocus
              value={query}
              onValueChange={(value) => {
                setQuery(value);
                setHovered(null);
              }}
              onKeyDown={(event) => {
                if (event.key === "Escape") {
                  event.preventDefault();
                  onDismiss();
                }
              }}
              placeholder="Search models..."
              aria-label="Search models"
              className="h-8 min-w-0 flex-1 rounded-none bg-transparent p-0 text-[13px] text-ink outline-none placeholder:text-ink-3"
            />
          </div>
        </div>
        <ModelSelectorList className="relative max-h-[280px] overflow-y-auto p-1.5" label="Available models">
          <GlidingHighlight box={rowBox} visible={hovered !== null} className="inset-x-1 rounded-[6px]" />
          {filteredModels.map((item, i) => (
            <ModelSelectorItem
              key={item.id}
              value={item.id}
              role="option"
              aria-selected={item.id === selectedModelId}
              ref={(el) => {
                rowRefs.current[i] = el;
              }}
              onMouseDown={(event) => event.preventDefault()}
              onMouseEnter={() => setHovered(i)}
              onSelect={() => onSelectModel(item)}
              className="relative z-10 flex w-full items-start gap-2.5 rounded-[8px] px-2.5 py-2 text-left data-[selected=true]:bg-hover data-[selected=true]:text-ink"
            >
              <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center text-ink">
                <ChatModelIcon className="size-4" provider={item.provider} />
              </span>
              <span className="flex min-w-0 flex-1 flex-col">
                <ModelSelectorName className="text-[13px] font-medium text-ink">{item.label}</ModelSelectorName>
                <span className="text-[11.5px] leading-4 text-ink-3">{item.description}</span>
                <span className="text-[10px] leading-4 text-ink-3/75">{item.pricing}</span>
              </span>
              <span className={`mt-1 shrink-0 text-ink ${item.id === selectedModelId ? "" : "invisible"}`}>
                <Icon size={13} strokeWidth={2.5}><path d="M20 6L9 17l-5-5" /></Icon>
              </span>
            </ModelSelectorItem>
          ))}
          {filteredModels.length === 0 ? (
            <div className="px-3 py-6 text-center text-[12px] text-ink-3">No models found.</div>
          ) : null}
        </ModelSelectorList>
      </Command>
      {effortOptions ? (
        <div className="relative border-t border-line px-2 pb-2 pt-2">
          <button
            type="button"
            aria-expanded={effortOpen}
            aria-haspopup="menu"
            aria-label={`Choose reasoning effort (currently ${selectedEffort.label})`}
            onClick={() => setEffortOpen((current) => !current)}
            className="glass-button flex w-full items-center justify-between gap-2 text-left"
          >
            <span className="text-[13px] font-medium">
              <span className="text-ink-3">Effort</span>{" "}
              <span className="text-ink">{selectedEffort.label}</span>
            </span>
            <span className="text-ink-3">
              <Icon size={13} strokeWidth={2.2}><path d="m9 18 6-6-6-6" /></Icon>
            </span>
          </button>
          {effortOpen ? (
            <div
              role="menu"
              aria-label="Reasoning effort options"
              className={`glass-layer absolute bottom-0 z-40 w-52 rounded-[12px] border border-line p-1.5 ${
                effortSide === "right" ? "left-[calc(100%+8px)]" : "right-[calc(100%+8px)]"
              }`}
            >
              {effortOptions.map((option) => {
                const isSelected = option.value === selectedEffort.value;
                return (
                  <button
                    key={option.value}
                    type="button"
                    role="menuitemradio"
                    aria-checked={isSelected}
                    onClick={() => {
                      onSelectEffort(option.value);
                      setEffortOpen(false);
                    }}
                    className="glass-control flex w-full items-center gap-2 rounded-[8px] px-2.5 py-2 text-left"
                  >
                    <span className="min-w-0 flex-1 text-[13px] font-medium text-ink">{option.label}</span>
                    {isSelected ? (
                      <span className="shrink-0 text-ink">
                        <Icon size={13} strokeWidth={2.5}><path d="M20 6L9 17l-5-5" /></Icon>
                      </span>
                    ) : null}
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
