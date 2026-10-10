import type { PromptSkill } from "@/components/primitives/prompt-bar";
import { Icon } from "@/components/primitives/prompt-bar-demo";

/** Attached files shown above the input, each with a remove button. */
export function PromptAttachmentChips({
  attachments,
  pill,
  onRemove,
}: {
  attachments: string[];
  pill: boolean;
  onRemove: (index: number) => void;
}) {
  if (attachments.length === 0) return null;
  return (
    <div className={`flex flex-wrap gap-1.5 pt-0.5 ${pill ? "px-1" : "px-0.5"}`}>
      {attachments.map((file, i) => (
        <span
          key={`${file}-${i}`}
          className={`flex h-6.5 items-center gap-1.5 bg-field py-1 pr-1 pl-1.5 text-[11.5px] text-ink-2 shadow-hairline ${
            pill ? "rounded-full" : "rounded-chip"
          }`}
          style={{ animation: "pop-in 200ms cubic-bezier(0.23,1,0.32,1) both" }}
        >
          <Icon size={12}><g><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><path d="M14 2v6h6" /></g></Icon>
          <span className="max-w-36 truncate">{file}</span>
          <button
            type="button"
            aria-label={`Remove ${file}`}
            onClick={() => onRemove(i)}
            className="glass-button glass-icon -my-1 flex size-6 items-center justify-center"
          >
            <Icon size={10} strokeWidth={2.5}><path d="M18 6L6 18M6 6l12 12" /></Icon>
          </button>
        </span>
      ))}
    </div>
  );
}

/** Skills selected for the next message, each with a remove button. */
export function PromptSkillChips({
  skills,
  pill,
  onRemove,
}: {
  skills: PromptSkill[];
  pill: boolean;
  onRemove: (skillId: string) => void;
}) {
  if (skills.length === 0) return null;
  return (
    <div className={`flex flex-wrap items-center gap-1.5 pt-0.5 ${pill ? "px-1" : "px-0.5"}`}>
      {skills.map((skill) => (
        <span
          key={skill.id}
          className="group inline-flex max-w-[460px] items-center gap-1.5 rounded-lg border border-line bg-surface/90 px-2.5 py-1 text-xs text-ink shadow-sm backdrop-blur-sm transition-colors hover:border-line-strong hover:bg-surface"
          style={{ animation: "pop-in 180ms cubic-bezier(0.23,1,0.32,1) both" }}
        >
          <span className="font-mono text-[11px] font-bold text-accent-ink">/</span>
          <span className="font-medium text-ink">{skill.name}</span>
          <span className="hidden max-w-[200px] truncate text-[11px] text-ink-3 sm:inline">
            {skill.description}
          </span>
          {skill.content ? (
            <span className="rounded bg-line/60 px-1 py-0.5 font-mono text-[10px] text-ink-3">
              +{skill.content.length}
            </span>
          ) : null}
          <button
            type="button"
            aria-label={`Remove skill ${skill.name}`}
            onClick={(e) => {
              e.stopPropagation();
              onRemove(skill.id);
            }}
            className="glass-button glass-icon -mr-1 flex size-5 items-center justify-center"
          >
            <Icon size={10} strokeWidth={2.5}><path d="M18 6L6 18M6 6l12 12" /></Icon>
          </button>
        </span>
      ))}
    </div>
  );
}
