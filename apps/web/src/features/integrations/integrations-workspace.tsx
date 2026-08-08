"use client";

import { ExternalLink, LoaderCircle, RefreshCw, Search, X } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import {
  getSelectedWorkspaceId,
  plotApiClient,
  PlotApiError,
  type GitHubConnection,
  type GitHubAccessCheckTrigger,
  type GitHubReleaseActivity,
  type GitHubRepository,
  type GitHubRepositoryMonitoring,
} from "@/lib/api-client";

type IntegrationAction = "install" | "connect" | null;

type IntegrationBrand = "figma" | "github" | "linear" | "notion" | "slack";

const plannedIntegrations: Array<{
  brand: Exclude<IntegrationBrand, "github">;
  category: string;
  description: string;
  name: string;
}> = [
  {
    brand: "linear",
    category: "Planning",
    description: "Bring product decisions, issue context, and project momentum into Plot.",
    name: "Linear",
  },
  {
    brand: "slack",
    category: "Collaboration",
    description: "Turn important team conversations into durable source material.",
    name: "Slack",
  },
  {
    brand: "notion",
    category: "Knowledge",
    description: "Reference the documents and decisions your workspace already maintains.",
    name: "Notion",
  },
  {
    brand: "figma",
    category: "Design",
    description: "Keep design context close to the artifacts it helped shape.",
    name: "Figma",
  },
];

