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
    <section className="min-h-0 flex-1 overflow-y-auto px-4 pb-6 pt-14 sm:px-6 lg:px-8 lg:pb-8 lg:pt-16 2xl:pr-[328px]">
      <div className="mx-auto max-w-3xl space-y-6 pb-40">
        {messages.map((message) => (
          <article key={message.id} className={message.role === "agent" ? "pl-8" : "pr-8"}>
            <div className="mb-2 flex items-center gap-2 text-xs text-black/45 dark:text-white/45">
              <span className="font-medium text-black/65 dark:text-white/65">{message.author}</span>
              <span>{message.timestamp}</span>
            </div>
            <div className="rounded-xl border border-black/10 bg-white px-4 py-3 text-sm leading-6 text-black/80 shadow-sm dark:border-white/10 dark:bg-[#232326] dark:text-white/80">
              {message.content}
            </div>
          </article>
        ))}
      </div>

      {floatingSummaryOpen && (
        <div
          className={cn(
            "fixed top-[76px] z-20 hidden 2xl:block",
            rightPanelOpen ? "right-[484px]" : "right-6",
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
