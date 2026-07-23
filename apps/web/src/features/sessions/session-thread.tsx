"use client";

import {
  Copy,
  Pencil,
} from "lucide-react";
import type { ReactNode } from "react";

export type SessionMessage = {
  id: string;
  role: "user";
  timestamp: string;
  content: string;
};

type SessionThreadProps = {
  messages: SessionMessage[];
  generationPanel?: ReactNode;
};

export function SessionThread({
  messages,
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

    </section>
  );
}

function SessionThreadMessage({
  message,
}: {
  message: SessionMessage;
}) {
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
