"use client";

import type { ContentType } from "@plot/api-client";

type ChatContentTypeSelectorProps = {
  value: ContentType;
  onChange: (next: ContentType) => void;
  disabled?: boolean;
};

const OPTIONS: Array<{ value: ContentType; label: string }> = [
  { value: "CHANGELOG", label: "Changelog" },
  { value: "LAUNCH_ANNOUNCEMENT", label: "Launch announcement" },
];

export function ChatContentTypeSelector({ value, onChange, disabled = false }: ChatContentTypeSelectorProps) {
  return (
    <div
      role="group"
      aria-label="Content type"
      className="inline-flex rounded-lg border border-black/[0.08] bg-black/[0.02] p-0.5 dark:border-white/10 dark:bg-white/[0.03]"
    >
      {OPTIONS.map((option) => {
        const selected = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            disabled={disabled}
            aria-pressed={selected}
            onClick={() => onChange(option.value)}
            className={[
              "rounded-md px-2.5 py-1 text-[11px] font-medium transition",
              selected
                ? "bg-white text-black/85 shadow-sm dark:bg-[#1e1f23] dark:text-white/90"
                : "text-black/50 hover:text-black/75 dark:text-white/50 dark:hover:text-white/75",
              disabled ? "opacity-50" : "",
            ].join(" ")}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
