"use client";

import { LoaderCircle } from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState } from "react";

import type { ChatReasoningEffort } from "@plot/api-client";
import { PromptInput, PromptInputSubmit, PromptInputTextarea } from "@/components/ai-elements/prompt-input";
import { PromptSkillChips } from "@/components/primitives/prompt-bar-chips";
import { Icon } from "@/components/primitives/prompt-bar-icon";
import { PromptModelMenu } from "@/components/primitives/prompt-bar-model-menu";
import { PromptSkillMenu } from "@/components/primitives/prompt-bar-skill-menu";
import { Popover, PopoverAnchor, PopoverContent } from "@/components/ui/popover";
import { ChatModelIcon, type ChatModelProvider } from "@/features/chat/chat-model-icon";
import { REASONING_EFFORT_OPTIONS, preferredReasoningEffort, reasoningEffortsFor } from "@/lib/reasoning-effort";

/* ─────────────────────────────────────────────────────────
 * PROMPT BAR
 * A composer with a skill picker, a model picker, and send.
 * Type / to open the skill menu; ↑↓ + Enter to pick.
 * Variants: Rounded (card radius) · Pill (full radius).
 * ───────────────────────────────────────────────────────── */

export type PromptModelOption = {
  id: string;
  label: string;
  description: string;
  pricing: string;
  provider: ChatModelProvider;
  reasoningEfforts?: readonly ChatReasoningEffort[];
  reasoningDefault?: ChatReasoningEffort;
};

export interface PromptSkill {
  id: string;
  name: string;
  description: string;
  content?: string;
  revision?: number;
  isSystem?: boolean;
}

const MAX_SELECTED_SKILLS = 4;
const MODEL_MENU_WIDTH = 288;

/* the /word being typed at the end of the draft, if any */
function parseSkillToken(draft: string): { query: string; start: number } | null {
  const match = /(^|\s)\/([\w-]*)$/.exec(draft);
  if (!match) return null;
  return {
    query: match[2].toLowerCase(),
    start: match.index + match[1].length,
  };
}