export function IntegrationsWorkspace() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const callbackConnectionId = searchParams.get("githubConnection");
  const callbackError = searchParams.get("githubError");
  const [preferredConnectionId] = useState(callbackConnectionId);
  const [connections, setConnections] = useState<GitHubConnection[]>([]);
  const [repositories, setRepositories] = useState<GitHubRepository[]>([]);
  const [selectedRepositoryId, setSelectedRepositoryId] = useState<number | null>(null);
  const [isOwner, setIsOwner] = useState<boolean | null>(null);
  const [connectionNeedsReconnect, setConnectionNeedsReconnect] = useState(false);
  const [repositoryLoadFailed, setRepositoryLoadFailed] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [action, setAction] = useState<IntegrationAction>(null);
  const actionRef = useRef<IntegrationAction>(null);
  const [message, setMessage] = useState<string | null>(callbackError ? callbackMessage(callbackError) : null);
  const [messageRequestId, setMessageRequestId] = useState<string | null>(null);
  const [monitoring, setMonitoring] = useState<GitHubRepositoryMonitoring | null>(null);
  const [monitoringRetrying, setMonitoringRetrying] = useState(false);
  const [monitoringReloadNonce, setMonitoringReloadNonce] = useState(0);
  const [releaseActivity, setReleaseActivity] = useState<GitHubReleaseActivity | null>(null);
  const [releaseActivityError, setReleaseActivityError] = useState<{
    sourceScopeId: string;
    error: unknown;
  } | null>(null);
  const [releaseActionId, setReleaseActionId] = useState<string | null>(null);
  const releaseActionRef = useRef<string | null>(null);
  const [releaseLoadActionId, setReleaseLoadActionId] = useState<string | null>(null);
  const releaseLoadActionRef = useRef<string | null>(null);
  const [reloadNonce, setReloadNonce] = useState(0);
  const [accessCheckActionId, setAccessCheckActionId] = useState<string | null>(null);
  const accessCheckActionRef = useRef<string | null>(null);
  const [accessCheckReloadNonce, setAccessCheckReloadNonce] = useState(0);
  const [integrationQuery, setIntegrationQuery] = useState("");

  const selectedConnection = connections.find((connection) => connection.id === preferredConnectionId)
    ?? connections.find((connection) => connection.status === "ACTIVE")
    ?? connections[0]
    ?? null;
  const activeConnection = selectedConnection?.status === "ACTIVE" ? selectedConnection : null;
  const selectedRepository = repositories.find(
    (repository) => repository.externalRepositoryId === selectedRepositoryId,
  ) ?? null;
  const hasInactiveConnection = connections.some((connection) => connection.status !== "ACTIVE");
  const hasInactiveRepository = repositories.some((repository) => repository.id && repository.status !== "ACTIVE");
  const selectedRepositoryDisconnected = selectedRepository?.statusReason === "REPOSITORY_DELETED";
  const pendingAccessCheckId = repositories.find((repository) =>
    repository.id && (repository.accessCheckStatus === "QUEUED" || repository.accessCheckStatus === "CHECKING"),
  )?.id ?? null;
  const connectionBadgeStatus: "connected" | "attention" | "disconnected" = !selectedConnection
    || selectedConnection.status === "DISABLED"
    || selectedConnection.statusReason === "INSTALLATION_UNINSTALLED"
    || selectedRepositoryDisconnected
    ? "disconnected"
    : selectedConnection.status !== "ACTIVE" || repositoryLoadFailed || connectionNeedsReconnect || hasInactiveRepository
      ? "attention"
      : "connected";
  const monitoredRepository = activeConnection && !connectionNeedsReconnect
    ? selectedRepositoryId === null
      ? repositories.find((repository) => repository.id && repository.status === "ACTIVE") ?? null
      : selectedRepository?.status === "ACTIVE" ? selectedRepository : null
    : null;
  const latestRelease = monitoredRepository?.id === releaseActivity?.sourceScopeId ? releaseActivity : null;
  const latestReleaseError = releaseActivityError &&
    monitoredRepository?.id === releaseActivityError.sourceScopeId
    ? releaseActivityError.error
    : null;
  const normalizedIntegrationQuery = integrationQuery.trim().toLowerCase();
  const githubMatchesQuery = ["github", "engineering", "repository", "release", "changelog"]
    .some((term) => term.includes(normalizedIntegrationQuery));
  const matchingPlannedIntegrations = plannedIntegrations.filter((integration) => (
    [integration.name, integration.category, integration.description]
      .some((term) => term.toLowerCase().includes(normalizedIntegrationQuery))
  ));

  const refresh = () => {
    setMessage(null);
    setMessageRequestId(null);
    setRepositoryLoadFailed(false);
    setIsLoading(true);
    setReloadNonce((value) => value + 1);
  };

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const workspaceId = getSelectedWorkspaceId();
        if (!workspaceId) throw new Error("No workspace is selected");

        const [workspace, nextConnections] = await Promise.all([
          plotApiClient.getWorkspace(workspaceId),
          plotApiClient.listGitHubConnections(),
        ]);
        if (cancelled) return;

        const owner = workspace.role === "OWNER";
        const preferredConnection = nextConnections.find((connection) => connection.id === preferredConnectionId)
          ?? nextConnections.find((connection) => connection.status === "ACTIVE")
          ?? nextConnections[0]
          ?? null;
        setIsOwner(owner);
        setConnections(nextConnections);
        setRepositories(preferredConnection?.repositories ?? []);
        setMonitoring(null);
        setReleaseActivity(null);
        setReleaseActivityError(null);
        setConnectionNeedsReconnect(false);
        setRepositoryLoadFailed(false);

        if (owner && preferredConnection?.status === "ACTIVE") {
          try {
            const nextRepositories = await plotApiClient.listGitHubRepositories(preferredConnection.id);
            if (cancelled) return;
            setRepositories(nextRepositories);
            setSelectedRepositoryId((current) => (
              nextRepositories.some((repository) => repository.externalRepositoryId === current) ? current : null
            ));
          } catch (error) {
            if (cancelled) return;
            setConnectionNeedsReconnect(requiresReconnect(error));
            setRepositoryLoadFailed(true);
            setMessage(errorMessage(error));
            setMessageRequestId(providerRequestId(error));
          }
        }
      } catch (error) {
        if (!cancelled) {
          setConnectionNeedsReconnect(requiresReconnect(error));
          setMessage(errorMessage(error));
          setMessageRequestId(providerRequestId(error));
        }
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }

    queueMicrotask(() => { void load(); });
    return () => { cancelled = true; };
  }, [preferredConnectionId, reloadNonce, accessCheckReloadNonce]);

  useEffect(() => {
    if (!pendingAccessCheckId) return;
    const timer = setTimeout(() => setAccessCheckReloadNonce((value) => value + 1), 2000);
    return () => clearTimeout(timer);
  }, [pendingAccessCheckId]);

  useEffect(() => {
    if (callbackConnectionId || callbackError) router.replace("/integrations");
  }, [callbackConnectionId, callbackError, router]);

  useEffect(() => {
    const sourceScopeId = monitoredRepository?.id;
    let cancelled = false;

    queueMicrotask(() => {
      if (cancelled) return;
      setReleaseActivity(null);
      setReleaseActivityError(null);
      if (!sourceScopeId) return;

      void plotApiClient.getGitHubReleaseActivity(sourceScopeId)
        .then((activity) => {
          if (!cancelled) setReleaseActivity(activity);
        })
        .catch((error: unknown) => {
          if (!cancelled) setReleaseActivityError({ sourceScopeId, error });
        });
    });
    return () => { cancelled = true; };
  }, [monitoredRepository?.id]);

  useEffect(() => {
    const sourceScopeId = monitoredRepository?.id;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    if (!sourceScopeId) return () => { cancelled = true; };

    const load = async () => {
      try {
        const next = await plotApiClient.getGitHubRepositoryMonitoring(sourceScopeId);
        if (cancelled) return;
        setMonitoring(next);
        if (next.analysisStatus === "QUEUED" || next.analysisStatus === "ANALYZING") {
          timer = setTimeout(() => { void load(); }, 3000);
        }
      } catch (error) {
        if (!cancelled && requiresReconnect(error)) setConnectionNeedsReconnect(true);
      }
    };
    queueMicrotask(() => {
      if (cancelled) return;
      setMonitoring(monitoredRepository.monitoring);
      void load();
    });
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [monitoredRepository?.id, monitoredRepository?.monitoring, monitoringReloadNonce]);

  const installGitHub = async () => {
    if (actionRef.current) return;
    actionRef.current = "install";
    setAction("install");
    setMessage(null);
    setMessageRequestId(null);
    try {
      const request = await plotApiClient.createGitHubInstallationRequest();
      window.location.assign(request.installUrl);
    } catch (error) {
      setMessage(errorMessage(error));
    } finally {
      actionRef.current = null;
      setAction(null);
    }
  };

  const connectRepository = async () => {
    if (!activeConnection || !selectedRepository || selectedRepository.id || actionRef.current) return;
    actionRef.current = "connect";
    setAction("connect");
    setMessage(null);
    setMessageRequestId(null);
    try {
      const connected = await plotApiClient.connectGitHubRepository(
        activeConnection.id,
        selectedRepository.externalRepositoryId,
      );
      setRepositories((current) => current.map((repository) => (
        repository.externalRepositoryId === connected.externalRepositoryId ? connected : repository
      )));
    } catch (error) {
      setMessage(errorMessage(error));
    } finally {
      actionRef.current = null;
      setAction(null);
    }
  };

  const retryRelease = async (activity: GitHubReleaseActivity) => {
    if (releaseActionRef.current) return;
    releaseActionRef.current = activity.id;
    setReleaseActionId(activity.id);
    setMessage(null);
    setMessageRequestId(null);
    try {
      const next = await plotApiClient.retryGitHubReleaseDraft(activity.sourceScopeId, activity.id);
      setReleaseActivity(next);
    } catch (error) {
      setMessage(errorMessage(error));
    } finally {
      releaseActionRef.current = null;
      setReleaseActionId(null);
    }
  };

  const retryReleaseActivityLoad = async (sourceScopeId: string) => {
    if (releaseLoadActionRef.current) return;
    releaseLoadActionRef.current = sourceScopeId;
    setReleaseLoadActionId(sourceScopeId);
    setReleaseActivityError(null);
    try {
      const activity = await plotApiClient.getGitHubReleaseActivity(sourceScopeId);
      setReleaseActivity(activity);
    } catch (error) {
      setReleaseActivityError({ sourceScopeId, error });
    } finally {
      releaseLoadActionRef.current = null;
      setReleaseLoadActionId(null);
    }
  };

  const retryMonitoring = async (sourceScopeId: string) => {
    if (monitoringRetrying) return;
    setMonitoringRetrying(true);
    try {
      setMonitoring(await plotApiClient.retryGitHubRepositoryMonitoring(sourceScopeId));
      setMonitoringReloadNonce((value) => value + 1);
    } catch (error) {
      if (requiresReconnect(error)) setConnectionNeedsReconnect(true);
      setMessage(errorMessage(error));
    } finally {
      setMonitoringRetrying(false);
    }
  };

  const recheckRepositoryAccess = async (sourceScopeId: string, trigger: GitHubAccessCheckTrigger) => {
    if (accessCheckActionRef.current) return;
    accessCheckActionRef.current = sourceScopeId;
    setAccessCheckActionId(sourceScopeId);
    setMessage(null);
    setMessageRequestId(null);
    try {
      const check = await plotApiClient.recheckGitHubRepositoryAccess(sourceScopeId, trigger);
      setRepositories((current) => current.map((repository) => (
        repository.id === sourceScopeId
          ? { ...repository, accessCheckStatus: check.status }
          : repository
      )));
      setAccessCheckReloadNonce((value) => value + 1);
    } catch (error) {
      setMessage(errorMessage(error));
      setMessageRequestId(providerRequestId(error));
    } finally {
      accessCheckActionRef.current = null;
      setAccessCheckActionId(null);
    }
  };

  return (
    <div className="h-screen overflow-y-auto bg-[#f7f8fa] px-5 py-9 dark:bg-[#111113] sm:px-8 sm:py-12 lg:px-12">
      <div className="mx-auto max-w-[1040px] pb-16">
        <header className="max-w-[680px]">
          <p className="mb-4 text-[11px] font-semibold uppercase tracking-[0.18em] text-black/38 dark:text-white/38">
            Workspace sources
          </p>
          <h1 className="font-serif text-[42px] font-normal leading-[1.08] tracking-[-0.02em] text-black/90 dark:text-white/92 sm:text-[48px]">
            Integrations
          </h1>
          <p className="mt-4 text-[15px] leading-6 text-black/52 dark:text-white/50">
            Connect the tools where product work happens. Plot turns their activity into source material for review-ready artifacts.
          </p>
        </header>

        <label className="mt-8 flex h-11 max-w-[560px] items-center gap-3 rounded-[11px] border border-black/10 bg-white px-3.5 shadow-[0_1px_1px_rgb(15_23_42_/_0.02)] transition focus-within:border-black/25 focus-within:ring-2 focus-within:ring-black/[0.04] dark:border-white/10 dark:bg-white/[0.05] dark:focus-within:border-white/25">
          <Search className="size-4 shrink-0 text-black/35 dark:text-white/35" aria-hidden="true" />
          <span className="sr-only">Search integrations</span>
          <input
            type="search"
            value={integrationQuery}
            onChange={(event) => setIntegrationQuery(event.target.value)}
            placeholder="Search integrations"
            className="min-w-0 flex-1 bg-transparent text-sm text-black/80 outline-none placeholder:text-black/35 dark:text-white/82 dark:placeholder:text-white/35"
          />
          {integrationQuery && (
            <button
              type="button"
              onClick={() => setIntegrationQuery("")}
              aria-label="Clear integration search"
              className="rounded-md p-1 text-black/35 transition hover:bg-black/[0.05] hover:text-black/65 dark:text-white/35 dark:hover:bg-white/10 dark:hover:text-white/70"
            >
              <X className="size-3.5" />
            </button>
          )}
        </label>

        {githubMatchesQuery && (
          <section className="mt-12" aria-labelledby="available-now-heading">
            <SectionHeading
              id="available-now-heading"
              title="Available now"
              description="Configured for this workspace"
            />

            <div className="mt-4 overflow-hidden rounded-[16px] border border-black/[0.09] bg-white shadow-[0_1px_2px_rgb(15_23_42_/_0.025)] dark:border-white/10 dark:bg-white/[0.04]">
              <div className="flex items-start gap-4 px-5 py-5 sm:px-6">
                <BrandIcon brand="github" />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
                    <h2 className="text-[16px] font-semibold tracking-[-0.01em] text-black/85 dark:text-white/88">
                      GitHub
                    </h2>
                    <span className="text-[11px] font-medium uppercase tracking-[0.12em] text-black/38 dark:text-white/38">
                      Engineering
                    </span>
                  </div>
                  <p className="mt-1.5 max-w-2xl text-sm leading-5 text-black/50 dark:text-white/50">
                    Monitor one repository&apos;s releases and prepare review-ready changelog drafts.
                  </p>
                </div>
                {!isLoading && <ConnectionBadge status={connectionBadgeStatus} />}
              </div>

              <div className="border-t border-black/[0.07] px-5 pb-6 dark:border-white/[0.08] sm:px-6">
                {message && (
                  <StatusMessage
                    message={message}
                    requestId={messageRequestId}
                    onRetry={refresh}
                    onReconnect={activeConnection && repositoryLoadFailed && !connectionNeedsReconnect
                      ? () => { void installGitHub(); }
                      : undefined}
                    onDismiss={() => setMessage(null)}
                  />
                )}
                {isLoading ? (
                  <Loading />
                ) : isOwner === null ? null : !isOwner ? (
                  <NonOwnerState connected={Boolean(activeConnection) && !connectionNeedsReconnect} />
                ) : !selectedConnection || (connectionNeedsReconnect && repositories.length === 0) ? (
                  <ConnectState
                    reconnect={hasInactiveConnection || connectionNeedsReconnect}
                    busy={action === "install"}
                    onConnect={() => { void installGitHub(); }}
                  />
                ) : (
                  <div className="pt-6">
                    {(!activeConnection || connectionNeedsReconnect) && (
                      <ConnectionRecoveryState
                        connection={selectedConnection}
                        busy={action === "install"}
                        onReconnect={() => { void installGitHub(); }}
                      />
                    )}
                    <div className="flex items-center justify-between gap-4">
                      <div>
                        <h3 className="text-sm font-semibold text-black/80 dark:text-white/82">Repository access</h3>
                        <p className="mt-1 text-sm text-black/48 dark:text-white/48">
                          Select one repository to connect and monitor its releases.
                        </p>
                      </div>
                      <button
                        type="button"
                        onClick={refresh}
                        disabled={action !== null}
                        className="inline-flex shrink-0 items-center gap-2 rounded-full border border-black/10 px-3 py-1.5 text-sm font-medium text-black/60 transition hover:bg-black/[0.04] disabled:opacity-50 dark:border-white/12 dark:text-white/65 dark:hover:bg-white/10"
                      >
                        <RefreshCw className="size-3.5" />
                        Refresh
                      </button>
                    </div>

                    {repositories.length > 0 ? (
                      <div
                        className="mt-5 max-h-64 space-y-2 overflow-y-auto overscroll-contain pr-1"
                        role="radiogroup"
                        aria-label="Allowed GitHub repositories"
                      >
                        {repositories.map((repository) => (
                          <label
                            key={repository.externalRepositoryId}
                            className="flex cursor-pointer items-center gap-3 rounded-[10px] border border-black/10 px-4 py-3 text-sm transition hover:bg-black/[0.02] dark:border-white/10 dark:hover:bg-white/5"
                          >
                            <input
                              type="radio"
                              name="repository"
                              checked={selectedRepositoryId === repository.externalRepositoryId}
                              onChange={() => setSelectedRepositoryId(repository.externalRepositoryId)}
                              disabled={action !== null}
                            />
                            <span className="min-w-0 flex-1 truncate font-medium text-black/78 dark:text-white/80">
                              {repository.displayName}
                            </span>
                            {repository.id && (
                              <RepositoryAccessLabel
                                repository={repository}
                                connectionActive={Boolean(activeConnection && !connectionNeedsReconnect)}
                              />
                            )}
                            <a
                              href={repository.url}
                              target="_blank"
                              rel="noreferrer"
                              aria-label={`Open ${repository.displayName} on GitHub`}
                              className="text-black/38 hover:text-black/65 dark:text-white/38 dark:hover:text-white/70"
                              onClick={(event) => event.stopPropagation()}
                            >
                              <ExternalLink className="size-3.5" />
                            </a>
                          </label>
                        ))}
                      </div>
                    ) : (
                      <div className="mt-5 rounded-[10px] border border-dashed border-black/10 bg-black/[0.015] p-4 text-sm leading-5 text-black/52 dark:border-white/10 dark:bg-white/[0.025] dark:text-white/52">
                        No repositories are currently granted. Update this GitHub App installation&apos;s repository access, then refresh.
                      </div>
                    )}

                    {repositories.length > 0 && selectedRepository && !selectedRepository.id && (
                      <button
                        type="button"
                        onClick={() => { void connectRepository(); }}
                        disabled={
                          !activeConnection
                          || repositoryLoadFailed
                          || connectionNeedsReconnect
                          || action !== null
                        }
                        className="mt-5 inline-flex items-center gap-2 rounded-full bg-black px-4 py-2 text-sm font-semibold text-white transition disabled:cursor-not-allowed disabled:opacity-40 dark:bg-white dark:text-black"
                      >
                        {action === "connect" && <LoaderCircle className="size-4 animate-spin" />}
                        Connect repository
                      </button>
                    )}

                    {selectedRepository?.id && selectedRepository.status !== "ACTIVE" && (
                      <RepositoryAccessRecovery
                        repository={selectedRepository}
                        busy={accessCheckActionId === selectedRepository.id || action === "install"}
                        onCheckAgain={() => { void recheckRepositoryAccess(selectedRepository.id!, "CHECK_AGAIN"); }}
                        onRetry={() => { void recheckRepositoryAccess(selectedRepository.id!, "RETRY"); }}
                        onReconnect={() => { void installGitHub(); }}
                      />
                    )}

                    {monitoredRepository?.id && latestRelease && (
                      <LatestRelease
                        activity={latestRelease}
                        busy={releaseActionId === latestRelease.id || action === "install"}
                        onRetry={() => { void retryRelease(latestRelease); }}
                        onReconnect={() => { void installGitHub(); }}
                      />
                    )}
                    {monitoredRepository?.id && monitoring && (
                      <RepositoryMonitoring
                        monitoring={monitoring}
                        busy={monitoringRetrying || action === "install"}
                        onRetry={() => { void retryMonitoring(monitoredRepository.id!); }}
                        onReconnect={() => { void installGitHub(); }}
                      />
                    )}
                    {monitoredRepository?.id && latestReleaseError !== null && latestReleaseError !== undefined && (
                      <ReleaseActivityLoadError
                        error={latestReleaseError}
                        busy={releaseLoadActionId === monitoredRepository.id || action === "install"}
                        onRetry={() => { void retryReleaseActivityLoad(monitoredRepository.id!); }}
                        onReconnect={() => { void installGitHub(); }}
                      />
                    )}
                  </div>
                )}
              </div>
            </div>
          </section>
        )}

        {matchingPlannedIntegrations.length > 0 && (
          <section className="mt-12" aria-labelledby="planned-integrations-heading">
            <SectionHeading
              id="planned-integrations-heading"
              title="On the horizon"
              description="The next sources we are shaping for Plot"
            />
            <div className="mt-4 grid gap-3 md:grid-cols-2">
              {matchingPlannedIntegrations.map((integration) => (
                <article
                  key={integration.name}
                  className="group flex min-h-36 items-start gap-4 rounded-[14px] border border-black/[0.08] bg-white/70 p-5 transition hover:border-black/[0.14] hover:bg-white dark:border-white/[0.09] dark:bg-white/[0.035] dark:hover:border-white/15 dark:hover:bg-white/[0.055]"
                >
                  <BrandIcon brand={integration.brand} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <h3 className="text-[15px] font-semibold tracking-[-0.01em] text-black/82 dark:text-white/86">
                          {integration.name}
                        </h3>
                        <p className="mt-1 text-[11px] font-medium uppercase tracking-[0.12em] text-black/38 dark:text-white/38">
                          {integration.category}
                        </p>
                      </div>
                      <span className="shrink-0 pt-0.5 text-[12px] text-black/38 dark:text-white/38">Planned</span>
                    </div>
                    <p className="mt-3 text-[13px] leading-5 text-black/50 dark:text-white/50">
                      {integration.description}
                    </p>
                  </div>
                </article>
              ))}
            </div>
          </section>
        )}

        {!githubMatchesQuery && matchingPlannedIntegrations.length === 0 && (
          <div className="mt-12 rounded-[14px] border border-dashed border-black/10 px-5 py-10 text-center dark:border-white/10">
            <p className="text-sm font-medium text-black/65 dark:text-white/68">No integrations found</p>
            <p className="mt-1 text-sm text-black/42 dark:text-white/42">Try another tool or category.</p>
          </div>
        )}
      </div>
    </div>
  );
}

