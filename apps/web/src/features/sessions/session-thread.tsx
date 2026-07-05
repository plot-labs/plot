"use client";

import { ChevronDown, ChevronRight, FileText, GitPullRequest, TerminalSquare } from "lucide-react";
import type { ComponentType, ReactNode } from "react";
import { useState } from "react";

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
};

export function SessionThread({
  messages,
  drafts,
  references,
  floatingSummaryOpen,
  rightPanelOpen,
  onSelectDocument,
}: SessionThreadProps) {
  return (
    <section className="min-h-0 flex-1 overflow-y-auto px-4 pb-8 pt-16 sm:px-6 lg:px-8 lg:pb-10 lg:pt-20">
      <div className="mx-auto max-w-3xl space-y-10 pb-40">
        {messages.map((message, index) => (
          <SessionThreadMessage
            key={message.id}
            message={message}
            messageIndex={index}
          />
        ))}
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
  messageIndex,
}: {
  message: SessionMessage;
  messageIndex: number;
}) {
  const [workOpen, setWorkOpen] = useState(true);
  const workIndex = Math.max(0, messageIndex - 1);

  if (message.role === "user") {
    return (
      <article className="flex justify-end">
        <div className="max-w-[min(620px,86%)]">
          <MessageMeta
            author={message.author}
            timestamp={message.timestamp}
            align="right"
          />
          <div className="rounded-[18px] bg-black/[0.045] px-4 py-2.5 text-sm leading-6 text-black/80 shadow-[inset_0_0_0_1px_rgba(0,0,0,0.025)] dark:bg-white/10 dark:text-white/82">
            <MessageContent content={message.content} compact />
          </div>
        </div>
      </article>
    );
  }

  return (
    <article className="space-y-4">
      <div className="flex items-center gap-2 text-sm text-black/42 dark:text-white/42">
        <button
          type="button"
          onClick={() => setWorkOpen((open) => !open)}
          aria-expanded={workOpen}
          className="inline-flex items-center gap-1.5 rounded-xl pr-1 transition hover:text-black/65 dark:hover:text-white/68"
        >
          <span>{getWorkDurationLabel(workIndex)}</span>
          {workOpen ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />}
        </button>
        <div className="h-px flex-1 bg-black/[0.06] dark:bg-white/10" />
      </div>

      {workOpen && <WorkRunDetails workIndex={workIndex} />}

      <div className="space-y-4 text-[15px] leading-7 text-black/82 dark:text-white/82">
        <MessageContent content={message.content} />
      </div>
    </article>
  );
}

type WorkStep =
  | {
      type: "note";
      text: string;
    }
  | {
      type: "tool";
      icon: ComponentType<{ className?: string }>;
      text: string;
    };

function WorkRunDetails({ workIndex }: { workIndex: number }) {
  const steps = getWorkSteps(workIndex);

  return (
    <div className="space-y-4 text-[14px] leading-7 text-black/76 dark:text-white/76">
      {steps.map((step, stepIndex) => {
        if (step.type === "tool") {
          const Icon = step.icon;

          return (
            <div
              key={`${step.text}-${stepIndex}`}
              className="flex items-start gap-2 text-black/42 dark:text-white/42"
            >
              <Icon className="mt-1 size-3.5 shrink-0" />
              <div>{renderInlineText(step.text)}</div>
            </div>
          );
        }

        return (
          <p key={`${step.text}-${stepIndex}`}>
            {renderInlineText(step.text)}
          </p>
        );
      })}
    </div>
  );
}

function MessageMeta({
  author,
  timestamp,
  align,
}: {
  author: string;
  timestamp: string;
  align: "left" | "right";
}) {
  return (
    <div
      className={cn(
        "mb-2 flex items-center gap-2 text-xs text-black/45 dark:text-white/45",
        align === "right" && "justify-start",
      )}
    >
      <span className="font-medium text-black/62 dark:text-white/62">{author}</span>
      <span>{timestamp}</span>
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

function getWorkDurationLabel(messageIndex: number) {
  const durations = ["27s of work", "1m 12s of work", "4m 41s of work"];

  return durations[messageIndex % durations.length];
}

function getWorkSteps(messageIndex: number): WorkStep[] {
  const runs: WorkStep[][] = [
    [
      {
        type: "note",
        text:
          "Read the request, active drafts, and source list before changing the response shape. The goal is a source-backed draft, not a generic update.",
      },
      {
        type: "tool",
        icon: FileText,
        text: "Loaded `Changelog.md` and `Customer-update.md` from the current workspace.",
      },
      {
        type: "note",
        text:
          "Matched the requested July window against `PR #184`, `Release v0.4`, and `Issue #77`, then kept the customer-facing wording conservative.",
      },
      {
        type: "tool",
        icon: GitPullRequest,
        text: "Checked 3 references and kept the unresolved wording decision visible for review.",
      },
      {
        type: "note",
        text:
          "Prepared the draft summary and left the next review decision in the generated result list.",
      },
    ],
    [
      {
        type: "note",
        text:
          "Compared the requested output with the existing pack so a follow-up draft can reuse the same references.",
      },
      {
        type: "tool",
        icon: TerminalSquare,
        text: "Ran a dry review of the current session state and open draft list.",
      },
      {
        type: "note",
        text:
          "Kept the answer short because the next useful action is choosing whether the draft should become a changelog or a customer update.",
      },
    ],
  ];

  return runs[messageIndex % runs.length];
}
