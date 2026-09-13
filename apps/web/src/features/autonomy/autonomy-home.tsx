"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import type { ActivityItem, ActivityStatus } from "@plot/api-client";
import { RefreshCw } from "lucide-react";
import {
  WorkspaceHeader,
  workspaceIconButtonClass,
  workspaceListClass,
  workspaceNoticeClass,
  workspacePageClass,
  workspacePrimaryButtonClass,
  workspaceSectionClass,
  workspaceTextButtonClass,
} from "@/components/layout/workspace-page";
import { getSelectedWorkspaceId, plotApiClient } from "@/lib/api-client";

const buttonClass = workspaceTextButtonClass;

export function AutonomyHomeWorkspace({ view = "overview" }: { view?: "overview" | "activity" }) {
  const [items, setItems] = useState<ActivityItem[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [highWaterMark, setHighWaterMark] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [paginationError, setPaginationError] = useState<string | null>(null);
  const [filter, setFilter] = useState<"ALL" | "ATTENTION" | "DECIDED">("ALL");
  const [revision, setRevision] = useState(0);
  const generation = useRef(0);

  useEffect(() => {
    const change = () => {
      generation.current += 1;
      setItems([]);
      setNextCursor(null);
      setHasMore(false);
      setHighWaterMark(null);
      setError(null);
      setPaginationError(null);
      setLoading(true);
      setRevision((value) => value + 1);
    };
    window.addEventListener("plot:workspace-changed", change);
    return () => {
      generation.current += 1;
      window.removeEventListener("plot:workspace-changed", change);
    };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const current = ++generation.current;
    const workspace = getSelectedWorkspaceId();
    const valid = () => !controller.signal.aborted && generation.current === current && workspace === getSelectedWorkspaceId();

    async function load() {
      try {
        if (!workspace) throw new Error("Select a workspace to see its updates.");
        const page = await plotApiClient.getActivity({ limit: 20 }, { signal: controller.signal });
        if (valid()) {
          setItems(page.items);
          setNextCursor(page.nextCursor);
          setHasMore(page.hasMore);
          setHighWaterMark(page.highWaterMark);
        }
      } catch (cause) {
        if (valid()) setError(cause instanceof Error ? cause.message : "Could not load updates.");
      } finally {
        if (valid()) setLoading(false);
      }
    }

    void load();
    return () => controller.abort();
  }, [revision]);

  function refresh() {
    setError(null);
    setPaginationError(null);
    setLoading(true);
    setRevision((value) => value + 1);
  }

  async function loadMore() {
    if (loadingMore || !nextCursor || !hasMore) return;
    setLoadingMore(true);
    setPaginationError(null);
    try {
      const page = await plotApiClient.getActivity({
        cursor: nextCursor,
        highWaterMark: highWaterMark ?? undefined,
        limit: 20,
      });
      setItems((prev) => [...prev, ...page.items]);
      setNextCursor(page.nextCursor);
      setHasMore(page.hasMore);
    } catch (cause) {
      setPaginationError(cause instanceof Error ? cause.message : "Could not load more activity.");
    } finally {
      setLoadingMore(false);
    }
  }

  const attentionItems = items.filter((item) =>
    item.status === "READY_FOR_REVIEW" || item.status === "ACTION_REQUIRED" || item.status === "IN_PROGRESS" || item.status === "FOUND"
  );
  const decidedItems = items.filter((item) => item.status === "NO_UPDATE_NEEDED" || item.status === "EXCLUDED");

  const filteredItems = items.filter((item) => {
    if (filter === "ATTENTION") {
      return item.status === "READY_FOR_REVIEW" || item.status === "ACTION_REQUIRED" || item.status === "IN_PROGRESS" || item.status === "FOUND";
    }
    if (filter === "DECIDED") {
      return item.status === "NO_UPDATE_NEEDED" || item.status === "EXCLUDED";
    }
    return true;
  });

  const displayItems = view === "overview" ? items.slice(0, 5) : filteredItems;

  function rows(activityItems: ActivityItem[]) {
    return (
      <ul className={`mt-3 ${workspaceListClass}`}>
        {activityItems.map((item) => (
          <li key={item.id} className="px-6 py-4 transition hover:bg-white/70 dark:hover:bg-white/[0.04]">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0 flex-1">
                <p className="text-xs text-black/50 dark:text-white/50">{statusLabel(item.status)}</p>
                <h3 className="mt-1 break-words text-[15px] font-semibold tracking-[-0.01em] text-black/82 dark:text-white/86">{item.title}</h3>
                <p className="mt-1 text-sm leading-6 text-black/52 dark:text-white/52">{item.reason}</p>
                {item.semanticTime && (
                  <p className="mt-1 text-xs text-black/40 dark:text-white/40">
                    {new Date(item.semanticTime).toLocaleDateString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })}
                  </p>
                )}
              </div>
              <div className="flex flex-wrap items-center gap-2">
                {item.chatId ? (
                  <Link
                    className={buttonClass}
                    href={`/chat?chat=${encodeURIComponent(item.chatId)}${item.responseVersionId ? `&version=${encodeURIComponent(item.responseVersionId)}` : item.agentRunId ? `&agent=${encodeURIComponent(item.agentRunId)}` : ""}${item.artifactId ? `&artifact=${encodeURIComponent(item.artifactId)}` : ""}`}
                  >
                    {item.status === "READY_FOR_REVIEW" || item.status === "ACTION_REQUIRED" ? "Review and discuss" : "View in chat"}
                  </Link>
                ) : item.artifactId ? (
                  <Link className={buttonClass} href={`/contents?artifact=${encodeURIComponent(item.artifactId)}`}>
                    Open artifact
                  </Link>
                ) : null}
              </div>
            </div>
          </li>
        ))}
      </ul>
    );
  }

  return (
    <div className={workspacePageClass}>
      <section className={workspaceSectionClass} aria-labelledby="home-heading">
        <WorkspaceHeader
          id="home-heading"
          title={view === "overview" ? "Home" : "Activity"}
          description={view === "overview" ? "What Plot is preparing and what needs your attention." : "Connected changes and the reasons to prepare, hold, or exclude an update."}
          actions={
            <button
              type="button"
              className={workspaceIconButtonClass}
              disabled={loading || loadingMore}
              onClick={refresh}
              aria-label="Refresh updates"
              title="Refresh updates"
            >
              <RefreshCw className={`size-3.5 ${loading ? "animate-spin" : ""}`} />
            </button>
          }
        >
          {view === "overview" && (
            <nav aria-label="Home actions" className="mt-5 flex flex-wrap items-center gap-1.5">
              <Link className={workspacePrimaryButtonClass} href="/chat">New chat</Link>
              <Link className={workspaceTextButtonClass} href="/contents">Review updates</Link>
            </nav>
          )}
        </WorkspaceHeader>

        {error && items.length === 0 && (
          <p role="alert" className={`mx-6 mt-4 ${workspaceNoticeClass}`}>{error}</p>
        )}
        {loading && items.length === 0 && (
          <p role="status" className={`mx-6 mt-4 py-8 text-center ${workspaceNoticeClass}`}>Loading activity…</p>
        )}

        {!loading && (
          <>
            <div className="px-6 pt-4">
              {view === "overview" && (
                <p className="text-[11px] leading-4 text-black/40 dark:text-white/42">
                  {attentionItems.length} active or under review · {decidedItems.length} decided
                </p>
              )}
              <p className="mt-1.5 text-[11px] leading-4 text-black/40 dark:text-white/42">
                Plot assesses connected changes before drafting. Publishing still requires your review.
              </p>
            </div>

            {view === "activity" && items.length > 0 && (
              <div className="mx-6 mt-4 flex items-center gap-2" role="tablist" aria-label="Activity filter">
                <button
                  type="button"
                  role="tab"
                  aria-selected={filter === "ALL"}
                  onClick={() => setFilter("ALL")}
                  className={`rounded-full px-3 py-1 text-xs font-medium transition ${filter === "ALL" ? "bg-black text-white dark:bg-white dark:text-black" : "bg-black/5 text-black/60 hover:bg-black/10 dark:bg-white/10 dark:text-white/60"}`}
                >
                  All ({items.length})
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={filter === "ATTENTION"}
                  onClick={() => setFilter("ATTENTION")}
                  className={`rounded-full px-3 py-1 text-xs font-medium transition ${filter === "ATTENTION" ? "bg-black text-white dark:bg-white dark:text-black" : "bg-black/5 text-black/60 hover:bg-black/10 dark:bg-white/10 dark:text-white/60"}`}
                >
                  Active ({attentionItems.length})
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={filter === "DECIDED"}
                  onClick={() => setFilter("DECIDED")}
                  className={`rounded-full px-3 py-1 text-xs font-medium transition ${filter === "DECIDED" ? "bg-black text-white dark:bg-white dark:text-black" : "bg-black/5 text-black/60 hover:bg-black/10 dark:bg-white/10 dark:text-white/60"}`}
                >
                  Decided ({decidedItems.length})
                </button>
              </div>
            )}

            {items.length === 0 && (
              <div className={`mx-6 mt-4 px-4 py-8 text-center ${workspaceNoticeClass}`}>
                <h2 className="text-[13px] font-medium text-black/65 dark:text-white/65">No activity yet</h2>
                <p className="mt-2 text-[12px] leading-5 text-black/48 dark:text-white/50">
                  New connected activity will appear here as Plot evaluates changes.
                </p>
              </div>
            )}

            {items.length > 0 && displayItems.length === 0 && (
              <div className={`mx-6 mt-4 px-4 py-8 text-center ${workspaceNoticeClass}`}>
                <p className="text-[12px] leading-5 text-black/48 dark:text-white/50">
                  No matching activity found for the selected filter.
                </p>
              </div>
            )}

            {displayItems.length > 0 && (
              <section className="mt-6" aria-label="Activity items">
                {rows(displayItems)}
              </section>
            )}

            {view === "activity" && hasMore && (
              <div className="mt-4 px-6 text-center">
                <button
                  type="button"
                  className={buttonClass}
                  disabled={loadingMore}
                  onClick={() => void loadMore()}
                >
                  {loadingMore ? "Loading more activity…" : "Load more activity"}
                </button>
                {paginationError && (
                  <p role="alert" className="mt-2 text-xs text-rose-600 dark:text-rose-400">
                    {paginationError}
                  </p>
                )}
              </div>
            )}

            {view === "activity" && !hasMore && items.length > 0 && (
              <p className="mt-6 text-center text-[11px] text-black/35 dark:text-white/35">
                End of activity feed
              </p>
            )}
          </>
        )}
      </section>
    </div>
  );
}

function statusLabel(status: ActivityStatus): string {
  switch (status) {
    case "FOUND": return "Evaluating";
    case "IN_PROGRESS": return "Drafting in progress";
    case "READY_FOR_REVIEW": return "Ready for review";
    case "ACTION_REQUIRED": return "Action required";
    case "NO_UPDATE_NEEDED": return "No customer update needed";
    case "EXCLUDED": return "Excluded";
  }
}