function SectionHeading({
  description,
  id,
  title,
}: {
  description: string;
  id: string;
  title: string;
}) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-2">
      <h2 id={id} className="text-[15px] font-semibold tracking-[-0.01em] text-black/78 dark:text-white/82">
        {title}
      </h2>
      <p className="text-[12px] text-black/38 dark:text-white/38">{description}</p>
    </div>
  );
}

function BrandIcon({ brand }: { brand: IntegrationBrand }) {
  return (
    <div className="flex size-11 shrink-0 items-center justify-center rounded-[12px] border border-black/[0.08] bg-white shadow-[0_1px_1px_rgb(15_23_42_/_0.025)] dark:border-white/10">
      {brand === "github" && (
        <svg role="img" aria-label="GitHub" viewBox="0 0 24 24" className="size-[23px] fill-[#181717]">
          <path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12" />
        </svg>
      )}
      {brand === "linear" && (
        <svg role="img" aria-label="Linear" viewBox="0 0 24 24" className="size-[23px] fill-[#5e6ad2]">
          <path d="M2.886 4.18A11.982 11.982 0 0 1 11.99 0C18.624 0 24 5.376 24 12.009c0 3.64-1.62 6.903-4.18 9.105L2.887 4.18ZM1.817 5.626l16.556 16.556c-.524.33-1.075.62-1.65.866L.951 7.277c.247-.575.537-1.126.866-1.65ZM.322 9.163l14.515 14.515c-.71.172-1.443.282-2.195.322L0 11.358a12 12 0 0 1 .322-2.195Zm-.17 4.862 9.823 9.824a12.02 12.02 0 0 1-9.824-9.824Z" />
        </svg>
      )}
      {brand === "slack" && (
        <svg role="img" aria-label="Slack" viewBox="0 0 24 24" className="size-[24px]">
          <path fill="#e01e5a" d="M5.04 15.17a2.52 2.52 0 1 1-2.52-2.52h2.52v2.52Zm1.27 0a2.52 2.52 0 0 1 5.04 0v6.31a2.52 2.52 0 1 1-5.04 0v-6.31Z" />
          <path fill="#36c5f0" d="M8.83 5.04a2.52 2.52 0 1 1 2.52-2.52v2.52H8.83Zm0 1.27a2.52 2.52 0 0 1 0 5.04H2.52a2.52 2.52 0 1 1 0-5.04h6.31Z" />
          <path fill="#2eb67d" d="M18.96 8.83a2.52 2.52 0 1 1 2.52 2.52h-2.52V8.83Zm-1.27 0a2.52 2.52 0 0 1-5.04 0V2.52a2.52 2.52 0 1 1 5.04 0v6.31Z" />
          <path fill="#ecb22e" d="M15.17 18.96a2.52 2.52 0 1 1-2.52 2.52v-2.52h2.52Zm0-1.27a2.52 2.52 0 0 1 0-5.04h6.31a2.52 2.52 0 1 1 0 5.04h-6.31Z" />
        </svg>
      )}
      {brand === "notion" && (
        <svg role="img" aria-label="Notion" viewBox="0 0 24 24" className="size-[23px] fill-black">
          <path d="M4.459 4.208c.746.606 1.026.56 2.428.466l13.215-.793c.28 0 .047-.28-.046-.326L17.86 1.968c-.42-.326-.981-.7-2.055-.607L3.01 2.295c-.466.046-.56.28-.374.466zm.793 3.08v13.904c0 .747.373 1.027 1.214.98l14.523-.84c.841-.046.935-.56.935-1.167V6.354c0-.606-.233-.933-.748-.887l-15.177.887c-.56.047-.747.327-.747.933zm14.337.745c.093.42 0 .84-.42.888l-.7.14v10.264c-.608.327-1.168.514-1.635.514-.748 0-.935-.234-1.495-.933l-4.577-7.186v6.952L12.21 19s0 .84-1.168.84l-3.222.186c-.093-.186 0-.653.327-.746l.84-.233V9.854L7.822 9.76c-.094-.42.14-1.026.793-1.073l3.456-.233 4.764 7.279v-6.44l-1.215-.139c-.093-.514.28-.887.747-.933zM1.936 1.035l13.31-.98c1.634-.14 2.055-.047 3.082.7l4.249 2.986c.7.513.934.653.934 1.213v16.378c0 1.026-.373 1.634-1.68 1.726l-15.458.934c-.98.047-1.448-.093-1.962-.747l-3.129-4.06c-.56-.747-.793-1.306-.793-1.96V2.667c0-.839.374-1.54 1.447-1.632z" />
        </svg>
      )}
      {brand === "figma" && (
        <svg role="img" aria-label="Figma" viewBox="0 0 38 57" className="h-[26px] w-[18px]">
          <path fill="#f24e1e" d="M0 9.5A9.5 9.5 0 0 1 9.5 0H19v19H9.5A9.5 9.5 0 0 1 0 9.5Z" />
          <path fill="#ff7262" d="M19 0h9.5a9.5 9.5 0 1 1 0 19H19V0Z" />
          <path fill="#a259ff" d="M0 28.5A9.5 9.5 0 0 1 9.5 19H19v19H9.5A9.5 9.5 0 0 1 0 28.5Z" />
          <path fill="#1abcfe" d="M19 19h9.5a9.5 9.5 0 1 1 0 19H19V19Z" />
          <path fill="#0acf83" d="M0 47.5A9.5 9.5 0 0 1 9.5 38H19v9.5a9.5 9.5 0 0 1-19 0Z" />
        </svg>
      )}
    </div>
  );
}

