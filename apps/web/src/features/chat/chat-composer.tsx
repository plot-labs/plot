"use client";

import { ArrowUp } from "lucide-react";
import { useRef, useState } from "react";

import { cn } from "@/lib/utils";

type ChatComposerProps = {
  onSubmit: (message: string, referenceIds: string[]) => void;
  variant?: "center" | "dock";
  id?: string;
  placeholder?: string;
  references?: { id: string; label: string; available: boolean; groupId?: string }[];
  busy?: boolean;
};

export function ChatComposer({
  onSubmit,
  variant = "dock",
  id,
  placeholder = "Ask Plot to create another source-backed artifact...",
  references = [],
  busy = false,
}: ChatComposerProps) {
  const [message, setMessage] = useState("");
  const submittingRef = useRef(false);
  const [selectedReferenceIds] = useState<string[]>([]);
  const canSubmit = !busy && Boolean(message.trim()) && references.some((reference) => reference.available);

  return (
    <form
      id={id}
      className={cn(
        variant === "dock"
          ? "bg-white/95 px-4 py-4 dark:bg-[#111113]/95 sm:px-8"
          : "w-full",
      )}
      onSubmit={(event) => {
        event.preventDefault();
        if (!canSubmit || submittingRef.current) return;

        submittingRef.current = true;
        onSubmit(message.trim(), selectedReferenceIds);
        setMessage("");
        queueMicrotask(() => { submittingRef.current = false; });
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
          aria-label="Chat message"
        />
        <div className="flex items-center justify-end px-3 pb-3 text-xs text-black/45 dark:text-white/45">
          <div className="flex items-center gap-1.5">
            <button
              type="submit"
              disabled={!canSubmit}
              className="inline-flex size-9 items-center justify-center rounded-full bg-black/35 text-white transition hover:bg-black/55 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-white/35 dark:hover:bg-white/55"
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
