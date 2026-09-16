"use client";

import { Citation } from "@astryxdesign/core/Citation";

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
    <div className="mt-4 flex flex-wrap items-center gap-x-2 gap-y-2 border-t border-black/[0.07] pt-3 dark:border-white/[0.08]" aria-label="Connected sources">
      <span className="mr-1 text-xs font-medium text-black/55 dark:text-white/58">
        Sources <span className="font-normal text-black/35 dark:text-white/38">{totalCount}</span>
      </span>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        {sources.map((source, index) => (
          <Citation
            key={source.id}
            source={{
              title: source.title,
              url: source.url ?? undefined,
            }}
            number={index + 1}
            variant="label"
          />
        ))}
        {remainingCount > 0 ? (
          <span className="text-xs text-black/42 dark:text-white/45">{remainingCount} more</span>
        ) : null}
      </div>
    </div>
  );
}