function RepositoryMonitoring({
  monitoring,
  busy,
  onRetry,
  onReconnect,
}: {
  monitoring: GitHubRepositoryMonitoring;
  busy: boolean;
  onRetry: () => void;
  onReconnect: () => void;
}) {
  let content;
  if (monitoring.analysisStatus === "QUEUED" || monitoring.analysisStatus === "ANALYZING") {
    content = <span>Analyzing release convention…</span>;
  } else if (monitoring.analysisStatus === "COMPLETED") {
    content = (
      <span>
        {conventionLabel(monitoring)}
        {monitoring.sampleSource && ` · ${monitoring.sampleSize} ${monitoring.sampleSource.toLowerCase()}`}
      </span>
    );
  } else if (monitoring.lastErrorCode === "GITHUB_ACCESS_DENIED" || monitoring.lastErrorCode === "GITHUB_NOT_FOUND") {
    content = (
      <span>
        GitHub permission lost ·{" "}
        <button type="button" onClick={onReconnect} disabled={busy} className="font-semibold underline disabled:opacity-50">
          Reconnect
        </button>
      </span>
    );
  } else {
    content = (
      <span>
        Analysis failed ·{" "}
        <button type="button" onClick={onRetry} disabled={busy} className="font-semibold underline disabled:opacity-50">
          Retry
        </button>
      </span>
    );
  }
  return (
    <div role="status" aria-live="polite" className="mt-5 rounded-[10px] border border-black/[0.08] bg-black/[0.015] px-4 py-3 text-sm text-black/58 dark:border-white/10 dark:bg-white/[0.025] dark:text-white/58">
      <div className="text-xs font-semibold uppercase tracking-[0.08em] text-black/38 dark:text-white/38">
        Repository monitoring
      </div>
      <div className="mt-1.5">{content}</div>
    </div>
  );
}

