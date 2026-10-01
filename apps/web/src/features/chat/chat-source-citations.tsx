"use client";

import { Sources, Source } from "@/components/ai-elements/sources";
import {InlineCitation, InlineCitationText} from "@/components/ai-elements/inline-citation";

export function ChatSourceCitations({
  sources,
  totalCount = sources.length,
}: {
  sources: { id: string; title: string; url?: string | null }[];
  totalCount?: number;
}) {
  if (!sources.length) return null;

  const remainingCount = totalCount - sources.length;

  return (
    <Sources className="mt-4 flex flex-wrap items-center gap-x-2 gap-y-2 border-t border-black/[0.07] pt-3 dark:border-white/[0.08]" aria-label="Connected sources">
      <span className="mr-1 text-xs font-medium text-black/55 dark:text-white/58">
        Sources <span className="font-normal text-black/35 dark:text-white/38">{totalCount}</span>
      </span>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        {sources.map((source, index) => (
          <InlineCitation key={source.id}>
            <Source title={source.title} href={source.url ?? undefined} aria-label={`Citation ${index + 1}: ${source.title}`} className="ml-0.5">
              <InlineCitationText className="min-w-0 truncate group-hover:bg-transparent">{source.title}</InlineCitationText>
            </Source>
          </InlineCitation>
        ))}
        {remainingCount > 0 ? (
          <span className="text-xs text-black/42 dark:text-white/45">{remainingCount} more</span>
        ) : null}
      </div>
    </Sources>
  );
}
