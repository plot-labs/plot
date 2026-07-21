"use client";

import {
  Copy,
  Pencil,
  ThumbsDown,
  ThumbsUp,
} from "lucide-react";
import type { ReactNode } from "react";

import type { DraftDocument, ReferenceDocument, SessionMessage } from "@/lib/api-client";
import { cn } from "@/lib/utils";
import { SessionFloatingSummary } from "@/features/sessions/session-floating-summary";

type SessionThreadProps = {
  messages: SessionMessage[];
  drafts: DraftDocument[];
  references: ReferenceDocument[];
  floatingSummaryOpen: boolean;
  rightPanelOpen: boolean;
  onSelectDocument: (documentId: string) => void;
  generationPanel?: ReactNode;
};

export function SessionThread({
  messages,
  drafts,
  references,
  floatingSummaryOpen,
  rightPanelOpen,
  onSelectDocument,
  generationPanel,
}: SessionThreadProps) {
  return (
    <section className="min-h-0 flex-1 overflow-y-auto px-4 pb-8 pt-16 sm:px-6 lg:px-8 lg:pb-10 lg:pt-20">
      <div className="mx-auto max-w-3xl space-y-10 pb-40">
        {messages.map((message) => (
          <SessionThreadMessage
            key={message.id}
            message={message}
          />
        ))}
        {generationPanel}
      </div>

      {floatingSummaryOpen && (
        <div
          className={cn(
            "fixed top-[76px] z-20 hidden",
            rightPanelOpen ? "right-[484px] min-[2100px]:block" : "right-6 min-[1700px]:block",
          )}
        >
          <SessionFloatingSummary
            drafts={drafts}
            references={references}
            onSelectDocument={onSelectDocument}
          />
        </div>
      )}
    </section>
  );
}

function SessionThreadMessage({
  message,
}: {
  message: SessionMessage;
}) {
  if (message.role === "user") {
    return (
      <article className="flex justify-end">
        <div className="max-w-[min(720px,86%)]">
          <div className="rounded-[22px] bg-black/[0.055] px-5 py-3 text-[15px] leading-7 text-black/84 dark:bg-white/10 dark:text-white/84">
            <MessageContent content={message.content} compact />
          </div>
          <UserMessageActions timestamp={message.timestamp} />
        </div>
      </article>
    );
  }

  return (
    <article className="space-y-4">
      <div className="space-y-4 text-[15px] leading-7 text-black/82 dark:text-white/82">
        <MessageContent content={message.content} />
      </div>
      <AgentMessageActions />
    </article>
  );
}

function UserMessageActions({ timestamp }: { timestamp: string }) {
  return (
    <div className="mt-3 flex items-center justify-end gap-4 text-sm text-black/45 dark:text-white/45">
      <span>{timestamp}</span>
      <button
        type="button"
        className="inline-flex size-6 items-center justify-center rounded-lg transition hover:bg-black/5 hover:text-black/68 dark:hover:bg-white/10 dark:hover:text-white/70"
        aria-label="Copy message"
      >
        <Copy className="size-4" />
      </button>
      <button
        type="button"
        className="inline-flex size-6 items-center justify-center rounded-lg transition hover:bg-black/5 hover:text-black/68 dark:hover:bg-white/10 dark:hover:text-white/70"
        aria-label="Edit message"
      >
        <Pencil className="size-4" />
      </button>
    </div>
  );
}

function AgentMessageActions() {
  return (
    <div className="flex items-center gap-4 pt-1 text-black/42 dark:text-white/42">
      <button type="button" className="transition hover:text-black/68 dark:hover:text-white/70" aria-label="Copy response">
        <Copy className="size-4" />
      </button>
      <button type="button" className="transition hover:text-black/68 dark:hover:text-white/70" aria-label="Like response">
        <ThumbsUp className="size-4" />
      </button>
      <button type="button" className="transition hover:text-black/68 dark:hover:text-white/70" aria-label="Dislike response">
        <ThumbsDown className="size-4" />
      </button>
    </div>
  );
}

function MessageContent({ content, compact = false }: { content: string; compact?: boolean }) {
  const blocks = content.split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);

  if (blocks.length === 0) {
    return null;
  }

  return (
    <>
      {blocks.map((block, blockIndex) => {
        const lines = block.split("\n").map((line) => line.trim()).filter(Boolean);
        const isList = lines.every((line) => /^[-*]\s+/.test(line));

        if (isList) {
          return (
            <ul key={`${block}-${blockIndex}`} className="space-y-2 pl-5">
              {lines.map((line, lineIndex) => (
                <li key={`${line}-${lineIndex}`} className="list-disc pl-1">
                  {renderInlineText(line.replace(/^[-*]\s+/, ""))}
                </li>
              ))}
            </ul>
          );
        }

        if (lines.length === 1 && lines[0].startsWith(">")) {
          return (
            <blockquote
              key={`${block}-${blockIndex}`}
              className="border-l-4 border-black/10 pl-5 text-black/72 dark:border-white/14 dark:text-white/72"
            >
              {renderInlineText(lines[0].replace(/^>\s?/, ""))}
            </blockquote>
          );
        }

        return (
          <p key={`${block}-${blockIndex}`} className={compact ? undefined : "max-w-none"}>
            {renderInlineText(lines.join(" "))}
          </p>
        );
      })}
    </>
  );
}

function renderInlineText(text: string): ReactNode[] {
  return text.split(/(`[^`]+`)/g).map((part, index) => {
    if (part.startsWith("`") && part.endsWith("`")) {
      return (
        <code
          key={`${part}-${index}`}
          className="rounded-md bg-black/[0.06] px-1.5 py-0.5 font-mono text-[0.92em] text-black/74 dark:bg-white/10 dark:text-white/78"
        >
          {part.slice(1, -1)}
        </code>
      );
    }

    return <span key={`${part}-${index}`}>{part}</span>;
  });
}