function conventionLabel(monitoring: GitHubRepositoryMonitoring) {
  if (monitoring.releaseConvention === "SEMVER_V") return "Release convention: v-prefixed SemVer";
  if (monitoring.releaseConvention === "SEMVER") return "Release convention: SemVer";
  if (monitoring.releaseConvention === "PREFIXED") return `Release convention: ${monitoring.tagPrefix ?? "prefixed"} SemVer`;
  if (monitoring.releaseConvention === "NO_TAGS") return "No release tags found";
  return "Mixed release tags";
}

function LatestRelease({
  activity,
  busy,
  onRetry,
  onReconnect,
}: {
  activity: GitHubReleaseActivity;
  busy: boolean;
  onRetry: () => void;
  onReconnect: () => void;
}) {
  let content;
  if (activity.status === "QUEUED" || activity.status === "RESOLVING" || activity.status === "GENERATING") {
    content = <span>Preparing {activity.tagName}…</span>;
  } else if (activity.status === "READY" && activity.artifactId) {
    content = (
      <span>
        <span>Draft ready</span> ·{" "}
        <Link
          href={`/artifacts?artifact=${encodeURIComponent(activity.artifactId)}`}
          className="font-semibold text-[#2563eb] hover:underline dark:text-[#93c5fd]"
        >
          Review changelog
        </Link>
      </span>
    );
  } else if (activity.status === "READY") {
    content = <span>Draft ready</span>;
  } else if (activity.status === "NO_ACTIVITY") {
    content = <span>No customer-facing changes found for {activity.tagName}</span>;
  } else if (activity.status === "NEEDS_RANGE") {
    content = <span>Release baseline saved. The next release will prepare a changelog.</span>;
  } else if (activity.errorCode === "GITHUB_ACCESS_DENIED") {
    content = (
      <span>
        <span>GitHub permission lost</span> ·{" "}
        <button
          type="button"
          onClick={onReconnect}
          disabled={busy}
          aria-label={`Reconnect GitHub for release ${activity.tagName}`}
          className="font-semibold text-[#2563eb] hover:underline disabled:opacity-50 dark:text-[#93c5fd]"
        >
          Reconnect
        </button>
      </span>
    );
  } else {
    content = (
      <span>
        Could not prepare {activity.tagName} ·{" "}
        <button
          type="button"
          onClick={onRetry}
          disabled={busy}
          aria-label={`Retry release ${activity.tagName}`}
          className="font-semibold text-[#2563eb] hover:underline disabled:opacity-50 dark:text-[#93c5fd]"
        >
          Retry
        </button>
      </span>
    );
  }

  return (
    <div
      role="status"
      aria-live="polite"
      className="mt-5 rounded-[10px] border border-black/[0.08] bg-black/[0.015] px-4 py-3 text-sm text-black/58 dark:border-white/10 dark:bg-white/[0.025] dark:text-white/58"
    >
      <div className="text-xs font-semibold uppercase tracking-[0.08em] text-black/38 dark:text-white/38">
        Latest release
      </div>
      <div className="mt-1.5">{content}</div>
    </div>
  );
}

