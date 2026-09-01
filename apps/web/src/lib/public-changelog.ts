import { cache } from "react";

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

export const fetchPublicChangelog = cache(async (workspaceSlug: string): Promise<PublicChangelog> => {
  return fetchPublicChangelogFromApi(workspaceSlug, { baseUrl: getPlotApiBaseUrl() });
});

export const fetchPublicChangelogEntry = cache(async (
  workspaceSlug: string,
  entrySlug: string,
): Promise<PublicChangelogEntry> => {
  return fetchPublicChangelogEntryFromApi(workspaceSlug, entrySlug, { baseUrl: getPlotApiBaseUrl() });
});
