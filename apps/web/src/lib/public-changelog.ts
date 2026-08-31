import {
  fetchPublicChangelog as fetchPublicChangelogFromApi,
  fetchPublicChangelogEntry as fetchPublicChangelogEntryFromApi,
  type PublicChangelog,
  type PublicChangelogEntry,
} from "@plot/api-client";

export type { PublicChangelog, PublicChangelogEntry };

function getPlotApiBaseUrl(): string {
  return (process.env.PLOT_API_BASE_URL ?? "http://127.0.0.1:8080").replace(/\/$/, "");
}

export async function fetchPublicChangelog(workspaceSlug: string): Promise<PublicChangelog> {
  return fetchPublicChangelogFromApi(workspaceSlug, { baseUrl: getPlotApiBaseUrl() });
}

export async function fetchPublicChangelogEntry(
  workspaceSlug: string,
  entrySlug: string,
): Promise<PublicChangelogEntry> {
  return fetchPublicChangelogEntryFromApi(workspaceSlug, entrySlug, { baseUrl: getPlotApiBaseUrl() });
}