function ReleaseActivityLoadError({
  error,
  busy,
  onRetry,
  onReconnect,
}: {
  error: unknown;
  busy: boolean;
  onRetry: () => void;
  onReconnect: () => void;
}) {
  const reconnect = requiresReconnect(error);
  return (
    <div
      role="status"
      aria-live="polite"
      className="mt-5 rounded-[10px] border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:border-amber-400/30 dark:bg-amber-500/10 dark:text-amber-100"
    >
      <span>{reconnect ? "GitHub permission lost" : "Could not load release activity"}</span> ·{" "}
      <button
        type="button"
        onClick={reconnect ? onReconnect : onRetry}
        disabled={busy}
        aria-label={reconnect ? "Reconnect GitHub for release activity" : "Retry release activity"}
        className="font-semibold underline underline-offset-2 disabled:opacity-50"
      >
        {reconnect ? "Reconnect" : "Retry"}
      </button>
    </div>
  );
}

function ConnectionRecoveryState({
  connection,
  busy,
  onReconnect,
}: {
  connection: GitHubConnection;
  busy: boolean;
  onReconnect: () => void;
}) {
  const uninstalled = connection.statusReason === "INSTALLATION_UNINSTALLED" || connection.status === "DISABLED";
  const message = connection.statusReason === "INSTALLATION_SUSPENDED"
    ? "GitHub installation is suspended. Restore it in GitHub, then check repository access again."
    : connection.statusReason === "AUTH_EXPIRED"
      ? "GitHub authentication expired. Reconnect GitHub to restore access."
      : uninstalled
        ? "The GitHub installation was removed. Reconnect GitHub to restore repository access."
        : "GitHub access needs attention before new work can be fetched.";
  return (
    <div role="status" aria-live="polite" className="mb-5 rounded-[10px] border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:border-amber-400/30 dark:bg-amber-500/10 dark:text-amber-100">
      <div>{message}</div>
      <button
        type="button"
        onClick={onReconnect}
        disabled={busy}
        className="mt-2 font-semibold underline underline-offset-2 disabled:opacity-50"
      >
        Reconnect GitHub
      </button>
    </div>
  );
}

