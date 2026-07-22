"use client";

import { ExternalLink, GitBranch, LoaderCircle, RefreshCw, X } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";

import {
  getSelectedWorkspaceId,
  plotApiClient,
  PlotApiError,
  type GitHubConnection,
  type GitHubImport,
  type GitHubRepository,
} from "@/lib/api-client";

type IntegrationAction = "install" | "import" | null;

type IntegrationsWorkspaceProps = {
  navigateToInstall?: (url: string) => void;
};

export function IntegrationsWorkspace({
  navigateToInstall = (url) => window.location.assign(url),
}: IntegrationsWorkspaceProps = {}) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const callbackConnectionId = searchParams.get("githubConnection");
  const callbackError = searchParams.get("githubError");
  const [preferredConnectionId] = useState(callbackConnectionId);
  const [connections, setConnections] = useState<GitHubConnection[]>([]);
  const [repositories, setRepositories] = useState<GitHubRepository[]>([]);
  const [selectedRepositoryId, setSelectedRepositoryId] = useState<number | null>(null);
  const [isOwner, setIsOwner] = useState<boolean | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [action, setAction] = useState<IntegrationAction>(null);
  const actionRef = useRef<IntegrationAction>(null);
  const [message, setMessage] = useState<string | null>(callbackError ? callbackMessage(callbackError) : null);
  const [lastImport, setLastImport] = useState<GitHubImport | null>(null);
  const [reloadNonce, setReloadNonce] = useState(0);

  const activeConnection = useMemo(() => {
    const active = connections.filter((connection) => connection.status === "ACTIVE");
    return active.find((connection) => connection.id === preferredConnectionId) ?? active[0] ?? null;
  }, [connections, preferredConnectionId]);
  const selectedRepository = repositories.find(
    (repository) => repository.externalRepositoryId === selectedRepositoryId,
  ) ?? null;
  const hasInactiveConnection = connections.some((connection) => connection.status !== "ACTIVE");

  const refresh = () => {
    setMessage(null);
    setIsLoading(true);
    setReloadNonce((value) => value + 1);
  };

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const [workspace, nextConnections] = await Promise.all([
          plotApiClient.getWorkspace(getSelectedWorkspaceId()),
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

        if (owner && preferredConnection?.status === "ACTIVE") {
          const nextRepositories = await plotApiClient.listGitHubRepositories(preferredConnection.id);
          if (cancelled) return;
          setRepositories(nextRepositories);
          setSelectedRepositoryId((current) => (
            nextRepositories.some((repository) => repository.externalRepositoryId === current) ? current : null
          ));
        }
      } catch (error) {
        if (!cancelled) setMessage(errorMessage(error));
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

  const installGitHub = async () => {
    if (actionRef.current) return;
    actionRef.current = "install";
    setAction("install");
    setMessage(null);
    try {
      const request = await plotApiClient.createGitHubInstallationRequest();
      navigateToInstall(request.installUrl);
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
    try {
      const connected = selectedRepository.sourceScopeId
        ? selectedRepository
        : await plotApiClient.connectGitHubRepository(
          activeConnection.id,
          selectedRepository.externalRepositoryId,
        );
      const sourceScopeId = connected.sourceScopeId ?? connected.id;
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
              <ConnectionBadge connected={Boolean(activeConnection)} />
            )}
          </div>

          {message && <StatusMessage message={message} onRetry={refresh} onDismiss={() => setMessage(null)} />}
          {lastImport && <ImportSummary result={lastImport} />}

          {isLoading ? (
            <Loading />
          ) : isOwner === null ? (
            <UnavailableState />
          ) : !isOwner ? (
            <NonOwnerState connected={Boolean(activeConnection)} />
          ) : !activeConnection ? (
            <ConnectState
              reconnect={hasInactiveConnection}
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
                <div className="mt-5 space-y-2" role="radiogroup" aria-label="Allowed GitHub repositories">
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
                      {repository.sourceScopeId && (
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
                  {selectedRepository?.sourceScopeId ? "Import last 30 days" : "Connect and import last 30 days"}
                </button>
              )}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

function ConnectionBadge({ connected }: { connected: boolean }) {
  return (
    <span className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-medium ${connected ? "bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-300" : "bg-black/[0.05] text-black/45 dark:bg-white/10 dark:text-white/48"}`}>
      {connected ? "Connected" : "Not connected"}
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

function UnavailableState() {
  return (
    <div className="mt-6 border-t border-black/[0.08] pt-6 text-sm text-black/50 dark:border-white/10 dark:text-white/50">
      GitHub integration state is unavailable. Retry to load it again.
    </div>
  );
}

function StatusMessage({ message, onRetry, onDismiss }: { message: string; onRetry: () => void; onDismiss: () => void }) {
  return (
    <div role="alert" className="mt-5 flex items-start gap-3 rounded-[10px] border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-400/30 dark:bg-amber-500/10 dark:text-amber-100">
      <span className="min-w-0 flex-1">{message}</span>
      <button type="button" onClick={onRetry} className="shrink-0 font-semibold underline underline-offset-2">Retry</button>
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
    if (error.code === "GITHUB_ACCESS_DENIED" || error.code === "CONNECTION_INACTIVE" || error.code === "REPOSITORY_INACTIVE") return "GitHub access was revoked. Reconnect GitHub, then retry.";
    if (error.code === "FORBIDDEN") return "Workspace owner must connect GitHub.";
    if (error.code === "IMPORT_ALREADY_RUNNING") return "An import is already running for this repository. Wait for it to finish, then retry.";
    if (error.code === "GITHUB_PROVIDER_UNAVAILABLE") return "GitHub is temporarily unavailable. Try again shortly.";
    return error.message;
  }
  return "Could not update the GitHub integration. Try again.";
}
