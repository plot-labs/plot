"use client";

import { Citation } from "@astryxdesign/core/Citation";

import type { SourceReference } from "@plot/api-client";

export function ChatSourceCitations({ references }: { references: SourceReference[] }) {
  if (!references.length) return null;

  const visibleReferences = references.slice(0, 2);
  const remainingCount = references.length - visibleReferences.length;

  return (
    <div className="mt-4 flex flex-wrap items-center gap-x-2 gap-y-2 border-t border-black/[0.07] pt-3 dark:border-white/[0.08]" aria-label="Connected sources">
      <span className="mr-1 text-xs font-medium text-black/55 dark:text-white/58">
        Sources <span className="font-normal text-black/35 dark:text-white/38">{references.length}</span>
      </span>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        {visibleReferences.map((reference, index) => (
          <Citation
            key={reference.id}
            source={{
              title: `${reference.repositoryLabel} / ${reference.sourceLabel}`,
              url: reference.originalUrl ?? undefined,
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
