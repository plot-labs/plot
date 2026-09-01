import Link from "next/link";

import type { PublicChangelogEntrySummary } from "@plot/api-client";
import { publicChangelogEntryPath } from "@/lib/public-changelog-url";

type PublicChangelogListProps = {
  workspaceSlug: string;
  entries: PublicChangelogEntrySummary[];
};

const publishedAtFormatter = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
});

function formatPublishedAt(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : publishedAtFormatter.format(date);
}

export function PublicChangelogList({ workspaceSlug, entries }: PublicChangelogListProps) {
  if (entries.length === 0) {
    return (
      <section aria-labelledby="changelog-empty-heading">
        <header className="border-b border-black/10 pb-10">
          <p className="font-mono text-xs uppercase tracking-[0.18em] text-black/45">Changelog</p>
          <h1 id="changelog-empty-heading" className="mt-4 font-display text-5xl tracking-[-0.03em] sm:text-6xl">
            No changelog entries yet
          </h1>
          <p className="mt-6 max-w-2xl text-lg leading-8 text-black/65">
            Published releases will appear here once the team shares them.
          </p>
        </header>
      </section>
    );
  }

  return (
    <section aria-labelledby="changelog-list-heading">
      <header className="border-b border-black/10 pb-10">
        <p className="font-mono text-xs uppercase tracking-[0.18em] text-black/45">Changelog</p>
        <h1 id="changelog-list-heading" className="mt-4 font-display text-5xl tracking-[-0.03em] sm:text-6xl">
          Updates
        </h1>
      </header>

      <ol className="divide-y divide-black/10">
        {entries.map((entry) => (
          <li key={entry.id} className="py-8">
            <article>
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-black/45">
                <time dateTime={entry.publishedAt}>{formatPublishedAt(entry.publishedAt)}</time>
                {entry.tagName ? (
                  <span className="rounded-full border border-black/10 px-2 py-0.5 font-mono text-xs uppercase tracking-[0.12em] text-black/55">
                    {entry.tagName}
                  </span>
                ) : null}
              </div>
              <h2 className="mt-3 font-display text-3xl tracking-[-0.02em]">
                <Link
                  href={publicChangelogEntryPath(workspaceSlug, entry.entrySlug)}
                  className="transition-colors hover:text-black/70"
                >
                  {entry.title}
                </Link>
              </h2>
            </article>
          </li>
        ))}
      </ol>
    </section>
  );
}
