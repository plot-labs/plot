"use client";

import { ArrowUp, ChevronDown, Mic, Plus, SlidersHorizontal, Sparkles } from "lucide-react";
import { useState } from "react";

import { cn } from "@/lib/utils";

type SessionComposerProps = {
  onSubmit: (message: string) => void;
  variant?: "center" | "dock";
  placeholder?: string;
};

export function SessionComposer({
  onSubmit,
  variant = "dock",
  placeholder = "Ask Plot to revise, use another reference, or create another draft...",
}: SessionComposerProps) {
  const [message, setMessage] = useState("");

  return (
    <form
      className={cn(
        variant === "dock"
          ? "border-t border-black/[0.08] bg-white/95 px-4 py-4 dark:border-white/10 dark:bg-[#111113]/95 sm:px-8"
          : "w-full",
      )}
      onSubmit={(event) => {
        event.preventDefault();
        const trimmed = message.trim();

        if (!trimmed) {
          return;
        }

        onSubmit(trimmed);
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
            className="inline-flex size-8 items-center justify-center rounded-md transition hover:bg-black/5 dark:hover:bg-white/10"
            aria-label="Attach source"
          >
            <Plus className="size-4" />
          </button>
          <button
            type="button"
            className="inline-flex items-center gap-1.5 rounded-md px-2 py-1.5 font-medium text-[#2563eb] transition hover:bg-[#2563eb]/5 dark:text-[#93c5fd] dark:hover:bg-white/10"
          >
            <Sparkles className="size-3.5" />
            References
            <ChevronDown className="size-3.5" />
          </button>

          <div className="ml-auto flex items-center gap-1.5">
            <button
              type="button"
              className="inline-flex items-center gap-1 rounded-md px-2 py-1.5 transition hover:bg-black/5 dark:hover:bg-white/10"
            >
              <SlidersHorizontal className="size-3.5" />
              Voice
              <ChevronDown className="size-3.5" />
            </button>
            <button
              type="button"
              className="inline-flex size-8 items-center justify-center rounded-md transition hover:bg-black/5 dark:hover:bg-white/10"
              aria-label="Voice input"
            >
              <Mic className="size-3.5" />
            </button>
            <button
              type="submit"
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
