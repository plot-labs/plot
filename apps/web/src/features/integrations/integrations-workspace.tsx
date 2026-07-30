"use client";

import { ExternalLink, GitBranch, LoaderCircle, RefreshCw, X } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import {
  getSelectedWorkspaceId,
  plotApiClient,
  PlotApiError,
  type GitHubConnection,
  type GitHubImport,
  type GitHubReleaseActivity,
  type GitHubRepository,
} from "@/lib/api-client";

type IntegrationAction = "install" | "import" | null;

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
  const [lastImport, setLastImport] = useState<GitHubImport | null>(null);
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

  const activeConnection = connections.find(
    (connection) => connection.status === "ACTIVE" && connection.id === preferredConnectionId,
  ) ?? connections.find((connection) => connection.status === "ACTIVE") ?? null;
  const selectedRepository = repositories.find(
    (repository) => repository.externalRepositoryId === selectedRepositoryId,
  ) ?? null;
  const hasInactiveConnection = connections.some((connection) => connection.status !== "ACTIVE");
  const monitoredRepository = selectedRepositoryId === null
    ? repositories.find((repository) => repository.id) ?? null
    : selectedRepository;
  const latestRelease = monitoredRepository?.id === releaseActivity?.sourceScopeId ? releaseActivity : null;
  const latestReleaseError = releaseActivityError &&
    monitoredRepository?.id === releaseActivityError.sourceScopeId
    ? releaseActivityError.error
    : null;

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
          ?? null;
        setIsOwner(owner);
        setConnections(nextConnections);
        setRepositories([]);
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
  }, [preferredConnectionId, reloadNonce]);

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

  const importLast30Days = async () => {
    if (!activeConnection || !selectedRepository || actionRef.current) return;
    actionRef.current = "import";
    setAction("import");
    setMessage(null);
    setMessageRequestId(null);
    try {
      const connected = selectedRepository.id
        ? selectedRepository
        : await plotApiClient.connectGitHubRepository(
          activeConnection.id,
          selectedRepository.externalRepositoryId,
        );
      const sourceScopeId = connected.id;
      if (!sourceScopeId) throw new Error("GitHub repository was connected without a source scope");

      const to = new Date();
      const from = new Date(to.getTime() - 30 * 24 * 60 * 60 * 1000);
      const result = await plotApiClient.importGitHubRepository(sourceScopeId, {
        from: from.toISOString(),
        to: to.toISOString(),
      });
      setLastImport(result);
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

  return (
    <div className="h-screen overflow-y-auto bg-[#f8fafc] px-6 py-14 dark:bg-[#111113] lg:px-10">
      <div className="mx-auto max-w-4xl">
        <header className="max-w-2xl">
          <h1 className="font-serif text-[38px] font-normal leading-tight tracking-normal text-black/90 dark:text-white/92">
            Integrations
          </h1>
          <p className="mt-2 text-[15px] leading-6 text-black/50 dark:text-white/50">
            Connect tools to this workspace and turn their activity into reusable Writing Blocks.
          </p>
        </header>

        <section className="mt-9 rounded-[14px] border border-black/10 bg-white p-6 shadow-[0_1px_2px_rgb(15_23_42_/_0.03)] dark:border-white/10 dark:bg-white/[0.04] lg:p-7">
          <div className="flex items-start gap-4">
            <div className="flex size-10 shrink-0 items-center justify-center rounded-[10px] bg-black text-white dark:bg-white dark:text-black">
              <GitBranch className="size-5" />
            </div>
            <div className="min-w-0 flex-1">
              <h2 className="text-lg font-semibold text-black/85 dark:text-white/88">GitHub</h2>
              <p className="mt-1 text-sm leading-5 text-black/50 dark:text-white/50">
                Import merged pull requests from one repository for the previous 30 days.
              </p>
            </div>
            {!isLoading && (
              <ConnectionBadge
                status={!activeConnection
                  ? "disconnected"
                  : repositoryLoadFailed || connectionNeedsReconnect
                    ? "attention"
                    : "connected"}
              />
            )}
          </div>

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
          {lastImport && <ImportSummary result={lastImport} />}

          {isLoading ? (
            <Loading />
          ) : isOwner === null ? null : !isOwner ? (
            <NonOwnerState connected={Boolean(activeConnection) && !connectionNeedsReconnect} />
          ) : !activeConnection || connectionNeedsReconnect ? (
            <ConnectState
              reconnect={hasInactiveConnection || connectionNeedsReconnect}
              busy={action === "install"}
              onConnect={() => { void installGitHub(); }}
            />
          ) : (
            <div className="mt-6 border-t border-black/[0.08] pt-6 dark:border-white/10">
              <div className="flex items-center justify-between gap-4">
                <div>
                  <h3 className="text-sm font-semibold text-black/80 dark:text-white/82">Repository access</h3>
                  <p className="mt-1 text-sm text-black/48 dark:text-white/48">
                    Select one repository, then connect it and import the last 30 days.
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
                  className="mt-5 h-64 space-y-2 overflow-y-auto overscroll-contain pr-1"
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
                        <span className="text-xs text-emerald-700 dark:text-emerald-300">Connected</span>
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

              {repositories.length > 0 && (
                <button
                  type="button"
                  onClick={() => { void importLast30Days(); }}
                  disabled={!selectedRepository || action !== null}
                  className="mt-5 inline-flex items-center gap-2 rounded-full bg-black px-4 py-2 text-sm font-semibold text-white transition disabled:cursor-not-allowed disabled:opacity-40 dark:bg-white dark:text-black"
                >
                  {action === "import" && <LoaderCircle className="size-4 animate-spin" />}
                  {selectedRepository?.id ? "Import last 30 days" : "Connect and import last 30 days"}
                </button>
              )}

              {monitoredRepository?.id && latestRelease && (
                <LatestRelease
                  activity={latestRelease}
                  busy={releaseActionId === latestRelease.id || action === "install"}
                  onRetry={() => { void retryRelease(latestRelease); }}
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
        </section>
      </div>
    </div>
  );
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
  } else if (activity.status === "READY" && activity.contentPackId) {
    content = (
      <span>
        <span>Draft ready</span> ·{" "}
        <Link
          href={`/packs?pack=${encodeURIComponent(activity.contentPackId)}`}
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

function ConnectionBadge({ status }: { status: "connected" | "attention" | "disconnected" }) {
  const styles = status === "connected"
    ? "bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-300"
    : status === "attention"
      ? "bg-amber-50 text-amber-800 dark:bg-amber-500/10 dark:text-amber-200"
      : "bg-black/[0.05] text-black/45 dark:bg-white/10 dark:text-white/48";
  return (
    <span className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-medium ${styles}`}>
      {status === "connected" ? "Connected" : status === "attention" ? "Needs attention" : "Not connected"}
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

function ImportSummary({ result }: { result: GitHubImport }) {
  return (
    <div role="status" className="mt-5 rounded-[10px] border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900 dark:border-emerald-400/30 dark:bg-emerald-500/10 dark:text-emerald-100">
      Import complete: {result.blockCreatedCount} created, {result.blockUpdatedCount} updated, {result.blockUnchangedCount} unchanged, {result.eligibleCount} eligible.
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
    if (error.code === "IMPORT_ALREADY_RUNNING") return "An import is already running for this repository. Wait for it to finish, then retry.";
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
