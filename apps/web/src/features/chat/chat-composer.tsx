"use client";

import {
  ChatComposer as AstryxChatComposer,
  ChatComposerDrawer,
  ChatComposerInput,
  ChatSendButton,
} from "@astryxdesign/core/Chat";
import { Citation } from "@astryxdesign/core/Citation";
import { ArrowUp, Folder, Mic, Plus } from "lucide-react";
import type { CSSProperties, KeyboardEvent } from "react";
import { useId, useRef, useState } from "react";

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
  const [centerPrompt, setCenterPrompt] = useState("");
  const hasConnectedSource = references.some((reference) => reference.available);

  function submit(value: string) {
    if (submittingRef.current) return;
    const trimmed = value.trim();
    if (!trimmed) return;

    submittingRef.current = true;
    onSubmit(trimmed, []);
    queueMicrotask(() => {
      submittingRef.current = false;
    });
  }

  if (variant === "center") {
    const isSendDisabled = busy || !hasConnectedSource || !centerPrompt.trim();

    function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        if (!isSendDisabled) {
          submit(centerPrompt);
        }
      }
    }

    return (
      <div id={id} className="w-full">
        <div className="w-full rounded-[22px] border border-black/[0.08] bg-white shadow-[0_4px_24px_rgba(0,0,0,0.05)] transition focus-within:border-black/20 focus-within:shadow-[0_8px_32px_rgba(0,0,0,0.08)] dark:border-white/10 dark:bg-[#1e1f23] dark:shadow-[0_4px_24px_rgba(0,0,0,0.2)]">
          {/* Top section: input and actions */}
          <div className="p-4 pb-2.5 sm:px-5 sm:pt-4.5">
            <textarea
              role="textbox"
              aria-label="Chat message"
              rows={2}
              value={centerPrompt}
              onChange={(e) => setCenterPrompt(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={placeholder || "Describe the update you need..."}
              className="w-full resize-none border-none bg-transparent text-[15px] leading-6 text-black/88 outline-none placeholder:text-black/35 focus:ring-0 dark:text-white/90 dark:placeholder:text-white/35"
            />
            <div className="flex items-center justify-between pt-3 pb-1">
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  aria-label="Add attachment"
                  className="inline-flex size-7.5 items-center justify-center rounded-full text-black/50 transition hover:bg-black/5 hover:text-black/80 dark:text-white/50 dark:hover:bg-white/10 dark:hover:text-white"
                >
                  <Plus className="size-4" />
                </button>
              </div>
              <div className="flex items-center gap-1">
                <button
                  type="button"
                  aria-label="Voice input"
                  className="inline-flex size-7.5 items-center justify-center rounded-full text-black/50 transition hover:bg-black/5 hover:text-black/80 dark:text-white/50 dark:hover:bg-white/10 dark:hover:text-white"
                >
                  <Mic className="size-4" />
                </button>
                <button
                  type="button"
                  aria-label="Send message"
                  disabled={isSendDisabled}
                  onClick={() => submit(centerPrompt)}
                  className="inline-flex size-8 items-center justify-center rounded-full bg-primary text-primary-foreground transition hover:bg-[#303036] active:bg-black disabled:pointer-events-none disabled:opacity-30 dark:bg-[#f4f4f5] dark:text-[#18181b] dark:hover:bg-white dark:active:bg-white dark:disabled:opacity-20"
                >
                  <ArrowUp className="size-4" />
                </button>
              </div>
            </div>
          </div>

          {/* Bottom attached tray */}
          <div className="flex items-center justify-between border-t border-black/[0.05] bg-[#f8f9fa] px-4 py-2.5 text-xs text-black/60 dark:border-white/[0.06] dark:bg-[#16171a] dark:text-white/60 rounded-b-[22px]">
            <div className="flex items-center gap-2">
              <button
                type="button"
                className="inline-flex items-center gap-1.5 font-medium text-black/70 transition hover:text-black/90 dark:text-white/70 dark:hover:text-white"
              >
                <Folder className="size-3.5 text-black/50 dark:text-white/50" />
                <span>{references.length ? `${references.length} Connected Sources` : "Connect sources"}</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div
      id={id}
      className={cn(
        "w-full",
        variant === "dock"
          ? "bg-[#fbfbf8]/95 px-4 pb-4 pt-3 backdrop-blur-xl dark:bg-[#111113]/95 sm:px-6"
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
        className="!rounded-full rounded-full bg-primary text-primary-foreground hover:bg-[#303036] active:bg-black disabled:bg-black/25 dark:bg-[#f4f4f5] dark:text-[#18181b] dark:hover:bg-white dark:active:bg-white dark:disabled:bg-white/20"
      />
    </>
  );
}
