"use client";

import {
  ChevronDown,
  ChevronRight,
  Copy,
  FileText,
  GitPullRequest,
  Pencil,
  RotateCcw,
  TerminalSquare,
  ThumbsDown,
  ThumbsUp,
} from "lucide-react";
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
        {messages.map((message, index) => (
          <SessionThreadMessage
            key={message.id}
            message={message}
            messageIndex={index}
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
  messageIndex,
}: {
  message: SessionMessage;
  messageIndex: number;
}) {
  const [workOpen, setWorkOpen] = useState(false);
  const workIndex = Math.max(0, messageIndex - 1);

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
        <AgentResultCards workIndex={workIndex} />
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

function AgentResultCards({ workIndex }: { workIndex: number }) {
  const result = getResultSummary(workIndex);

  return (
    <div className="space-y-3 pt-2">
      <button
        type="button"
        className="inline-flex items-center gap-2 text-left text-[15px] font-medium leading-6 text-[#1f7ae0] transition hover:text-[#155fba] dark:text-[#7bb5ff] dark:hover:text-[#a7ceff]"
      >
        <FileText className="size-4 shrink-0" />
        <span className="break-all">{result.fileName}</span>
      </button>

      <div className="rounded-[16px] border border-black/[0.10] bg-white px-5 py-4 dark:border-white/10 dark:bg-[#1d1d20]">
        <div className="flex items-center gap-4">
          <div className="flex size-12 shrink-0 items-center justify-center rounded-[14px] bg-black/[0.04] text-black/50 dark:bg-white/[0.08] dark:text-white/55">
            <FileText className="size-5" />
          </div>
          <div className="min-w-0 flex-1">
            <div className="truncate text-[15px] font-semibold text-black/84 dark:text-white/86">
              {result.fileName}
            </div>
            <div className="mt-0.5 text-sm text-black/48 dark:text-white/48">{result.fileKind}</div>
          </div>
          <button
            type="button"
            className="inline-flex shrink-0 items-center gap-1.5 rounded-[14px] border border-black/10 px-3.5 py-2 text-sm font-medium text-black/78 transition hover:bg-black/5 dark:border-white/10 dark:text-white/78 dark:hover:bg-white/10"
          >
            Open next
            <ChevronDown className="size-4" />
          </button>
        </div>
      </div>

      <div className="rounded-[16px] border border-black/[0.10] bg-white px-5 py-4 dark:border-white/10 dark:bg-[#1d1d20]">
        <div className="flex items-center gap-4">
          <div className="flex size-12 shrink-0 items-center justify-center rounded-[14px] bg-black/[0.04] text-black/50 dark:bg-white/[0.08] dark:text-white/55">
            <FileText className="size-5" />
          </div>
          <div className="min-w-0 flex-1">
            <div className="truncate text-[15px] font-semibold text-black/84 dark:text-white/86">
              {result.changeTitle}
            </div>
            <div className="mt-0.5 text-sm">
              <span className="font-medium text-[#089a3a]">+{result.added}</span>
              <span className="text-black/35 dark:text-white/35"> </span>
              <span className="font-medium text-[#c92f2f]">-{result.removed}</span>
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-3">
            <button
              type="button"
              className="inline-flex items-center gap-1.5 rounded-xl px-2 py-1.5 text-sm font-medium text-black/70 transition hover:bg-black/5 dark:text-white/72 dark:hover:bg-white/10"
            >
              Undo
              <RotateCcw className="size-4" />
            </button>
            <button
              type="button"
              className="rounded-[14px] border border-black/10 px-3.5 py-2 text-sm font-medium text-black/78 transition hover:bg-black/5 dark:border-white/10 dark:text-white/78 dark:hover:bg-white/10"
            >
              Review
            </button>
          </div>
        </div>
      </div>
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

function getResultSummary(messageIndex: number) {
  const results = [
    {
      fileName: "Changelog.md",
      fileKind: "Document · MD",
      changeTitle: "Changelog.md updated",
      added: 42,
      removed: 0,
    },
    {
      fileName: "Customer-update.md",
      fileKind: "Document · MD",
      changeTitle: "Customer-update.md updated",
      added: 28,
      removed: 0,
    },
  ];

  return results[messageIndex % results.length];
}
