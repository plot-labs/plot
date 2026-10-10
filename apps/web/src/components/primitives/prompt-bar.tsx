"use client";

import { LoaderCircle } from "lucide-react";

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { Command } from "@/components/ui/command";
import { Popover, PopoverAnchor, PopoverContent } from "@/components/ui/popover";
import { ModelSelectorInput, ModelSelectorList, ModelSelectorItem, ModelSelectorName } from "@/components/ai-elements/model-selector";
import { PromptInput, PromptInputTextarea, PromptInputSubmit } from "@/components/ai-elements/prompt-input";
import { createShader, playSweep, accentChain, ACCENTS } from "glimm";
import type { ChatReasoningEffort } from "@plot/api-client";
import { ChatModelIcon, type ChatModelProvider } from "@/features/chat/chat-model-icon";
import { PromptAttachmentChips, PromptSkillChips } from "@/components/primitives/prompt-bar-chips";
import {
  AUTO_STEPS,
  BRANDS,
  COMMANDS,
  DEMO_MODELS,
  DICTATION,
  FILES,
  GLYPHS,
  Icon,
  SOURCES,
} from "@/components/primitives/prompt-bar-demo";
import { REASONING_EFFORT_OPTIONS, preferredReasoningEffort, reasoningEffortsFor } from "@/lib/reasoning-effort";

/* The built-in "prism" palette is only cyan→indigo→magenta, so a sweep
 * reads as blue/purple. Build a true full-spectrum rainbow instead. */
const RAINBOW = accentChain([
  ACCENTS.red,
  ACCENTS.orange,
  ACCENTS.yellow,
  ACCENTS.green,
  ACCENTS.cyan,
  ACCENTS.blue,
  ACCENTS.purple,
]);

/* ─────────────────────────────────────────────────────────
 * PROMPT BAR
 * A composer with real controls: attach, @ data sources,
 * / commands, a model picker, dictation, and send.
 * Type @ or / to open the menus; ↑↓ + Enter to pick.
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

/* the last @word or /word being typed, if any */
function parseToken(draft: string): { kind: "at" | "slash"; query: string; start: number } | null {
  const match = /(^|\s)([@/])([\w-]*)$/.exec(draft);
  if (!match) return null;
  return {
    kind: match[2] === "@" ? "at" : "slash",
    query: match[3].toLowerCase(),
    start: match.index + match[1].length,
  };
}

export interface PromptSkill {
  id: string;
  name: string;
  description: string;
  content?: string;
  revision?: number;
  isSystem?: boolean;
}

