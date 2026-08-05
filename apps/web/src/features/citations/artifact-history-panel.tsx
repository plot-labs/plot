"use client";

import { History, LoaderCircle } from "lucide-react";
import { useEffect, useState } from "react";

import type { ArtifactHistoryDetail, ArtifactHistoryItem, PlotApiClient } from "@plot/api-client";

type ArtifactHistoryPanelProps = {
  variantId: string;
  client: PlotApiClient;
  refreshKey?: string | number;
  selectedPosition?: number | null;
  onSelect: (detail: ArtifactHistoryDetail, position: number) => void;
};

export function ArtifactHistoryPanel({ variantId, client, refreshKey = "initial", selectedPosition = null, onSelect }: ArtifactHistoryPanelProps) {
  const [items, setItems] = useState<ArtifactHistoryItem[]>([]);
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const [loadingPosition, setLoadingPosition] = useState<number | null>(null);
  const [error, setError] = useState("");
  const requestKey = `${variantId}:${client}:${refreshKey}`;
  const loading = loadedKey !== requestKey;

  useEffect(() => {
    const controller = new AbortController();
    void client.listArtifactHistory(variantId, { signal: controller.signal })
      .then((next) => {
        if (controller.signal.aborted) return;
        setItems(next);
        setError("");
        setLoadedKey(requestKey);
      })
      .catch((reason: unknown) => {
        if (controller.signal.aborted) return;
        setError(reason instanceof Error ? reason.message : "History could not be loaded.");
        setLoadedKey(requestKey);
      });
    return () => controller.abort();
  }, [client, requestKey, variantId]);

  async function select(position: number) {
    if (loadingPosition !== null) return;
    setLoadingPosition(position);
    setError("");
    const controller = new AbortController();
    try {
      const detail = await client.getArtifactHistoryAt(variantId, position, { signal: controller.signal });
      onSelect(detail, position);
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "This historical snapshot could not be loaded.");
    } finally {
      setLoadingPosition(null);
    }
  }

  return (
    <section aria-label="Artifact history" className="min-w-0">
      <div className="flex items-center gap-2 text-sm font-semibold text-black/80 dark:text-white/88">
        <History aria-hidden="true" className="size-4" />
        History
      </div>
      <p className="mt-1 text-xs leading-5 text-black/48 dark:text-white/52">Content snapshots only. Delivery and source views are not history.</p>
      {loading ? <p className="mt-4 text-sm text-black/48 dark:text-white/48">Loading history…</p> : null}
      {!loading && !items.length ? <p className="mt-4 rounded-lg border border-dashed border-black/10 px-3 py-4 text-sm text-black/48 dark:border-white/10 dark:text-white/48">No saved snapshots yet.</p> : null}
      {error ? <p role="alert" className="mt-3 text-sm text-rose-700 dark:text-rose-300">{error}</p> : null}
      <ol className="mt-4 space-y-2" aria-label="Artifact content snapshots">
        {items.map((item) => (
          <li key={`${item.position}-${item.createdAt}`}>
            <button
              type="button"
              aria-current={selectedPosition === item.position ? "true" : undefined}
              onClick={() => void select(item.position)}
              className={`w-full rounded-lg border px-3 py-2.5 text-left transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 ${selectedPosition === item.position ? "border-black/25 bg-black/[0.04] dark:border-white/25 dark:bg-white/10" : "border-black/[0.08] hover:bg-black/[0.025] dark:border-white/10 dark:hover:bg-white/[0.06]"}`}
            >
              <span className="flex items-center gap-2 text-sm font-medium text-black/76 dark:text-white/80">
                {loadingPosition === item.position ? <LoaderCircle aria-hidden="true" className="size-3.5 animate-spin" /> : null}
                {item.cause}
              </span>
              <time aria-label={formatSnapshotTime(item.createdAt)} dateTime={item.createdAt} className="mt-1 block text-xs text-black/48 dark:text-white/48">
                {formatSnapshotTime(item.createdAt)}
              </time>
            </button>
          </li>
        ))}
      </ol>
    </section>
  );
}

function formatSnapshotTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}
