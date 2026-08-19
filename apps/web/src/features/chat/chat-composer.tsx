"use client";

import {
  ChatComposer as AstryxChatComposer,
  ChatComposerDrawer,
  ChatComposerInput,
  ChatSendButton,
} from "@astryxdesign/core/Chat";
import { Citation } from "@astryxdesign/core/Citation";
import type { CSSProperties } from "react";
import { useId, useRef } from "react";

import { cn } from "@/lib/utils";

type ChatComposerProps = {
  onSubmit: (message: string, referenceIds: string[]) => void;
  variant?: "center" | "dock";
  id?: string;
  placeholder?: string;
  references?: { id: string; label: string; available: boolean; groupId?: string; url?: string }[];
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
  const submittingRef = useRef(false);
  const hasConnectedSource = references.some((reference) => reference.available);

  function submit(value: string) {
    if (submittingRef.current) return;

    submittingRef.current = true;
    onSubmit(value, []);
    queueMicrotask(() => {
      submittingRef.current = false;
    });
  }

  return (
    <div
      id={id}
      className={cn(
        "w-full",
        variant === "dock"
          ? "border-t border-black/[0.06] bg-white/85 px-4 py-4 backdrop-blur-xl dark:border-white/[0.07] dark:bg-[#111113]/85 sm:px-8"
          : "px-1",
      )}
    >
      <AstryxChatComposer
        onSubmit={submit}
        placeholder={placeholder}
        isDisabled={busy || !hasConnectedSource}
        density={variant === "center" ? "compact" : "balanced"}
        elevation="none"
        drawer={references.length ? <ComposerSources references={references} /> : undefined}
        input={<ChatComposerInput label="Chat message" maxRows={variant === "center" ? 5 : 7} />}
        footerActions={variant === "center" ? null : <span className="text-xs text-black/42 dark:text-white/45">Enter to send</span>}
        sendButton={<ComposerSendButton />}
        className="mx-auto max-w-[720px]"
        style={{
          "--_chat-composer-radius": "12px",
          "--_chat-composer-padding": variant === "center" ? "12px" : "10px",
        } as CSSProperties}
      />
    </div>
  );
}

function ComposerSources({
  references,
}: {
  references: NonNullable<ChatComposerProps["references"]>;
}) {
  const visibleReferences = references.slice(0, 3);
  const remainingCount = references.length - visibleReferences.length;

  return (
    <ChatComposerDrawer count={references.length} label="Sources" defaultIsCollapsed>
      <div className="flex min-w-0 flex-wrap items-center gap-2" aria-label="Connected sources">
        {visibleReferences.map((reference, index) => (
          <Citation
            key={reference.id}
            source={{ title: reference.label, url: reference.url }}
            number={index + 1}
            variant="label"
          />
        ))}
        {remainingCount > 0 ? <span className="text-xs text-black/42 dark:text-white/45">{remainingCount} more</span> : null}
      </div>
    </ChatComposerDrawer>
  );
}

function ComposerSendButton() {
  const labelId = useId();

  return (
    <>
      <span id={labelId} className="sr-only">Send message</span>
      <ChatSendButton
        aria-labelledby={labelId}
        size="sm"
        className="bg-primary text-primary-foreground hover:bg-[#303036] active:bg-black disabled:bg-black/25 dark:bg-[#f4f4f5] dark:text-[#18181b] dark:hover:bg-white dark:active:bg-white dark:disabled:bg-white/20"
      />
    </>
  );
}
