"use client";

import { ArrowUp, ChevronDown, Mic, Plus, SlidersHorizontal, Sparkles } from "lucide-react";
import { useState } from "react";

import { cn } from "@/lib/utils";

type SessionComposerProps = {
  onSubmit: (message: string, referenceIds: string[]) => void;
  variant?: "center" | "dock";
  placeholder?: string;
  references?: { id: string; label: string; available: boolean; groupId?: string }[];
  busy?: boolean;
};

export function SessionComposer({
  onSubmit,
  variant = "dock",
  placeholder = "Ask Plot to revise, use another reference, or create another draft...",
  references = [],
  busy = false,
}: SessionComposerProps) {
  const [message, setMessage] = useState("");
  const [referencesOpen, setReferencesOpen] = useState(false);
  const [selectedReferenceIds, setSelectedReferenceIds] = useState<string[]>(() => {
    const first = references.find((reference) => reference.available);
    return first
      ? references.filter((reference) => reference.available && reference.groupId === first.groupId).map((reference) => reference.id)
      : [];
  });

  return (
    <form
      className={cn(
        variant === "dock"
          ? "bg-white/95 px-4 py-4 dark:bg-[#111113]/95 sm:px-8"
          : "w-full",
      )}
      onSubmit={(event) => {
        event.preventDefault();
        const trimmed = message.trim();

        if (!trimmed) {
          return;
        }

        onSubmit(trimmed, selectedReferenceIds);
        setMessage("");
      }}
    >
      <div
        className={cn(
          "mx-auto w-full max-w-[760px] overflow-hidden rounded-[18px] border border-black/[0.09] bg-white shadow-[0_16px_50px_rgb(0_0_0_/_0.08)] dark:border-white/10 dark:bg-[#232326] dark:shadow-black/30",
          variant === "dock" && "max-w-3xl shadow-[0_10px_28px_rgb(0_0_0_/_0.07)]",
        )}
      >
        <textarea
          value={message}
          onChange={(event) => setMessage(event.target.value)}
          className="min-h-20 w-full resize-none bg-transparent px-4 py-3 text-sm leading-6 outline-none placeholder:text-black/30 dark:placeholder:text-white/35"
          placeholder={placeholder}
          aria-label="Session message"
        />
        <div className="flex items-center gap-2 px-3 pb-3 text-xs text-black/45 dark:text-white/45">
          <button
            type="button"
            className="inline-flex size-8 items-center justify-center rounded-xl transition hover:bg-black/5 dark:hover:bg-white/10"
            aria-label="Attach source"
          >
            <Plus className="size-4" />
          </button>
          <div className="relative">
            <button
              type="button"
              aria-expanded={referencesOpen}
              onClick={() => setReferencesOpen((open) => !open)}
              className="inline-flex min-h-8 items-center gap-1.5 rounded-xl px-2 py-1.5 font-medium text-[#2563eb] transition hover:bg-[#2563eb]/5 dark:text-[#93c5fd] dark:hover:bg-white/10"
            >
              <Sparkles className="size-3.5" />
              References{selectedReferenceIds.length ? ` · ${selectedReferenceIds.length}` : ""}
              <ChevronDown className="size-3.5" />
            </button>
            {referencesOpen && references.length ? (
              <div className="absolute bottom-[calc(100%+8px)] left-0 z-30 w-[min(300px,calc(100vw-32px))] rounded-xl border border-black/10 bg-white p-2 shadow-xl dark:border-white/15 dark:bg-[#232326]">
                <div className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-[0.08em] text-black/42 dark:text-white/42">Use current references</div>
                {references.map((reference) => (
                  <label key={reference.id} className="flex min-h-11 items-center gap-2 rounded-lg px-2 text-sm hover:bg-black/5 dark:hover:bg-white/10">
                    <input
                      type="checkbox"
                      disabled={!reference.available || busy}
                      checked={selectedReferenceIds.includes(reference.id)}
                      onChange={(event) => setSelectedReferenceIds((current) => {
                        if (!event.target.checked) return current.filter((id) => id !== reference.id);
                        const currentGroup = references.find((item) => current.includes(item.id))?.groupId;
                        return currentGroup !== undefined && currentGroup !== reference.groupId ? [reference.id] : [...current, reference.id];
                      })}
                    />
                    <span className="min-w-0 flex-1 truncate">{reference.label}</span>
                    {!reference.available ? <span className="text-[10px] text-black/40 dark:text-white/40">Preview only</span> : null}
                  </label>
                ))}
              </div>
            ) : null}
          </div>

          <div className="ml-auto flex items-center gap-1.5">
            <button
              type="button"
              className="inline-flex items-center gap-1 rounded-xl px-2 py-1.5 transition hover:bg-black/5 dark:hover:bg-white/10"
            >
              <SlidersHorizontal className="size-3.5" />
              Voice
              <ChevronDown className="size-3.5" />
            </button>
            <button
              type="button"
              className="inline-flex size-8 items-center justify-center rounded-xl transition hover:bg-black/5 dark:hover:bg-white/10"
              aria-label="Voice input"
            >
              <Mic className="size-3.5" />
            </button>
            <button
              type="submit"
              disabled={busy}
              className="inline-flex size-9 items-center justify-center rounded-full bg-black/35 text-white transition hover:bg-black/55 dark:bg-white/35 dark:hover:bg-white/55"
              aria-label="Send message"
            >
              <ArrowUp className="size-4" />
            </button>
          </div>
        </div>
      </div>
    </form>
  );
}
