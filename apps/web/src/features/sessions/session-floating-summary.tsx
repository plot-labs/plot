import { FileText, GitPullRequest } from "lucide-react";

import type { DraftDocument, ReferenceDocument } from "@/lib/api-client";

type SessionFloatingSummaryProps = {
  drafts: DraftDocument[];
  references: ReferenceDocument[];
  onSelectDocument: (documentId: string) => void;
};

export function SessionFloatingSummary({
  drafts,
  references,
  onSelectDocument,
}: SessionFloatingSummaryProps) {
  return (
    <div className="w-[280px] rounded-[18px] border border-black/10 bg-white/95 p-3 text-sm shadow-[0_16px_36px_rgba(0,0,0,0.12)] backdrop-blur dark:border-white/10 dark:bg-[#232326]/95 dark:shadow-black/35">
      <div className="text-xs font-semibold uppercase text-black/45 dark:text-white/45">Drafts</div>
      <div className="mt-2 space-y-1">
        {drafts.map((draft) => (
          <button
            key={draft.id}
            type="button"
            onClick={() => onSelectDocument(draft.id)}
            className="flex w-full items-center gap-2 rounded-xl px-2 py-1.5 text-left text-black/75 transition hover:bg-black/5 dark:text-white/75 dark:hover:bg-white/10"
          >
            <FileText className="size-3.5" />
            <span className="truncate">{draft.title}</span>
          </button>
        ))}
      </div>

      <div className="mt-4 border-t border-black/10 pt-3 dark:border-white/10">
        <div className="text-xs font-semibold uppercase text-black/45 dark:text-white/45">References</div>
        <div className="mt-2 space-y-1">
          {references.map((reference) => (
            <button
              key={reference.id}
              type="button"
              onClick={() => onSelectDocument(reference.id)}
              className="flex w-full items-center gap-2 rounded-xl px-2 py-1.5 text-left text-black/75 transition hover:bg-black/5 dark:text-white/75 dark:hover:bg-white/10"
            >
              <GitPullRequest className="size-3.5" />
              <span className="truncate">{reference.label}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