export default function PromptBar({
  variant = "Rounded",
  demo = true,
  tall = false,
  placeholder,
  onSend,
  ariaLabel = "Prompt",
  sendLabel = "Send",
  extraControls,
  disabled = false,
  busy = false,
  sendButtonClassName,
  skills,
  selectedSkillIds = [],
  onSelectedSkillIdsChange,
  models = DEMO_MODELS,
  selectedModelId,
  onSelectedModelIdChange,
  reasoningEffort,
  onReasoningEffortChange,
  modelPlacement = tall ? "auto" : "top",
}: {
  variant?: string;
  /** the self-running walkthrough; turn off when embedding in a real surface */
  demo?: boolean;
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
  models?: readonly PromptModelOption[];
  selectedModelId?: string;
  onSelectedModelIdChange?: (id: string) => void;
  reasoningEffort?: ChatReasoningEffort;
  onReasoningEffortChange?: (effort: ChatReasoningEffort) => void;
  modelPlacement?: "top" | "bottom" | "auto";
}) {
  const pill = variant === "Pill";
  const [draft, setDraft] = useState("");
  const [dismissed, setDismissed] = useState(false);
  const [plusOpen, setPlusOpen] = useState(false);
  const [modelOpen, setModelOpen] = useState(false);
  const [modelMenuPlacement, setModelMenuPlacement] = useState<"top" | "bottom">(
    modelPlacement === "top" || !tall ? "top" : "bottom"
  );
  const [internalModelId, setInternalModelId] = useState(DEMO_MODELS[1].id);
  const [internalReasoningEffort, setInternalReasoningEffort] = useState<ChatReasoningEffort>("medium");
  const [modelQuery, setModelQuery] = useState("");
  const [attachments, setAttachments] = useState<string[]>([]);
  const [connected, setConnected] = useState(false);
  const [active, setActive] = useState(0);
  const [listening, setListening] = useState(false);
  const [auto, setAuto] = useState(demo);
  const [autoStep, setAutoStep] = useState(0);
  const [expanded, setExpanded] = useState(false);
  const wide = expanded || tall;
  const [effortOpen, setEffortOpen] = useState(false);
  const [rowBox, setRowBox] = useState<{ top: number; height: number } | null>(null);
  const [engaged, setEngaged] = useState(false);
  const [modelBox, setModelBox] = useState<{ top: number; height: number } | null>(null);
  const [modelHovered, setModelHovered] = useState<number | null>(null);
  const [modelMenuLeft, setModelMenuLeft] = useState(0);
  const [effortMenuSide, setEffortMenuSide] = useState<"left" | "right">("right");
  const composerAnchorRef = useRef<HTMLDivElement>(null);
  const controlsRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const measureRef = useRef<HTMLSpanElement>(null);
  const modelRef = useRef<HTMLButtonElement>(null);
  const modelMenuRef = useRef<HTMLDivElement>(null);
  const rowRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const modelRowRefs = useRef<(HTMLDivElement | null)[]>([]);
  const glimmRef = useRef<HTMLCanvasElement>(null);
  const shaderRef = useRef<ReturnType<typeof createShader> | null>(null);
  const sweepingRef = useRef(false);
  const model = models.find((item) => item.id === selectedModelId)
    ?? models.find((item) => item.id === internalModelId)
    ?? models[0]
    ?? DEMO_MODELS[0];
  const modelReasoningEfforts = reasoningEffortsFor(model);
  const availableReasoningEffortOptions = REASONING_EFFORT_OPTIONS.filter((option) => modelReasoningEfforts.includes(option.value));
  const reasoningEnabled =
    (reasoningEffort !== undefined || onReasoningEffortChange !== undefined) &&
    availableReasoningEffortOptions.length > 0;
  const selectedReasoningEffort = availableReasoningEffortOptions.find(
    (option) => option.value === (reasoningEffort ?? internalReasoningEffort),
  )
    ?? availableReasoningEffortOptions.find((option) => option.value === model.reasoningDefault)
    ?? availableReasoningEffortOptions[0]
    ?? REASONING_EFFORT_OPTIONS[3];
  const filteredModels = models.filter((item) =>
    `${item.label} ${item.provider} ${item.description}`.toLowerCase().includes(modelQuery.trim().toLowerCase())
  );

  /* hand control to the user: stop the demo loop, and when they aim at
   * the input itself, clear the demo's leftover draft for a clean start */
  const takeOver = (event: { target: EventTarget | null }) => {
    setAuto(false);
    if (auto && event.target === inputRef.current) setDraft("");
  };

  const token = dismissed ? null : parseToken(draft);
  const menu: "at" | "slash" | null = plusOpen ? "at" : token?.kind ?? null;
  const query = plusOpen ? "" : token?.query ?? "";

  const availableCommands =
    skills && skills.length > 0
      ? skills.map((s) => ({
          key: s.id,
          name: `/${s.name}`,
          desc: s.description,
          skill: s,
        }))
      : COMMANDS;

  const rows: { key: string; name: string; desc: string; skill?: PromptSkill }[] =
    menu === "at"
      ? SOURCES.filter((s) => s.name.toLowerCase().includes(query))
      : menu === "slash"
        ? availableCommands.filter((c) =>
            c.name.slice(1).toLowerCase().includes(query) ||
            c.desc.toLowerCase().includes(query)
          )
        : [];

  const [prevMenu, setPrevMenu] = useState(menu);
  const [prevQuery, setPrevQuery] = useState(query);
  if (prevMenu !== menu || prevQuery !== query) {
    setPrevMenu(menu);
    setPrevQuery(query);
    setActive(0);
    setEngaged(false);
  }

  /* a single highlight glides to the active row instead of each row
   * toggling its own background — matches the gliding pill in the nav */
  useLayoutEffect(() => {
    const target = rowRefs.current[active];
    if (target) setRowBox({ top: target.offsetTop, height: target.offsetHeight });
  }, [menu, query, active, connected, rows.length]);

  /* same gliding highlight in the model menu — floats to the hovered
   * row, falling back to the currently-selected model */
  const modelIndex = filteredModels.findIndex((item) => item.id === model.id);
  useLayoutEffect(() => {
    if (!modelOpen) return;
    const target = modelRowRefs.current[modelHovered ?? modelIndex];
    if (target) setModelBox({ top: target.offsetTop, height: target.offsetHeight });
  }, [modelOpen, modelHovered, modelIndex, filteredModels.length]);

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
    setModelMenuLeft(Math.max(0, Math.min(triggerRect.left - anchorRect.left, anchorRect.width - 288)));
  }, [modelOpen, wide, model.label, tall, modelPlacement, reasoningEnabled]);

  useLayoutEffect(() => {
    if (!effortOpen || !modelMenuRef.current) return;
    const menuRect = modelMenuRef.current.getBoundingClientRect();
    const effortMenuWidth = 208;
    setEffortMenuSide(window.innerWidth - menuRect.right >= effortMenuWidth + 8 ? "right" : "left");
  }, [effortOpen, modelMenuLeft, modelMenuPlacement, modelReasoningEfforts.length]);

  const [prevModelOpen, setPrevModelOpen] = useState(modelOpen);
  if (prevModelOpen !== modelOpen) {
    setPrevModelOpen(modelOpen);
    if (!modelOpen && modelHovered !== null) {
      setModelHovered(null);
    }
  }

  /* Build the shader with a pinned hue phase. createShader seeds its
   * internal hueShift from Math.random(), which made the sweep a different
   * colour on every reload — pin it so the rainbow is identical each time. */
  const makeShader = useCallback(() => {
    const canvas = glimmRef.current;
    if (!canvas) return null;
    const random = Math.random;
    Math.random = () => 0;
    try {
      return createShader({
        canvas,
        palette: RAINBOW,
        direction: "ltr",
        bandTight: 10,
        swellAmount: 0.85,
      });
    } finally {
      Math.random = random;
    }
  }, []);

  /* Glimm shader lives inside the composer, invisible at rest. Selecting
   * the flagship model fires a one-shot rainbow sweep across the interior. */
  useEffect(() => {
    shaderRef.current = makeShader();
    return () => {
      shaderRef.current?.destroy();
      shaderRef.current = null;
    };
  }, [makeShader]);

  const celebrate = useCallback(() => {
    if (sweepingRef.current) return;
    if (typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) return;
    // Recreate the shader per sweep so uTime restarts at 0 — the hue phase
    // (which drifts with time) is then identical on every trigger.
    shaderRef.current?.destroy();
    const shader = makeShader();
    shaderRef.current = shader;
    if (!shader) return;
    sweepingRef.current = true;
    const sweep = playSweep(shader, {
      palette: RAINBOW,
      direction: "ltr",
      sweepMs: 570,
      outroMs: 80,
      peakAlpha: 1.3,
      bandTight: 10,
      brightness: 1.4,
      swellAmount: 1,
      waveSpeed: 1.8,
      easing: "easeOutExpo",
    });
    sweep.done.finally(() => {
      sweepingRef.current = false;
    });
  }, [makeShader]);

  const selectModel = useCallback((next: PromptModelOption) => {
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
    setEffortOpen(false);
    setModelQuery("");
    if (next.id === "sprinkles-5") celebrate();
  }, [celebrate, internalReasoningEffort, onReasoningEffortChange, onSelectedModelIdChange, reasoningEffort, reasoningEnabled]);

  /* autoplay: apply the current step, then advance after its hold */
  useEffect(() => {
    if (!auto) return;
    const step = AUTO_STEPS[autoStep % AUTO_STEPS.length];
    const t = setTimeout(() => {
      setDraft(step.draft);
      if (step.active !== undefined) setActive(step.active);
      if (step.connect !== undefined) setConnected(step.connect);
      if (step.modelOpen !== undefined) {
        setModelOpen(step.modelOpen);
        if (!step.modelOpen) setEffortOpen(false);
      }
      if (step.model) {
        const next = models.find((item) => item.id === step.model);
        if (next) selectModel(next);
      }
      setAutoStep((s) => s + 1);
    }, step.hold);
    return () => clearTimeout(t);
  }, [auto, autoStep, models, selectModel]);

  /* dictation resolves after a beat, like a real transcript landing */
  useEffect(() => {
    if (!listening) return;
    const t = setTimeout(() => {
      setDraft((current) => (current ? `${current.trimEnd()} ${DICTATION}` : DICTATION));
      setListening(false);
      inputRef.current?.focus();
    }, 2200);
    return () => clearTimeout(t);
  }, [listening]);

  /* Move wrapped text above the controls, then grow to a compact maximum. */
  useLayoutEffect(() => {
    const input = inputRef.current;
    const controls = controlsRef.current;
    const measure = measureRef.current;
    const modelButton = modelRef.current;
    if (!input || !controls || !measure || !modelButton) return;

    const fixedControlsWidth = 28 * 4 + modelButton.offsetWidth;
    const inlineGaps = 5 * 4;
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

  /* clicking anywhere outside the composer closes the open menus */
  useEffect(() => {
    if (!modelOpen && !plusOpen) return;
    const close = (event: PointerEvent) => {
      if (!(event.target as Element).closest("[data-promptbar]")) {
        setModelOpen(false);
        setPlusOpen(false);
        setEffortOpen(false);
      }
    };
    document.addEventListener("pointerdown", close);
    return () => document.removeEventListener("pointerdown", close);
  }, [modelOpen, plusOpen]);

  const closeMenus = () => {
    setPlusOpen(false);
    setModelOpen(false);
    setEffortOpen(false);
  };

  const selectedSkills = (skills ?? []).filter((s) => selectedSkillIds.includes(s.id));

  const pick = (row: { key: string; name: string; desc?: string; skill?: PromptSkill }) => {
    if (menu === "slash" && row.skill) {
      const skillId = row.key;
      if (onSelectedSkillIdsChange) {
        if (!selectedSkillIds.includes(skillId) && selectedSkillIds.length < 4) {
          onSelectedSkillIdsChange([...selectedSkillIds, skillId]);
        }
      }
      if (token) {
        setDraft(draft.slice(0, token.start).trimEnd());
      } else {
        setDraft(draft.replace(/\/\S*$/, "").trimEnd());
      }
      setDismissed(false);
      setPlusOpen(false);
      inputRef.current?.focus();
      return;
    }

    const source = SOURCES.find((s) => s.key === row.key);
    if (source?.attach) {
      setAttachments((current) => [...current, FILES[current.length % FILES.length]]);
      if (token) setDraft(draft.slice(0, token.start));
    } else if (menu === "at") {
      setDraft(`${token ? draft.slice(0, token.start) : draft}@${row.name} `);
    } else {
      setDraft(`${token ? draft.slice(0, token.start) : draft}${row.name} `);
    }
    setPlusOpen(false);
    setDismissed(false);
    inputRef.current?.focus();
  };

  const triggerSlash = () => {
    setModelOpen(false);
    setEffortOpen(false);
    setPlusOpen(false);
    setDismissed(false);
    setDraft((prev) => {
      if (prev.endsWith("/")) return prev;
      if (!prev.trim()) return "/";
      return `${prev.trimEnd()} /`;
    });
    inputRef.current?.focus();
  };

  const canSend = !disabled && !busy && (draft.trim().length > 0 || attachments.length > 0);
  const send = () => {
    if (!canSend) return;
    if (onSend?.(draft.trim()) === false) return;
    setDraft("");
    setAttachments([]);
    closeMenus();
  };

  return (
    <PromptInput
      onSubmit={send}
      data-promptbar
      className={demo ? "flex min-h-[384px] w-full max-w-105 flex-col justify-end pb-8" : "w-full"}
      onPointerDownCapture={takeOver}
      onKeyDownCapture={takeOver}
    >
      <Popover open={modelOpen} onOpenChange={(open) => {setModelOpen(open);if (!open) {setEffortOpen(false);setModelQuery("");}}}>
      {/* composer is the anchor for the menus */}
      <PopoverAnchor asChild><div ref={composerAnchorRef} className="relative">
      {/* ── @ / slash menu ─────────────────────────────── */}
      {menu && (
        <div
          onMouseLeave={() => setEngaged(false)}
          className="glass-layer absolute inset-x-0 bottom-full z-20 mb-2 max-h-72 overflow-y-auto rounded-[12px] border border-line p-1.5"
          style={{ animation: "pop-in 180ms cubic-bezier(0.23,1,0.32,1) both", transformOrigin: "bottom center" }}
        >
          {/* single gliding highlight — appears once a row is hovered */}
          <span
            aria-hidden
            className="pointer-events-none absolute inset-x-1.5 rounded-[8px] bg-hover"
            style={{
              top: rowBox?.top ?? 0,
              height: rowBox?.height ?? 0,
              opacity: rowBox && engaged && rows.length > 0 ? 1 : 0,
              transition:
                "top 220ms cubic-bezier(0.23,1,0.32,1), height 220ms cubic-bezier(0.23,1,0.32,1), opacity 150ms ease",
            }}
          />
          {rows.map((row, i) => {
            const isSlash = menu === "slash";
            const source = menu === "at" ? SOURCES.find((s) => s.key === row.key) : undefined;
            const isSelected = isSlash && selectedSkillIds.includes(row.key);
            return (
              <button
                key={row.key}
                type="button"
                ref={(el) => {
                  rowRefs.current[i] = el;
                }}
                onMouseDown={(event) => event.preventDefault()}
                onMouseEnter={() => {
                  setActive(i);
                  setEngaged(true);
                }}
                onClick={() => pick(row)}
                className={`glass-control relative z-10 flex w-full rounded-[8px] px-3 text-left ${
                  isSlash ? "flex-col justify-center py-2" : "h-9 items-center gap-2.5"
                }`}
              >
                {source && (
                  <span className="flex size-5.5 shrink-0 items-center justify-center text-ink-2">
                    {source.brand ? BRANDS[source.brand] : <Icon size={15}>{GLYPHS[source.glyph ?? "clip"]}</Icon>}
                  </span>
                )}
                <div className="flex w-full items-center justify-between gap-2">
                  <span className="text-[13px] font-semibold text-ink">
                    {row.name}
                  </span>
                  {isSelected ? (
                    <span className="text-[11px] font-medium text-accent-ink">Selected</span>
                  ) : null}
                </div>
                <span className="line-clamp-2 text-[12px] text-ink-3">
                  {row.desc}
                </span>
                {source?.connect && (
                  <span
                    role="button"
                    tabIndex={-1}
                    onClick={(event) => {
                      event.stopPropagation();
                      setConnected((current) => !current);
                    }}
                    className={`shrink-0 text-[12px] font-medium transition-colors duration-100 ${
                      connected ? "text-green" : "text-accent-ink hover:underline"
                    }`}
                  >
                    {connected ? "Connected" : "Connect"}
                  </span>
                )}
              </button>
            );
          })}
          {rows.length === 0 && (
            <div className="flex h-9 items-center px-3 text-[12px] text-ink-3">
              {menu === "slash" ? `No skills matching “${query}”` : `No matches for “${query}”`}
            </div>
          )}
          <div className="mt-1 border-t border-line px-2.5 pt-1.5 pb-0.5 text-[11px] text-ink-3">
            {menu === "at" ? "Type to search sources & files" : "Type to search skills · Up to 4 guides"}
          </div>
        </div>
      )}

      {/* ── model menu ─────────────────────────────────── */}
        <PopoverContent
          data-promptbar
          side={modelMenuPlacement}
          align="start"
          alignOffset={modelMenuLeft}
          sideOffset={modelMenuPlacement === "top" ? 8 : -6}
          onCloseAutoFocus={(event) => {event.preventDefault();inputRef.current?.focus();}}
          onOpenAutoFocus={(event) => {event.preventDefault();modelMenuRef.current?.querySelector<HTMLInputElement>("input")?.focus();}}
          ref={modelMenuRef}
          onMouseLeave={() => setModelHovered(null)}
          className="glass-layer z-30 w-72 p-0 overflow-visible rounded-[12px] border border-line"
        >
          <Command shouldFilter={false} className="overflow-visible bg-transparent text-ink [&_[cmdk-input-wrapper]]:contents [&_[cmdk-input-wrapper]>svg]:hidden">
          <div className="border-b border-line p-2">
            <div className="flex h-8 items-center gap-2 rounded-[7px] bg-field px-2.5 text-ink-3">
              <Icon size={14}><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></Icon>
              <ModelSelectorInput
                autoFocus
                value={modelQuery}
                onValueChange={(value) => {
                  setModelQuery(value);
                  setModelHovered(null);
                }}
                onKeyDown={(event) => {
                  if (event.key === "Escape") {
                    event.preventDefault();
                    setModelOpen(false);
                    setEffortOpen(false);
                    setModelQuery("");
                    inputRef.current?.focus();
                  }
                }}
                placeholder="Search models..."
                aria-label="Search models"
                className="h-8 min-w-0 flex-1 rounded-none bg-transparent p-0 text-[13px] text-ink outline-none placeholder:text-ink-3"
              />
            </div>
          </div>
          <ModelSelectorList className="relative max-h-[280px] overflow-y-auto p-1.5" label="Available models">
          {/* single gliding highlight — floats to the hovered / selected row */}
          <span
            aria-hidden
            className="pointer-events-none absolute inset-x-1 rounded-[6px] bg-hover"
            style={{
              top: modelBox?.top ?? 0,
              height: modelBox?.height ?? 0,
              opacity: modelBox && modelHovered !== null ? 1 : 0,
              transition:
                "top 220ms cubic-bezier(0.23,1,0.32,1), height 220ms cubic-bezier(0.23,1,0.32,1), opacity 150ms ease",
            }}
          />
          {filteredModels.map((item, i) => (
            <ModelSelectorItem
              key={item.id}
              value={item.id}
              role="option"
              aria-selected={item.id === model.id}
              ref={(el) => {
                modelRowRefs.current[i] = el;
              }}
              onMouseDown={(event) => event.preventDefault()}
              onMouseEnter={() => setModelHovered(i)}
              onSelect={() => {
                selectModel(item);
                inputRef.current?.focus();
              }}
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
              <span className={`mt-1 shrink-0 text-ink ${item.id === model.id ? "" : "invisible"}`}>
                <Icon size={13} strokeWidth={2.5}><path d="M20 6L9 17l-5-5" /></Icon>
              </span>
            </ModelSelectorItem>
          ))}
          {filteredModels.length === 0 ? (
            <div className="px-3 py-6 text-center text-[12px] text-ink-3">No models found.</div>
          ) : null}
          </ModelSelectorList>
          </Command>
          {reasoningEnabled ? (
            <div className="relative border-t border-line px-2 pb-2 pt-2">
              <button
                type="button"
                aria-expanded={effortOpen}
                aria-haspopup="menu"
                aria-label={`Choose reasoning effort (currently ${selectedReasoningEffort.label})`}
                onClick={() => setEffortOpen((current) => !current)}
                className="glass-button flex w-full items-center justify-between gap-2 text-left"
              >
                <span className="text-[13px] font-medium">
                  <span className="text-ink-3">Effort</span>{" "}
                  <span className="text-ink">{selectedReasoningEffort.label}</span>
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
                    effortMenuSide === "right" ? "left-[calc(100%+8px)]" : "right-[calc(100%+8px)]"
                  }`}
                >
                  {availableReasoningEffortOptions.map((option) => {
                    const isSelected = option.value === selectedReasoningEffort.value;
                    return (
                      <button
                        key={option.value}
                        type="button"
                        role="menuitemradio"
                        aria-checked={isSelected}
                        onClick={() => {
                          setInternalReasoningEffort(option.value);
                          onReasoningEffortChange?.(option.value);
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
        </PopoverContent>

      {/* ── composer ───────────────────────────────────── */}
      <div
        className={`glass-card relative isolate flex flex-col overflow-hidden border border-line transition-[border-color,border-radius] duration-150 focus-within:border-line-strong ${
          tall ? "gap-2.5 p-3.5" : "gap-1.5 p-1.5"
        } ${
          pill ? (attachments.length > 0 || wide ? "rounded-[24px]" : "rounded-full") : tall ? "rounded-[22px]" : "rounded-[14px]"
        }`}
      >
        {/* rainbow glimm sweep — plays across the interior on model change.
            explicit w/h: a <canvas> is a replaced element and won't stretch
            to inset-0 alone, which feeds back into the shader's ResizeObserver. */}
        <canvas
          ref={glimmRef}
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 -z-10 h-full w-full"
          style={{ borderRadius: "inherit" }}
        />
        <span
          ref={measureRef}
          aria-hidden="true"
          className="pointer-events-none absolute invisible whitespace-pre text-[13px] leading-[18px]"
        >
          {draft}
        </span>

        <PromptAttachmentChips
          attachments={attachments}
          pill={pill}
          onRemove={(index) => setAttachments((current) => current.filter((_, j) => j !== index))}
        />

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
          className={`grid items-end gap-x-1 gap-y-1.5 ${
            wide
              ? "grid-cols-[28px_28px_minmax(0,1fr)_auto_28px_28px]"
              : "grid-cols-[28px_28px_minmax(0,1fr)_auto_28px_28px]"
          }`}
        >
          <button
            type="button"
            aria-label="Add attachments and sources"
            aria-expanded={plusOpen}
            onClick={() => {
              setModelOpen(false);
              setEffortOpen(false);
              setPlusOpen((current) => !current);
              inputRef.current?.focus();
            }}
            className={`glass-button glass-icon flex size-7 shrink-0 items-center justify-center justify-self-start ${wide ? "col-start-1 row-start-2" : "col-start-1 row-start-1"}`}
          >
            <Icon size={16} strokeWidth={2}><path d="M12 5v14M5 12h14" /></Icon>
          </button>

          <button
            type="button"
            aria-label="Choose skill (/)"
            title="Choose writing guide (/)"
            onClick={triggerSlash}
            className={`glass-button glass-icon flex size-7 shrink-0 items-center justify-center font-mono text-[13px] font-semibold ${wide ? "col-start-2 row-start-2" : "col-start-2 row-start-1"}`}
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
              setPlusOpen(false);
            }}
            onInput={(event) => {
              const target = event.target as HTMLTextAreaElement;
              const val = target.value ?? event.currentTarget.textContent ?? "";
              setDraft(val);
              setDismissed(false);
              setPlusOpen(false);
            }}
            onKeyDown={(event) => {
              if (menu && rows.length > 0) {
                if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                  event.preventDefault();
                  setEngaged(true);
                  setActive((current) => (current + (event.key === "ArrowDown" ? 1 : rows.length - 1)) % rows.length);
                  return;
                }
                if ((event.key === "Enter" && !event.shiftKey) || event.key === "Tab") {
                  event.preventDefault();
                  pick(rows[active]);
                  return;
                }
              }
              if (event.key === "Backspace" && draft === "" && selectedSkillIds.length > 0) {
                onSelectedSkillIdsChange?.(selectedSkillIds.slice(0, -1));
                return;
              }
              if (event.key === "Escape") {
                setDismissed(true);
                closeMenus();
                return;
              }

            }}
            placeholder={listening ? "Listening…" : placeholder ?? "Write a message…"}
            aria-label={ariaLabel}
            className={`${tall ? "min-h-[68px] px-2 py-2 text-[14px] leading-5" : "min-h-7 px-1 py-[5px] text-[13px] leading-[18px]"} min-w-0 w-full resize-none bg-transparent text-ink outline-none [overflow-wrap:anywhere] placeholder:text-ink-3 ${
              wide ? "col-span-full col-start-1 row-start-1" : "col-start-3 row-start-1"
            }`}
          />

          {extraControls ? (
            <div className={wide ? "col-start-3 row-start-2 justify-self-start" : "hidden"}>
              {extraControls}
            </div>
          ) : null}

          {/* model picker */}
          <button
            ref={modelRef}
            type="button"
            aria-expanded={modelOpen}
            aria-label="Choose model"
            onClick={() => {
              setPlusOpen(false);
              setModelOpen((current) => {
                if (current) {
                  setModelQuery("");
                  setEffortOpen(false);
                }
                return !current;
              });
            }}
            className={`glass-button flex shrink-0 items-center gap-1 ${wide ? "col-start-4 row-start-2 justify-self-end" : "col-start-4 row-start-1"}`}
          >
            <ChatModelIcon className="size-3.5" provider={model.provider} />
            {model.label}
            <span className="text-ink-3">
              <Icon size={11} strokeWidth={2.4}><path d="M6 9l6 6 6-6" /></Icon>
            </span>
          </button>

          {/* dictation */}
          <button
            type="button"
            aria-label={listening ? "Stop dictation" : "Start dictation"}
            aria-pressed={listening}
            onClick={() => setListening((current) => !current)}
            className={`glass-control flex size-7 shrink-0 items-center justify-center ${
              pill ? "rounded-full" : "rounded-[8px]"
            } ${listening ? "text-accent-ink" : ""} ${wide ? "col-start-5 row-start-2" : "col-start-5 row-start-1"}`}
          >
            {listening ? (
              <span className="flex h-3.5 items-center gap-[2.5px]">
                {[0, 1, 2].map((i) => (
                  <span
                    key={i}
                    className="w-[2.5px] rounded-full bg-current"
                    style={{ height: "100%", animation: `eq-bounce 900ms ease-in-out ${i * 150}ms infinite` }}
                  />
                ))}
              </span>
            ) : (
              <Icon size={15} strokeWidth={2}><g><path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z" /><path d="M19 10v2a7 7 0 0 1-14 0v-2M12 19v3" /></g></Icon>
            )}
          </button>

          {/* send — tactile square (round in the pill variant) */}
          <PromptInputSubmit
            aria-label={sendLabel}
            title={busy ? "Response in progress" : sendLabel}
            aria-busy={busy}
            disabled={!canSend}
            className={`glass-button glass-icon glass-primary flex size-7 shrink-0 items-center justify-center ${wide ? "col-start-6 row-start-2" : "col-start-6 row-start-1"} ${sendButtonClassName || ""}`}
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
