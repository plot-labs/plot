"use client";

import { useLayoutEffect, useRef, useState } from "react";

import type { PromptSkill } from "@/components/primitives/prompt-bar";
import { GlidingHighlight } from "@/components/primitives/prompt-bar-icon";

/**
 * The skill list that opens above the composer while a `/word` is being typed.
 * The textarea keeps focus, so the parent owns the active row and moves it
 * with the arrow keys; this menu only reports hover and clicks.
 */
export function PromptSkillMenu({
  skills,
  query,
  activeIndex,
  highlighted,
  selectedSkillIds,
  onHover,
  onLeave,
  onPick,
}: {
  skills: PromptSkill[];
  query: string;
  activeIndex: number;
  /** whether the pointer or arrow keys have engaged the list yet */
  highlighted: boolean;
  selectedSkillIds: string[];
  onHover: (index: number) => void;
  onLeave: () => void;
  onPick: (skill: PromptSkill) => void;
}) {
  const rowRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const [rowBox, setRowBox] = useState<{ top: number; height: number } | null>(null);

  useLayoutEffect(() => {
    const target = rowRefs.current[activeIndex];
    if (target) setRowBox({ top: target.offsetTop, height: target.offsetHeight });
  }, [query, activeIndex, skills.length]);

  return (
    <div
      onMouseLeave={onLeave}
      className="glass-layer absolute inset-x-0 bottom-full z-20 mb-2 max-h-72 overflow-y-auto rounded-[12px] border border-line p-1.5"
      style={{ animation: "pop-in 180ms cubic-bezier(0.23,1,0.32,1) both", transformOrigin: "bottom center" }}
    >
      <GlidingHighlight box={rowBox} visible={highlighted && skills.length > 0} className="inset-x-1.5 rounded-[8px]" />
      {skills.map((skill, i) => (
        <button
          key={skill.id}
          type="button"
          ref={(el) => {
            rowRefs.current[i] = el;
          }}
          onMouseDown={(event) => event.preventDefault()}
          onMouseEnter={() => onHover(i)}
          onClick={() => onPick(skill)}
          className="glass-control relative z-10 flex w-full flex-col justify-center rounded-[8px] px-3 py-2 text-left"
        >
          <div className="flex w-full items-center justify-between gap-2">
            <span className="text-[13px] font-semibold text-ink">/{skill.name}</span>
            {selectedSkillIds.includes(skill.id) ? (
              <span className="text-[11px] font-medium text-accent-ink">Selected</span>
            ) : null}
          </div>
          <span className="line-clamp-2 text-[12px] text-ink-3">{skill.description}</span>
        </button>
      ))}
      {skills.length === 0 && (
        <div className="flex h-9 items-center px-3 text-[12px] text-ink-3">
          No skills matching “{query}”
        </div>
      )}
      <div className="mt-1 border-t border-line px-2.5 pt-1.5 pb-0.5 text-[11px] text-ink-3">
        Type to search skills · Up to 4 guides
      </div>
    </div>
  );
}
