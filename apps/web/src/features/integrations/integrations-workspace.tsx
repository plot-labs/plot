"use client";

import { Cancel01Icon, Search01Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";

import {
  WorkspaceHeader,
  WorkspaceEmptyState,
  workspacePageClass,
  workspaceSearchClass,
  workspaceSearchInputClass,
  workspaceSectionClass,
} from "@/components/layout/workspace-page";
import { getSelectedWorkspaceId, plotApiClient, type GitHubConnection } from "@/lib/api-client";
import { useWorkspaceChanged } from "@/lib/workspace-changed";

import { followedRepositoryCount, githubConnectionStatus } from "./github-connection-status";
import { BrandIcon, type IntegrationBrand } from "./integration-brand-icon";

const githubPagePath = "/settings/integrations/github";
const githubCallbackParams = ["githubConnection", "githubError", "githubConnected"];

const catalog: Array<{
  brand: IntegrationBrand;
  name: string;
  description: string;
  keywords: string[];
  available: boolean;
}> = [
  {
    brand: "github",
    name: "GitHub",
    description: "Follow releases, merged pull requests, and commits to draft source-backed updates.",
    keywords: ["repository", "release", "changelog"],
    available: true,
  },
  {
    brand: "linear",
    name: "Linear",
    description: "Bring product decisions, issue context, and project momentum into Plot.",
    keywords: [],
    available: false,
  },
  {
    brand: "slack",
    name: "Slack",
    description: "Turn important team conversations into durable source material.",
    keywords: [],
    available: false,
  },
  {
    brand: "notion",
    name: "Notion",
    description: "Reference the documents and decisions your workspace already maintains.",
    keywords: [],
    available: false,
  },
  {
    brand: "figma",
    name: "Figma",
    description: "Keep design context close to the content it helped shape.",
    keywords: [],
    available: false,
  },
];

export function IntegrationsWorkspace() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [query, setQuery] = useState("");
  const [connections, setConnections] = useState<GitHubConnection[] | null>(null);
  const [reloadNonce, setReloadNonce] = useState(0);

  // GitHub install and authorization callbacks land here; finish them on the GitHub page.
  const hasGitHubCallback = githubCallbackParams.some((key) => searchParams.has(key));
  useEffect(() => {
    if (!hasGitHubCallback) return;
    const forwarded = new URLSearchParams();
    githubCallbackParams.forEach((key) => {
      const value = searchParams.get(key);
      if (value) forwarded.set(key, value);
    });
    router.replace(`${githubPagePath}?${forwarded.toString()}`);
  }, [hasGitHubCallback, router, searchParams]);

  useWorkspaceChanged(() => {
    setConnections(null);
    setReloadNonce((value) => value + 1);
  });

  useEffect(() => {
    if (hasGitHubCallback) return;
    const controller = new AbortController();
    const workspaceId = getSelectedWorkspaceId();
    plotApiClient.listGitHubConnections({ signal: controller.signal })
      .then((next) => {
        if (!controller.signal.aborted && getSelectedWorkspaceId() === workspaceId) setConnections(next);
      })
      // The GitHub page explains connection failures; the catalog only needs a best-effort status.
      .catch(() => {
        if (!controller.signal.aborted) setConnections([]);
      });
    return () => controller.abort();
  }, [hasGitHubCallback, reloadNonce]);

  const normalizedQuery = query.trim().toLowerCase();
  const visible = catalog.filter((integration) => !normalizedQuery
    || [integration.name, integration.description, ...integration.keywords]
      .some((term) => term.toLowerCase().includes(normalizedQuery)));

  return (
    <div className={workspacePageClass}>
      <section className={workspaceSectionClass} aria-labelledby="connections-heading">
        <WorkspaceHeader
          id="connections-heading"
          title="Connections"
          description="Connect the tools that feed Plot with the context behind your product work."
        >
          <label className={workspaceSearchClass}>
            <HugeiconsIcon icon={Search01Icon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" />
            <span className="sr-only">Search connections</span>
            <input
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search connections"
              className={workspaceSearchInputClass}
            />
            {query && (
              <button
                type="button"
                onClick={() => setQuery("")}
                aria-label="Clear connection search"
                className="glass-button"
              >
                <HugeiconsIcon icon={Cancel01Icon} size={15} color="currentColor" strokeWidth={1.5} aria-hidden="true" />
              </button>
            )}
          </label>
        </WorkspaceHeader>

        {visible.length > 0 ? (
          <div className="grid gap-3 px-6 py-6 pb-10 sm:grid-cols-2">
            {visible.map((integration) => integration.brand === "github"
              ? <GitHubCard key={integration.name} description={integration.description} connections={connections} />
              : (
                <article
                  key={integration.name}
                  className="glass-card flex min-h-[156px] flex-col rounded-[12px] border border-black/10 p-4 opacity-70 dark:border-white/12"
                >
                  <div className="flex items-start justify-between gap-3">
                    <BrandIcon brand={integration.brand} />
                    <span className="pt-1 text-[10px] font-semibold uppercase tracking-[0.14em] text-black/35 dark:text-white/38">
                      Coming soon
                    </span>
                  </div>
                  <h2 className="mt-4 text-[15px] font-semibold tracking-[-0.01em] text-black/82 dark:text-white/86">{integration.name}</h2>
                  <p className="mt-1.5 line-clamp-2 text-[13px] leading-5 text-black/50 dark:text-white/50">{integration.description}</p>
                </article>
              ))}
          </div>
        ) : (
          <WorkspaceEmptyState title="No connections found" description="Try another search term." />
        )}
      </section>
    </div>
  );
}

function GitHubCard({ description, connections }: { description: string; connections: GitHubConnection[] | null }) {
  const status = connections ? githubConnectionStatus(connections) : null;
  const followed = connections ? followedRepositoryCount(connections) : 0;
  const connected = status === "connected" || status === "attention";

  return (
    <article className="glass-card relative flex min-h-[156px] flex-col rounded-[12px] border border-black/10 p-4 transition hover:border-black/[0.18] dark:border-white/12 dark:hover:border-white/22">
      <div className="flex items-start justify-between gap-3">
        <BrandIcon brand="github" />
        <div className="relative z-10 flex items-center gap-2">
          {status === "attention" && (
            <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[11px] font-medium text-amber-800 dark:bg-amber-500/10 dark:text-amber-200">Needs attention</span>
          )}
          {status === "connected" && followed > 0 && (
            <span
              aria-label={`${followed} ${followed === 1 ? "repository" : "repositories"} followed`}
              className="rounded-full bg-black/[0.06] px-2 py-0.5 text-[11px] font-medium text-black/62 dark:bg-white/[0.09] dark:text-white/68"
            >
              {followed}
            </span>
          )}
          {status !== null && (
            <Link
              href={connected ? githubPagePath : `${githubPagePath}?connect=1`}
              className="glass-button inline-flex items-center"
            >
              {connected ? "Manage" : "Connect"}
            </Link>
          )}
        </div>
      </div>
      <h2 className="mt-4 text-[15px] font-semibold tracking-[-0.01em] text-black/82 dark:text-white/86">
        <Link href={githubPagePath} className="after:absolute after:inset-0 after:rounded-[12px] focus-visible:outline-none">
          GitHub
        </Link>
      </h2>
      <p className="mt-1.5 line-clamp-2 text-[13px] leading-5 text-black/50 dark:text-white/50">{description}</p>
    </article>
  );
}