function RepositoryAccessLabel({
  repository,
  connectionActive,
}: {
  repository: GitHubRepository;
  connectionActive: boolean;
}) {
  const label = !connectionActive
    ? "Needs attention"
    : repository.status === "ACTIVE"
      ? "Connected"
      : repository.accessCheckStatus === "QUEUED" || repository.accessCheckStatus === "CHECKING"
        ? "Checking…"
        : repository.statusReason === "REPOSITORY_DELETED"
          ? "Disconnected"
          : "Needs attention";
  const styles = label === "Connected"
    ? "text-emerald-700 dark:text-emerald-300"
    : label === "Disconnected"
      ? "text-black/45 dark:text-white/48"
      : "text-amber-700 dark:text-amber-300";
  return <span className={`text-xs ${styles}`}>{label}</span>;
}

function RepositoryAccessRecovery({
  repository,
  busy,
  onCheckAgain,
  onRetry,
  onReconnect,
}: {
  repository: GitHubRepository;
  busy: boolean;
  onCheckAgain: () => void;
  onRetry: () => void;
  onReconnect: () => void;
}) {
  const checking = repository.accessCheckStatus === "QUEUED" || repository.accessCheckStatus === "CHECKING";
  const deleted = repository.statusReason === "REPOSITORY_DELETED";
  const userDisconnected = repository.statusReason === "USER_DISCONNECTED";
  const message = checking
    ? "Checking current GitHub access…"
    : userDisconnected
      ? "This repository was disconnected from Plot. Reconnect GitHub to choose it again."
    : deleted
      ? "This repository is currently unavailable. Check again after restoring it, or connect another repository."
      : repository.statusReason === "GRANT_REMOVED"
        ? "GitHub no longer grants Plot access to this repository. Update the App installation, then retry."
        : repository.statusReason === "REPOSITORY_TRANSFERRED"
          ? "This repository moved. Check again to verify its current location and access."
          : "This repository needs attention before Plot can fetch new work.";
  return (
    <div role="status" aria-live="polite" className="mt-5 rounded-[10px] border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:border-amber-400/30 dark:bg-amber-500/10 dark:text-amber-100">
      <div>{message}</div>
      {!checking && (
        <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
          {!userDisconnected && (
            <button type="button" onClick={deleted ? onCheckAgain : onRetry} disabled={busy} className="font-semibold underline underline-offset-2 disabled:opacity-50">
              {deleted ? "Check again" : "Retry"}
            </button>
          )}
          <button type="button" onClick={onReconnect} disabled={busy} className="font-semibold underline underline-offset-2 disabled:opacity-50">
            {deleted ? "Connect another repository" : "Reconnect"}
          </button>
        </div>
      )}
    </div>
  );
}