export default function PromptBar({
  variant = "Rounded",
  tall = false,
  placeholder,
  onSend,
  ariaLabel = "Prompt",
  sendLabel = "Send",
  extraControls,
  disabled = false,
  busy = false,
  sendButtonClassName,
  skills = [],
  selectedSkillIds = [],
  onSelectedSkillIdsChange,
  models,
  selectedModelId,
  onSelectedModelIdChange,
  reasoningEffort,
  onReasoningEffortChange,
  modelPlacement = tall ? "auto" : "top",
}: {
  variant?: string;
  /** hero sizing: a multi-line input with controls on their own row */
  tall?: boolean;
  placeholder?: string;
  onSend?: (text: string) => void | boolean;
  ariaLabel?: string;
  sendLabel?: string;
  extraControls?: React.ReactNode;
  disabled?: boolean;
  busy?: boolean;
  sendButtonClassName?: string;
  skills?: PromptSkill[];
  selectedSkillIds?: string[];
  onSelectedSkillIdsChange?: (ids: string[]) => void;
  models: readonly PromptModelOption[];
  selectedModelId?: string;
  onSelectedModelIdChange?: (id: string) => void;
  reasoningEffort?: ChatReasoningEffort;
  onReasoningEffortChange?: (effort: ChatReasoningEffort) => void;
  modelPlacement?: "top" | "bottom" | "auto";
}) {
  const pill = variant === "Pill";
  const [draft, setDraft] = useState("");
  const [dismissed, setDismissed] = useState(false);
  const [modelOpen, setModelOpen] = useState(false);
  const [modelMenuPlacement, setModelMenuPlacement] = useState<"top" | "bottom">(
    modelPlacement === "top" || !tall ? "top" : "bottom"
  );
  const [modelMenuLeft, setModelMenuLeft] = useState(0);
  const [internalModelId, setInternalModelId] = useState(models[0]?.id ?? "");
  const [internalReasoningEffort, setInternalReasoningEffort] = useState<ChatReasoningEffort>("medium");
  const [active, setActive] = useState(0);
  const [engaged, setEngaged] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const wide = expanded || tall;
  const composerAnchorRef = useRef<HTMLDivElement>(null);
  const controlsRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const measureRef = useRef<HTMLSpanElement>(null);
  const modelRef = useRef<HTMLButtonElement>(null);
  const modelMenuRef = useRef<HTMLDivElement>(null);

  const model: PromptModelOption | undefined = models.find((item) => item.id === selectedModelId)
    ?? models.find((item) => item.id === internalModelId)
    ?? models[0];
  const modelReasoningEfforts = model ? reasoningEffortsFor(model) : [];
  const availableReasoningEffortOptions = REASONING_EFFORT_OPTIONS.filter((option) => modelReasoningEfforts.includes(option.value));
  const reasoningEnabled =
    (reasoningEffort !== undefined || onReasoningEffortChange !== undefined) &&
    availableReasoningEffortOptions.length > 0;
  const selectedReasoningEffort = availableReasoningEffortOptions.find(
    (option) => option.value === (reasoningEffort ?? internalReasoningEffort),
  )
    ?? availableReasoningEffortOptions.find((option) => option.value === model?.reasoningDefault)
    ?? availableReasoningEffortOptions[0]
    ?? REASONING_EFFORT_OPTIONS[3];

  const token = dismissed ? null : parseSkillToken(draft);
  const skillMenuOpen = token !== null;
  const query = token?.query ?? "";
  const matchingSkills = skillMenuOpen
    ? skills.filter((skill) =>
        skill.name.toLowerCase().includes(query) || skill.description.toLowerCase().includes(query)
      )
    : [];

  const [prevSkillMenuOpen, setPrevSkillMenuOpen] = useState(skillMenuOpen);
  const [prevQuery, setPrevQuery] = useState(query);
  if (prevSkillMenuOpen !== skillMenuOpen || prevQuery !== query) {
    setPrevSkillMenuOpen(skillMenuOpen);
    setPrevQuery(query);
    setActive(0);
    setEngaged(false);
  }

  /* The menu is outside the clipped composer. Align it horizontally to the
   * model trigger, and open above or below depending on available space and placement. */
  useLayoutEffect(() => {
    if (!modelOpen || !composerAnchorRef.current || !modelRef.current) return;
    const anchorRect = composerAnchorRef.current.getBoundingClientRect();
    const triggerRect = modelRef.current.getBoundingClientRect();
    const spaceBelow = window.innerHeight - triggerRect.bottom;
    const spaceAbove = triggerRect.top;
    const menuHeight = reasoningEnabled ? 460 : 360;

    const shouldOpenAbove =
      modelPlacement === "top"
        ? true
        : modelPlacement === "bottom"
          ? false
          : !tall || spaceBelow < menuHeight || spaceBelow < spaceAbove;

    setModelMenuPlacement(shouldOpenAbove ? "top" : "bottom");
    setModelMenuLeft(Math.max(0, Math.min(triggerRect.left - anchorRect.left, anchorRect.width - MODEL_MENU_WIDTH)));
  }, [modelOpen, wide, model?.label, tall, modelPlacement, reasoningEnabled]);

  const selectModel = (next: PromptModelOption) => {
    const currentReasoningEffort = reasoningEffort ?? internalReasoningEffort;
    const nextReasoningEfforts = reasoningEffortsFor(next);
    if (reasoningEnabled && nextReasoningEfforts.length > 0 && !nextReasoningEfforts.includes(currentReasoningEffort)) {
      const fallback = preferredReasoningEffort(next, nextReasoningEfforts) ?? "medium";
      setInternalReasoningEffort(fallback);
      onReasoningEffortChange?.(fallback);
    }
    setInternalModelId(next.id);
    onSelectedModelIdChange?.(next.id);
    setModelOpen(false);
    inputRef.current?.focus();
  };

  /* Move wrapped text above the controls, then grow to a compact maximum. */
  useLayoutEffect(() => {
    const input = inputRef.current;
    const controls = controlsRef.current;
    const measure = measureRef.current;
    if (!input || !controls || !measure) return;

    const fixedControlsWidth = 28 * 2 + (modelRef.current?.offsetWidth ?? 0);
    const inlineGaps = 4 * 3;
    const inlineInputWidth = controls.clientWidth - fixedControlsWidth - inlineGaps;
    const needsFullWidth = draft.includes("\n") || measure.offsetWidth + 8 > inlineInputWidth;
    if (needsFullWidth !== expanded) {
      setExpanded(needsFullWidth);
    }

    const minHeight = 28;
    const maxHeight = 100;
    input.style.height = "0px";
    const contentHeight = input.scrollHeight;
    input.style.height = `${Math.min(Math.max(contentHeight, minHeight), maxHeight)}px`;
    input.style.overflowY = contentHeight > maxHeight ? "auto" : "hidden";
  }, [draft, expanded]);

  /* clicking anywhere outside the composer closes the model menu */
  useEffect(() => {
    if (!modelOpen) return;
    const close = (event: PointerEvent) => {
      if (!(event.target as Element).closest("[data-promptbar]")) setModelOpen(false);
    };
    document.addEventListener("pointerdown", close);
    return () => document.removeEventListener("pointerdown", close);
  }, [modelOpen]);

  const selectedSkills = skills.filter((skill) => selectedSkillIds.includes(skill.id));

  const pickSkill = (skill: PromptSkill) => {
    if (onSelectedSkillIdsChange && !selectedSkillIds.includes(skill.id) && selectedSkillIds.length < MAX_SELECTED_SKILLS) {
      onSelectedSkillIdsChange([...selectedSkillIds, skill.id]);
    }
    setDraft(token ? draft.slice(0, token.start).trimEnd() : draft.replace(/\/\S*$/, "").trimEnd());
    setDismissed(false);
    inputRef.current?.focus();
  };

  const openSkillMenu = () => {
    setModelOpen(false);
    setDismissed(false);
    setDraft((prev) => {
      if (prev.endsWith("/")) return prev;
      if (!prev.trim()) return "/";
      return `${prev.trimEnd()} /`;
    });
    inputRef.current?.focus();
  };

  const canSend = !disabled && !busy && draft.trim().length > 0;
  const send = () => {
    if (!canSend) return;
    if (onSend?.(draft.trim()) === false) return;
    setDraft("");
    setModelOpen(false);
  };

  return (
    <PromptInput onSubmit={send} data-promptbar className="w-full">
      <Popover open={modelOpen} onOpenChange={setModelOpen}>
      {/* composer is the anchor for the menus */}
      <PopoverAnchor asChild><div ref={composerAnchorRef} className="relative">
      {skillMenuOpen && (
        <PromptSkillMenu
          skills={matchingSkills}
          query={query}
          activeIndex={active}
          highlighted={engaged}
          selectedSkillIds={selectedSkillIds}
          onHover={(index) => {
            setActive(index);
            setEngaged(true);
          }}
          onLeave={() => setEngaged(false)}
          onPick={pickSkill}
        />
      )}

      {model ? (
        <PopoverContent
          data-promptbar
          side={modelMenuPlacement}
          align="start"
          alignOffset={modelMenuLeft}
          sideOffset={modelMenuPlacement === "top" ? 8 : -6}
          onCloseAutoFocus={(event) => {event.preventDefault();inputRef.current?.focus();}}
          onOpenAutoFocus={(event) => {event.preventDefault();modelMenuRef.current?.querySelector<HTMLInputElement>("input")?.focus();}}
          ref={modelMenuRef}
          className="glass-layer z-30 w-72 p-0 overflow-visible rounded-[12px] border border-line"
        >
          <PromptModelMenu
            models={models}
            selectedModelId={model.id}
            onSelectModel={selectModel}
            onDismiss={() => {
              setModelOpen(false);
              inputRef.current?.focus();
            }}
            effortOptions={reasoningEnabled ? availableReasoningEffortOptions : null}
            selectedEffort={selectedReasoningEffort}
            onSelectEffort={(effort) => {
              setInternalReasoningEffort(effort);
              onReasoningEffortChange?.(effort);
            }}
            popoverRef={modelMenuRef}
            placementKey={`${modelMenuPlacement}:${modelMenuLeft}`}
          />
        </PopoverContent>
      ) : null}

      {/* ── composer ───────────────────────────────────── */}
      <div
        className={`glass-card relative isolate flex flex-col overflow-hidden border border-line transition-[border-color,border-radius] duration-150 focus-within:border-line-strong ${
          tall ? "gap-2.5 p-3.5" : "gap-1.5 p-1.5"
        } ${
          pill ? (wide ? "rounded-[24px]" : "rounded-full") : tall ? "rounded-[22px]" : "rounded-[14px]"
        }`}
      >
        <span
          ref={measureRef}
          aria-hidden="true"
          className="pointer-events-none absolute invisible whitespace-pre text-[13px] leading-[18px]"
        >
          {draft}
        </span>

        <PromptSkillChips
          skills={selectedSkills}
          pill={pill}
          onRemove={(skillId) => {
            onSelectedSkillIdsChange?.(selectedSkillIds.filter((id) => id !== skillId));
            inputRef.current?.focus();
          }}
        />

        <div
          ref={controlsRef}
          className="grid grid-cols-[28px_minmax(0,1fr)_auto_28px] items-end gap-x-1 gap-y-1.5"
        >
          <button
            type="button"
            aria-label="Choose skill (/)"
            title="Choose writing guide (/)"
            onClick={openSkillMenu}
            className={`glass-button glass-icon flex size-7 shrink-0 items-center justify-center justify-self-start font-mono text-[13px] font-semibold ${wide ? "col-start-1 row-start-2" : "col-start-1 row-start-1"}`}
          >
            /
          </button>

          <PromptInputTextarea
            ref={inputRef}
            rows={1}
            value={draft}
            onChange={(event) => {
              setDraft(event.target.value);
              setDismissed(false);
            }}
            onInput={(event) => {
              const target = event.target as HTMLTextAreaElement;
              const val = target.value ?? event.currentTarget.textContent ?? "";
              setDraft(val);
              setDismissed(false);
            }}
            onKeyDown={(event) => {
              if (skillMenuOpen && matchingSkills.length > 0) {
                if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                  event.preventDefault();
                  setEngaged(true);
                  setActive((current) => (current + (event.key === "ArrowDown" ? 1 : matchingSkills.length - 1)) % matchingSkills.length);
                  return;
                }
                if ((event.key === "Enter" && !event.shiftKey) || event.key === "Tab") {
                  event.preventDefault();
                  pickSkill(matchingSkills[active]);
                  return;
                }
              }
              if (event.key === "Backspace" && draft === "" && selectedSkillIds.length > 0) {
                onSelectedSkillIdsChange?.(selectedSkillIds.slice(0, -1));
                return;
              }
              if (event.key === "Escape") {
                setDismissed(true);
                setModelOpen(false);
              }
            }}
            placeholder={placeholder ?? "Write a message…"}
            aria-label={ariaLabel}
            className={`${tall ? "min-h-[68px] px-2 py-2 text-[14px] leading-5" : "min-h-7 px-1 py-[5px] text-[13px] leading-[18px]"} min-w-0 w-full resize-none bg-transparent text-ink outline-none [overflow-wrap:anywhere] placeholder:text-ink-3 ${
              wide ? "col-span-full col-start-1 row-start-1" : "col-start-2 row-start-1"
            }`}
          />

          {extraControls ? (
            <div className={wide ? "col-start-2 row-start-2 justify-self-start" : "hidden"}>
              {extraControls}
            </div>
          ) : null}

          {model ? (
            <button
              ref={modelRef}
              type="button"
              aria-expanded={modelOpen}
              aria-label="Choose model"
              onClick={() => setModelOpen((current) => !current)}
              className={`glass-button flex shrink-0 items-center gap-1 ${wide ? "col-start-3 row-start-2 justify-self-end" : "col-start-3 row-start-1"}`}
            >
              <ChatModelIcon className="size-3.5" provider={model.provider} />
              {model.label}
              <span className="text-ink-3">
                <Icon size={11} strokeWidth={2.4}><path d="M6 9l6 6 6-6" /></Icon>
              </span>
            </button>
          ) : null}

          {/* send — tactile square (round in the pill variant) */}
          <PromptInputSubmit
            aria-label={sendLabel}
            title={busy ? "Response in progress" : sendLabel}
            aria-busy={busy}
            disabled={!canSend}
            className={`glass-button glass-icon glass-primary flex size-7 shrink-0 items-center justify-center ${wide ? "col-start-4 row-start-2" : "col-start-4 row-start-1"} ${sendButtonClassName || ""}`}
          >
            {busy ? <LoaderCircle aria-hidden="true" className="size-4 animate-spin" /> : <Icon size={16} strokeWidth={2.4}><path d="M12 19V5M5 12l7-7 7 7" /></Icon>}
          </PromptInputSubmit>
        </div>
      </div>
      </div></PopoverAnchor>
      </Popover>
    </PromptInput>
  );
}

export { PromptBar };
