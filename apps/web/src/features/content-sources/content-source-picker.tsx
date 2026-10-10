"use client";

import { ArrowUpRight, Search } from "lucide-react";
import { useId, useMemo, useState } from "react";

import type { SourceReference } from "@plot/api-client";
import { workspaceSearchClass, workspaceSearchInputClass } from "@/components/layout/workspace-page";
import { isSafeHttpUrl } from "@/lib/safe-url";

/** The API accepts at most this many source items on one request. */
export const MAX_SELECTED_SOURCES = 20;

const COMMIT_KIND = "commit";
const PULL_REQUEST_KIND = "pull_request";

const KIND_LABELS: Record<string, string> = {
  [PULL_REQUEST_KIND]: "Pull request",
  [COMMIT_KIND]: "Commit",
};

const dateFormatter = new Intl.DateTimeFormat("en", { dateStyle: "medium" });

function formatSourceDate(value: string | null): string {
  if (!value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : dateFormatter.format(date);
}

function byNewestFirst(a: SourceReference, b: SourceReference): number {
  const left = a.sourceCreatedAt ? Date.parse(a.sourceCreatedAt) : Number.NaN;
  const right = b.sourceCreatedAt ? Date.parse(b.sourceCreatedAt) : Number.NaN;
  if (Number.isNaN(left) && Number.isNaN(right)) return 0;
  if (Number.isNaN(left)) return 1;
  if (Number.isNaN(right)) return -1;
  return right - left;
}

/** Groups sources by repository, newest change first within each one. */
export function groupSourcesByRepository(sources: SourceReference[]): { scopeId: string; label: string; items: SourceReference[] }[] {
  const groups = new Map<string, { scopeId: string; label: string; items: SourceReference[] }>();
  for (const source of sources) {
    const group = groups.get(source.sourceScopeId) ?? { scopeId: source.sourceScopeId, label: source.repositoryLabel, items: [] };
    group.items.push(source);
    groups.set(source.sourceScopeId, group);
  }
  return [...groups.values()]
    .map((group) => ({ ...group, items: [...group.items].sort(byNewestFirst) }))
    .sort((a, b) => a.label.localeCompare(b.label));
}

/**
 * Lists the pull requests Plot has imported, grouped by repository, and lets
 * the reader tick the ones a new draft should be written from. Commits are
 * left out until the reader asks for them, because a pull request usually
 * already describes the change its commits make.
 */
export function ContentSourcePicker({
  sources,
  selectedIds,
  onSelectedIdsChange,
  disabled = false,
}: {
  sources: SourceReference[];
  selectedIds: string[];
  onSelectedIdsChange: (ids: string[]) => void;
  disabled?: boolean;
}) {
  const [query, setQuery] = useState("");
  const [includeCommits, setIncludeCommits] = useState(false);
  const searchId = useId();
  const limitNoteId = useId();
  const normalizedQuery = query.trim().toLowerCase();
  // The commit switch only means something when there is a mix: with no pull
  // requests the commits are all there is, and with no commits there is nothing to add.
  const hasPullRequests = sources.some((source) => source.sourceKind === PULL_REQUEST_KIND);
  const hasCommits = sources.some((source) => source.sourceKind === COMMIT_KIND);
  const canHideCommits = hasPullRequests && hasCommits;
  const showCommits = includeCommits || !canHideCommits;
  const groups = useMemo(() => groupSourcesByRepository(
    sources.filter((source) => {
      if (!showCommits && source.sourceKind === COMMIT_KIND) return false;
      return !normalizedQuery || `${source.sourceLabel} ${source.repositoryLabel}`.toLowerCase().includes(normalizedQuery);
    }),
  ), [normalizedQuery, showCommits, sources]);
  const selected = new Set(selectedIds);
  const limitReached = selectedIds.length >= MAX_SELECTED_SOURCES;

  function toggle(id: string) {
    if (selected.has(id)) {
      onSelectedIdsChange(selectedIds.filter((selectedId) => selectedId !== id));
    } else if (!limitReached) {
      onSelectedIdsChange([...selectedIds, id]);
    }
  }

  return (
    <div>
      <div className="px-5 pt-3">
        <label htmlFor={searchId} className={`${workspaceSearchClass} mt-0!`}>
          <Search className="size-4 shrink-0" aria-hidden="true" />
          <span className="sr-only">Search changes</span>
          <input
            id={searchId}
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={showCommits ? "Search pull requests and commits" : "Search pull requests"}
            className={workspaceSearchInputClass}
          />
        </label>

        <div className="mt-2 flex min-h-[34px] flex-wrap items-center justify-between gap-x-3 text-[12px] text-black/50 dark:text-white/52">
          <span id={limitNoteId} aria-live="polite">
            {selectedIds.length} of {MAX_SELECTED_SOURCES} selected
            {limitReached ? ". Remove one to choose another." : ""}
          </span>
          <span className="flex items-center gap-1">
            {selectedIds.length > 0 ? (
              <button type="button" className="glass-button" disabled={disabled} onClick={() => onSelectedIdsChange([])}>
                Clear selection
              </button>
            ) : null}
            {canHideCommits ? (
              <label className="flex min-h-[34px] cursor-pointer items-center gap-2 text-black/70 dark:text-white/72">
                <input
                  type="checkbox"
                  checked={includeCommits}
                  onChange={(event) => setIncludeCommits(event.target.checked)}
                  className="size-4 accent-black dark:accent-white"
                />
                Include commits
              </label>
            ) : null}
          </span>
        </div>
      </div>

      <div className="mt-2 max-h-[320px] overflow-y-auto border-t border-black/[0.07] dark:border-white/[0.08]">
        {groups.length === 0 ? (
          <p className="px-6 py-8 text-center text-[13px] text-black/50 dark:text-white/52">
            {normalizedQuery ? `No changes match “${query.trim()}”.` : "No changes to show."}
          </p>
        ) : groups.map((group) => (
          <fieldset key={group.scopeId} className="min-w-0 border-b border-black/[0.07] last:border-b-0 dark:border-white/[0.08]">
            <legend className="sr-only">{group.label}</legend>
            <div aria-hidden="true" className="sticky top-0 z-10 bg-shell-workspace px-5 py-2 text-[12px] font-medium text-black/55 dark:text-white/58">
              {group.label}
            </div>
            <ul>
              {group.items.map((source) => {
                const isSelected = selected.has(source.id);
                const blocked = disabled || (limitReached && !isSelected);
                const inputId = `content-source-${source.id}`;
                const date = formatSourceDate(source.sourceCreatedAt);
                return (
                  <li key={source.id} className="flex items-start gap-3 px-5 py-2.5 hover:bg-black/[0.025] dark:hover:bg-white/[0.04]">
                    <input
                      id={inputId}
                      type="checkbox"
                      checked={isSelected}
                      disabled={blocked}
                      aria-describedby={blocked && !disabled ? limitNoteId : undefined}
                      onChange={() => toggle(source.id)}
                      className="mt-1 size-4 shrink-0 accent-black dark:accent-white"
                    />
                    <label htmlFor={inputId} className={`min-w-0 flex-1 ${blocked ? "cursor-not-allowed opacity-55" : "cursor-pointer"}`}>
                      <span className="block truncate text-[13px] font-medium text-black/85 dark:text-white/88">{source.sourceLabel}</span>
                      <span className="mt-0.5 block text-[12px] text-black/45 dark:text-white/48">
                        {KIND_LABELS[source.sourceKind] ?? "Change"}{date ? ` · ${date}` : ""}
                      </span>
                    </label>
                    {isSafeHttpUrl(source.originalUrl) ? (
                      <a
                        href={source.originalUrl ?? undefined}
                        target="_blank"
                        rel="noreferrer noopener"
                        aria-label={`Open ${source.sourceLabel} on GitHub`}
                        className="glass-button glass-icon inline-flex size-7 shrink-0 items-center justify-center"
                      >
                        <ArrowUpRight className="size-3.5" aria-hidden="true" />
                      </a>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          </fieldset>
        ))}
      </div>
    </div>
  );
}
