"use client";

import { Send } from "lucide-react";
import { useState } from "react";

type SessionComposerProps = {
  onSubmit: (message: string) => void;
};

export function SessionComposer({ onSubmit }: SessionComposerProps) {
  const [message, setMessage] = useState("");

  return (
    <form
      className="border-t border-black/10 bg-[#f8f5ef] px-8 py-4 dark:border-white/10 dark:bg-[#141414]"
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
      <div className="mx-auto flex max-w-3xl items-end gap-2 rounded-xl border border-black/10 bg-white p-2 shadow-lg shadow-black/5 dark:border-white/10 dark:bg-[#242424]">
        <textarea
          value={message}
          onChange={(event) => setMessage(event.target.value)}
          className="min-h-12 flex-1 resize-none bg-transparent px-2 py-2 text-sm outline-none placeholder:text-black/35 dark:placeholder:text-white/35"
          placeholder="Ask Plot to revise, use another reference, or create another draft..."
          aria-label="Session message"
        />
        <button
          type="submit"
          className="inline-flex size-10 items-center justify-center rounded-lg bg-black text-white transition hover:bg-black/85 dark:bg-white dark:text-black dark:hover:bg-white/85"
          aria-label="Send message"
        >
          <Send className="size-4" />
        </button>
      </div>
    </form>
  );
}
