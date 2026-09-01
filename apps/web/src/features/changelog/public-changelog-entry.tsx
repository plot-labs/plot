import Link from "next/link";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

import type { PublicChangelogEntry } from "@plot/api-client";
import { publicChangelogPath } from "@/lib/public-changelog-url";
import { isSafeHttpUrl } from "@/lib/safe-url";

type PublicChangelogEntryViewProps = {
  workspaceSlug: string;
  entry: PublicChangelogEntry;
};

const publishedAtFormatter = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
});

function formatPublishedAt(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : publishedAtFormatter.format(date);
}

export function PublicChangelogEntryView({ workspaceSlug, entry }: PublicChangelogEntryViewProps) {
  return (
    <article aria-labelledby="changelog-entry-heading">
      <Link
        href={publicChangelogPath(workspaceSlug)}
        className="text-sm text-black/55 transition-colors hover:text-black"
      >
        ← All updates
      </Link>

      <header className="mt-8 border-b border-black/10 pb-10">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-black/45">
          <time dateTime={entry.publishedAt}>{formatPublishedAt(entry.publishedAt)}</time>
          {entry.tagName ? (
            <span className="rounded-full border border-black/10 px-2 py-0.5 font-mono text-xs uppercase tracking-[0.12em] text-black/55">
              {entry.tagName}
            </span>
          ) : null}
        </div>
        <h1 id="changelog-entry-heading" className="mt-4 font-display text-5xl tracking-[-0.03em] sm:text-6xl">
          {entry.title}
        </h1>
      </header>

      <div className="prose-changelog py-10 text-[17px] leading-8 text-black/75">
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          components={{
            a: ({ href, children }) => {
              if (!href || !isSafeHttpUrl(href)) {
                return <span>{children}</span>;
              }
              return (
                <a href={href} rel="noopener noreferrer" target="_blank" className="underline underline-offset-4">
                  {children}
                </a>
              );
            },
            h1: ({ children }) => <h2 className="mt-10 font-display text-3xl tracking-[-0.02em]">{children}</h2>,
            h2: ({ children }) => <h3 className="mt-8 font-display text-2xl tracking-[-0.02em]">{children}</h3>,
            h3: ({ children }) => <h4 className="mt-6 text-xl font-semibold">{children}</h4>,
            p: ({ children }) => <p className="mt-4 first:mt-0">{children}</p>,
            ul: ({ children }) => <ul className="mt-4 list-disc space-y-2 pl-5">{children}</ul>,
            ol: ({ children }) => <ol className="mt-4 list-decimal space-y-2 pl-5">{children}</ol>,
            li: ({ children }) => <li>{children}</li>,
            code: ({ children }) => (
              <code className="rounded bg-black/[0.05] px-1.5 py-0.5 font-mono text-[0.92em]">{children}</code>
            ),
            pre: ({ children }) => (
              <pre className="mt-4 overflow-x-auto rounded-lg border border-black/10 bg-white/70 p-4 font-mono text-sm leading-6">
                {children}
              </pre>
            ),
            blockquote: ({ children }) => (
              <blockquote className="mt-4 border-l-2 border-black/15 pl-4 text-black/60">{children}</blockquote>
            ),
            img: ({ src, alt }) => {
              if (typeof src !== "string" || !isSafeHttpUrl(src)) return null;
              return (
                // eslint-disable-next-line @next/next/no-img-element -- public markdown URLs are validated at render time
                <img src={src} alt={alt ?? ""} className="mt-6 max-w-full rounded-lg border border-black/10" />
              );
            },
          }}
        >
          {entry.bodyMarkdown}
        </ReactMarkdown>
      </div>
    </article>
  );
}