function ConnectionBadge({ status }: { status: "connected" | "attention" | "disconnected" }) {
  const styles = status === "connected"
    ? "bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-300"
    : status === "attention"
      ? "bg-amber-50 text-amber-800 dark:bg-amber-500/10 dark:text-amber-200"
      : "bg-black/[0.05] text-black/45 dark:bg-white/10 dark:text-white/48";
  return (
    <span className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-medium ${styles}`}>
      {status === "connected" ? "Connected" : status === "attention" ? "Needs attention" : "Disconnected"}
    </span>
  );
}

function ConnectState({ reconnect, busy, onConnect }: { reconnect: boolean; busy: boolean; onConnect: () => void }) {
  return (
    <div className="mt-6 border-t border-black/[0.08] pt-6 dark:border-white/10">
      <h3 className="text-sm font-semibold text-black/80 dark:text-white/82">
        {reconnect ? "GitHub access needs to be restored" : "Connect GitHub to get started"}
      </h3>
      <p className="mt-1 max-w-xl text-sm leading-5 text-black/50 dark:text-white/50">
        {reconnect
          ? "The previous installation is no longer active. Reinstall the GitHub App to choose repository access again."
          : "Plot requests read-only repository metadata and pull requests. You will choose a repository after installation."}
      </p>
      <button
        type="button"
        onClick={onConnect}
        disabled={busy}
        className="mt-5 inline-flex items-center gap-2 rounded-full bg-black px-4 py-2 text-sm font-semibold text-white disabled:opacity-50 dark:bg-white dark:text-black"
      >
        {busy && <LoaderCircle className="size-4 animate-spin" />}
        {reconnect ? "Reconnect GitHub" : "Connect GitHub"}
      </button>
    </div>
  );
}

function NonOwnerState({ connected }: { connected: boolean }) {
  return (
    <div className="mt-6 rounded-[10px] border border-black/10 bg-black/[0.015] p-4 text-sm dark:border-white/10 dark:bg-white/[0.025]">
      <div className="font-medium text-black/78 dark:text-white/80">
        GitHub is {connected ? "connected" : "not connected"} for this workspace
      </div>
      <p className="mt-1 text-black/52 dark:text-white/52">Workspace owner must connect GitHub.</p>
    </div>
  );
}

function Loading() {
  return (
    <div className="mt-6 flex items-center gap-2 border-t border-black/[0.08] pt-6 text-sm text-black/50 dark:border-white/10 dark:text-white/50">
      <LoaderCircle className="size-4 animate-spin" />
      Loading GitHub integration…
    </div>
  );
}

function StatusMessage({
  message,
  requestId,
  onRetry,
  onReconnect,
  onDismiss,
}: {
  message: string;
  requestId: string | null;
  onRetry: () => void;
  onReconnect?: () => void;
  onDismiss: () => void;
}) {
  return (
    <div role="alert" className="mt-5 flex items-start gap-3 rounded-[10px] border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-400/30 dark:bg-amber-500/10 dark:text-amber-100">
      <div className="min-w-0 flex-1">
        <p>{message}</p>
        {requestId && (
          <details className="mt-1 text-xs">
            <summary className="cursor-pointer font-medium">Technical details</summary>
            <code>GitHub request {requestId}</code>
          </details>
        )}
      </div>
      <button type="button" onClick={onRetry} className="shrink-0 font-semibold underline underline-offset-2">Retry</button>
      {onReconnect && (
        <button type="button" onClick={onReconnect} className="shrink-0 font-semibold underline underline-offset-2">
          Reconnect
        </button>
      )}
      <button type="button" onClick={onDismiss} aria-label="Dismiss message"><X className="size-4" /></button>
    </div>
  );
}

function callbackMessage(value: string) {
  if (value === "invalid") return "The GitHub installation link expired. Try connecting again.";
  if (value === "unauthorized") return "Only the workspace owner can connect GitHub.";
  if (value === "unavailable") return "GitHub is temporarily unavailable. Try again shortly.";
  return "GitHub could not be connected. Try again.";
}

function errorMessage(error: unknown) {
  if (error instanceof PlotApiError) {
    if (error.code === "GITHUB_NOT_CONFIGURED") return "GitHub is not configured for this environment. Try again after an administrator enables it.";
    if (error.code === "GITHUB_RATE_LIMITED") return "GitHub rate limit reached. Wait a moment, then retry.";
    if (error.code === "GITHUB_NOT_FOUND") return "The previous GitHub installation was replaced or removed. Reconnect GitHub to restore repository access.";
    if (error.code === "GITHUB_ACCESS_DENIED" || error.code === "CONNECTION_INACTIVE" || error.code === "REPOSITORY_INACTIVE") return "GitHub access was revoked. Reconnect GitHub, then retry.";
    if (error.code === "FORBIDDEN") return "Workspace owner must connect GitHub.";
    if (error.code === "GITHUB_PROVIDER_UNAVAILABLE") return "GitHub is temporarily unavailable. Try again shortly.";
    return "GitHub request failed. Try again.";
  }
  return "Could not update the GitHub integration. Try again.";
}

function providerRequestId(error: unknown) {
  if (!(error instanceof PlotApiError)) return null;
  return error.message.match(/\brequest ([A-Za-z0-9_-]{1,100})\b/)?.[1] ?? null;
}

function requiresReconnect(error: unknown) {
  return error instanceof PlotApiError && new Set([
    "GITHUB_NOT_FOUND",
    "GITHUB_ACCESS_DENIED",
    "CONNECTION_INACTIVE",
    "REPOSITORY_INACTIVE",
  ]).has(error.code);
}
