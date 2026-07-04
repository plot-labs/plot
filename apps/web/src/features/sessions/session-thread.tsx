import type { DraftDocument, ReferenceDocument, SessionMessage } from "@/lib/dev-context";
import { SessionFloatingSummary } from "@/features/sessions/session-floating-summary";

type SessionThreadProps = {
  messages: SessionMessage[];
  drafts: DraftDocument[];
  references: ReferenceDocument[];
  onSelectDocument: (documentId: string) => void;
};

export function SessionThread({
  messages,
  drafts,
  references,
  onSelectDocument,
}: SessionThreadProps) {
  return (
    <section className="min-h-0 flex-1 overflow-y-auto px-8 py-8">
      <div className="mx-auto grid max-w-[calc(48rem+284px)] grid-cols-1 gap-6 2xl:grid-cols-[minmax(0,48rem)_260px]">
        <div className="min-w-0 space-y-6 pb-40">
          {messages.map((message) => (
            <article key={message.id} className={message.role === "agent" ? "pl-8" : "pr-8"}>
              <div className="mb-2 flex items-center gap-2 text-xs text-black/45 dark:text-white/45">
                <span className="font-medium text-black/65 dark:text-white/65">{message.author}</span>
                <span>{message.timestamp}</span>
              </div>
              <div className="rounded-xl border border-black/10 bg-white px-4 py-3 text-sm leading-6 text-black/80 shadow-sm dark:border-white/10 dark:bg-[#222] dark:text-white/80">
                {message.content}
              </div>
            </article>
          ))}
        </div>

        <div className="order-first justify-self-end 2xl:order-none 2xl:sticky 2xl:top-5 2xl:self-start 2xl:justify-self-auto">
          <SessionFloatingSummary
            drafts={drafts}
            references={references}
            onSelectDocument={onSelectDocument}
          />
        </div>
      </div>
    </section>
  );
}
