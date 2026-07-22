"use client";

import { ExternalLink, GitBranch, LoaderCircle, RefreshCw, X } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

import { getSelectedWorkspaceId, plotApiClient, PlotApiError, type GitHubConnection, type GitHubImport, type GitHubRepository, type WritingBlock } from "@/lib/api-client";

type SourceItem = WritingBlock & { repository: string; sourceScopeId: string };

export function SourcesWorkspace() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const callbackConnectionId = searchParams.get("githubConnection");
  const callbackError = searchParams.get("githubError");
  const [connections, setConnections] = useState<GitHubConnection[]>([]);
  const [repositories, setRepositories] = useState<GitHubRepository[]>([]);
  const [sources, setSources] = useState<SourceItem[]>([]);
  const [selectedSourceId, setSelectedSourceId] = useState<string | null>(null);
  const [selectedRepositoryId, setSelectedRepositoryId] = useState<number | null>(null);
  const [isOwner, setIsOwner] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [action, setAction] = useState<"connect" | "import" | null>(null);
  const [message, setMessage] = useState<string | null>(callbackError ? callbackMessage(callbackError) : null);
  const [lastImport, setLastImport] = useState<GitHubImport | null>(null);
  const [reloadNonce, setReloadNonce] = useState(0);

  const activeConnection = useMemo(() => {
    const active = connections.filter((connection) => connection.status === "ACTIVE");
    return active.find((connection) => connection.id === callbackConnectionId) ?? active[0] ?? null;
  }, [callbackConnectionId, connections]);
  const connectedRepositories = useMemo(
    () => connections.flatMap((connection) => connection.repositories)
      .filter((repository): repository is GitHubRepository & { sourceScopeId: string } => repository.status === "ACTIVE" && Boolean(repository.sourceScopeId)),
    [connections],
  );
  const selectedSource = sources.find((source) => source.id === selectedSourceId) ?? null;

  const refresh = () => {
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
        const nextSources = await loadBlocks(nextConnections);
        const preferredConnection = nextConnections.find((connection) => connection.id === callbackConnectionId)
          ?? nextConnections.find((connection) => connection.status === "ACTIVE");
        const nextRepositories = workspace.role === "OWNER" && preferredConnection?.status === "ACTIVE"
          ? await plotApiClient.listGitHubRepositories(preferredConnection.id)
          : [];
        if (cancelled) return;
        setIsOwner(workspace.role === "OWNER");
        setConnections(nextConnections);
        setSources(nextSources);
        setRepositories(nextRepositories);
      } catch (error) {
        if (!cancelled) setMessage(errorMessage(error));
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }
    queueMicrotask(() => { void load(); });
    return () => { cancelled = true; };
  }, [callbackConnectionId, reloadNonce]);

  useEffect(() => {
    if (callbackConnectionId || callbackError) router.replace("/sources");
  }, [callbackConnectionId, callbackError, router]);

  const connectGitHub = async () => {
    setAction("connect");
    setMessage(null);
    try {
      const request = await plotApiClient.createGitHubInstallationRequest();
      window.location.assign(request.installUrl);
    } catch (error) {
      setMessage(errorMessage(error));
      setAction(null);
    }
  };

  const importLast30Days = async (repository: GitHubRepository) => {
    if (!activeConnection || action) return;
    setAction("import");
    setMessage(null);
    try {
      const connected = repository.sourceScopeId
        ? repository
        : await plotApiClient.connectGitHubRepository(activeConnection.id, repository.externalRepositoryId);
      const sourceScopeId = connected.sourceScopeId ?? connected.id;
      if (!sourceScopeId) throw new Error("GitHub repository was connected without a source scope");
      const to = new Date();
      const from = new Date(to.getTime() - 30 * 24 * 60 * 60 * 1000);
      const result = await plotApiClient.importGitHubRepository(sourceScopeId, { from: from.toISOString(), to: to.toISOString() });
      setLastImport(result);
      refresh();
    } catch (error) {
      setMessage(errorMessage(error));
    } finally {
      setAction(null);
    }
  };

  return (
    <div className="grid min-h-screen grid-cols-1 lg:h-screen lg:grid-cols-[380px_minmax(0,1fr)] lg:overflow-hidden">
      <section className="min-h-0 border-b border-black/10 bg-[#f6f7f9] px-6 pb-6 pt-14 dark:border-white/10 dark:bg-[#111113] lg:overflow-y-auto lg:border-r lg:border-b-0">
        <h1 className="font-serif text-[32px] font-normal leading-none tracking-normal text-black/90 dark:text-white/92">Sources</h1>
        <p className="mt-1 text-sm text-black/55 dark:text-white/55">Merged pull requests Plot can use as evidence.</p>

        {message && <StatusMessage message={message} onDismiss={() => setMessage(null)} />}
        {lastImport && <ImportSummary result={lastImport} />}

        {isLoading ? <Loading /> : !isOwner ? (
          <EmptyState title="GitHub is managed by the workspace owner" detail="Workspace owner must connect GitHub." />
        ) : !activeConnection ? (
          <GitHubConnect onConnect={connectGitHub} busy={action === "connect"} />
        ) : (
          <>
            <div className="mt-7 rounded-[12px] border border-black/10 bg-white/70 p-4 dark:border-white/10 dark:bg-white/5">
              <div className="flex items-center gap-2 font-medium text-black/80 dark:text-white/85"><GitBranch className="size-4" /> GitHub connected</div>
              <p className="mt-1 text-sm leading-5 text-black/50 dark:text-white/50">Choose one repository to import its merged pull requests from the last 30 days.</p>
              {repositories.length > 0 && <div className="mt-4 space-y-2" role="radiogroup" aria-label="Allowed GitHub repositories">
                {repositories.map((repository) => (
                  <label key={repository.externalRepositoryId} className="flex cursor-pointer items-center gap-3 rounded-lg border border-black/10 px-3 py-2.5 text-sm dark:border-white/10">
                    <input type="radio" name="repository" checked={selectedRepositoryId === repository.externalRepositoryId} onChange={() => {
                      setSelectedRepositoryId(repository.externalRepositoryId);
                      void importLast30Days(repository);
                    }} disabled={action !== null} />
                    <span className="min-w-0 flex-1 truncate font-medium">{repository.displayName}</span>
                    {repository.sourceScopeId && <span className="text-xs text-black/45 dark:text-white/45">Connected</span>}
                  </label>
                ))}
              </div>}
              {repositories.length === 0 && <p className="mt-4 text-sm text-black/55 dark:text-white/55">No repositories are currently granted. Update this GitHub App installation&apos;s repository access, then refresh.</p>}
              <button type="button" onClick={refresh} disabled={action !== null} className="mt-4 inline-flex items-center gap-2 text-sm font-medium text-black/65 hover:text-black disabled:opacity-50 dark:text-white/65 dark:hover:text-white"><RefreshCw className="size-3.5" /> Refresh</button>
            </div>
            {connectedRepositories.filter((repository) => !sources.some((source) => source.sourceScopeId === repository.sourceScopeId)).map((repository) => (
              <button key={repository.sourceScopeId} type="button" disabled={action !== null} onClick={() => void importLast30Days(repository)} className="mt-3 w-full rounded-[10px] border border-black/10 bg-white px-3 py-2.5 text-left text-sm font-medium text-black/70 hover:bg-black/[0.03] disabled:opacity-50 dark:border-white/10 dark:bg-white/5 dark:text-white/75">
                Import last 30 days from {repository.displayName}
              </button>
            ))}
          </>
        )}

        <div className="mt-7 space-y-2" role="listbox" aria-label="Writing Blocks">
          {sources.map((source) => <button key={source.id} type="button" role="option" aria-selected={source.id === selectedSourceId} onClick={() => setSelectedSourceId(source.id)} className={`w-full rounded-[12px] border px-4 py-3.5 text-left transition ${source.id === selectedSourceId ? "border-black/20 bg-white dark:border-white/20 dark:bg-white/10" : "border-black/10 bg-white/60 hover:bg-white dark:border-white/10 dark:bg-white/5 dark:hover:bg-white/10"}`}>
            <div className="font-medium text-black/82 dark:text-white/86">{source.title || "Untitled pull request"}</div>
            <div className="mt-1 text-sm text-black/55 dark:text-white/55">{source.repository}</div>
            <div className="mt-3 text-xs text-black/40 dark:text-white/40">{formatDate(source.sourceCreatedAt)}</div>
          </button>)}
          {!isLoading && activeConnection && sources.length === 0 && <div className="rounded-[12px] border border-black/10 bg-white/60 p-4 text-sm text-black/55 dark:border-white/10 dark:bg-white/5 dark:text-white/55">No eligible merged pull requests were found in the last 30 days.</div>}
        </div>
      </section>

      <section className="min-h-0 min-w-0 overflow-y-auto bg-[#f8fafc] px-6 py-10 dark:bg-[#18181b] lg:px-10">
        {selectedSource ? <article className="relative mx-auto max-w-3xl space-y-4 pt-10">
          <button type="button" onClick={() => setSelectedSourceId(null)} aria-label="Close source detail" className="absolute right-0 top-0 inline-flex size-8 items-center justify-center rounded-xl text-black/45 hover:bg-black/5 dark:text-white/45 dark:hover:bg-white/10"><X className="size-4" /></button>
          <div className="rounded-[12px] border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/5">
            <div className="text-xs font-medium text-black/40 dark:text-white/40">Pull request · {selectedSource.repository}</div>
            <h2 className="mt-2 text-[28px] font-semibold leading-tight text-black/88 dark:text-white/90">{selectedSource.title || "Untitled pull request"}</h2>
            <p className="mt-1 text-sm text-black/55 dark:text-white/55">{formatDate(selectedSource.sourceCreatedAt)}</p>
            {selectedSource.body && <p className="mt-5 whitespace-pre-wrap text-sm leading-6 text-black/70 dark:text-white/70">{selectedSource.body}</p>}
            {(selectedSource.canonicalUrl || selectedSource.url) && <a href={selectedSource.canonicalUrl || selectedSource.url || undefined} target="_blank" rel="noreferrer" className="mt-5 inline-flex items-center gap-2 text-sm font-medium text-black/70 underline underline-offset-4 dark:text-white/75">View on GitHub <ExternalLink className="size-3.5" /></a>}
          </div>
        </article> : <div className="flex h-full items-center justify-center"><div className="max-w-[300px] rounded-[12px] border border-dashed border-black/10 bg-white/45 p-5 text-sm leading-6 text-black/45 dark:border-white/10 dark:bg-white/[0.03] dark:text-white/45">Select a Writing Block to inspect its original pull request.</div></div>}
      </section>
    </div>
  );
}

function GitHubConnect({ onConnect, busy }: { onConnect: () => void; busy: boolean }) { return <div className="mt-7 rounded-[12px] border border-black/10 bg-white/70 p-5 dark:border-white/10 dark:bg-white/5"><GitBranch className="size-5" /><h2 className="mt-3 text-lg font-semibold">Connect GitHub</h2><p className="mt-2 text-sm leading-5 text-black/55 dark:text-white/55">Plot requests read-only repository metadata and pull requests. Select a repository after installation to import its merged PRs.</p><button type="button" onClick={onConnect} disabled={busy} className="mt-5 inline-flex items-center gap-2 rounded-full bg-black px-4 py-2 text-sm font-semibold text-white disabled:opacity-50 dark:bg-white dark:text-black">{busy && <LoaderCircle className="size-4 animate-spin" />} Connect GitHub</button></div>; }
async function loadBlocks(connections: GitHubConnection[]): Promise<SourceItem[]> { const scopes = connections.flatMap((connection) => connection.repositories).filter((repository): repository is GitHubRepository & { sourceScopeId: string } => repository.status === "ACTIVE" && Boolean(repository.sourceScopeId)); const pages = await Promise.all(scopes.map(async (repository) => { const first = await plotApiClient.listWritingBlocks(repository.sourceScopeId); const rest = await Promise.all(Array.from({ length: Math.max(0, first.totalPages - 1) }, (_, index) => plotApiClient.listWritingBlocks(repository.sourceScopeId, index + 1))); return [first, ...rest].flatMap((page) => page.items).map((item) => ({ ...item, repository: repository.displayName, sourceScopeId: repository.sourceScopeId })); })); return pages.flat().filter((item) => item.status === "ACTIVE"); }
function EmptyState({ title, detail }: { title: string; detail: string }) { return <div className="mt-7 rounded-[12px] border border-black/10 bg-white/60 p-5 text-sm dark:border-white/10 dark:bg-white/5"><div className="font-medium text-black/80 dark:text-white/85">{title}</div><p className="mt-1 text-black/55 dark:text-white/55">{detail}</p></div>; }
function Loading() { return <div className="mt-7 flex items-center gap-2 text-sm text-black/55 dark:text-white/55"><LoaderCircle className="size-4 animate-spin" /> Loading sources…</div>; }
function StatusMessage({ message, onDismiss }: { message: string; onDismiss: () => void }) { return <div className="mt-5 flex gap-3 rounded-[10px] border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-400/30 dark:bg-amber-500/10 dark:text-amber-100"><span className="flex-1">{message}</span><button type="button" onClick={onDismiss} aria-label="Dismiss message"><X className="size-4" /></button></div>; }
function ImportSummary({ result }: { result: GitHubImport }) { return <div className="mt-5 rounded-[10px] border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900 dark:border-emerald-400/30 dark:bg-emerald-500/10 dark:text-emerald-100">Import complete: {result.blockCreatedCount} created, {result.blockUpdatedCount} updated, {result.blockUnchangedCount} unchanged, {result.eligibleCount} eligible.</div>; }
function formatDate(value: string | null) { return value ? new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(new Date(value)) : "No date"; }
function callbackMessage(value: string) { return value === "invalid" ? "The GitHub installation link expired. Try connecting again." : value === "unauthorized" ? "Only the workspace owner can connect GitHub." : value === "unavailable" ? "GitHub is temporarily unavailable. Try again shortly." : "GitHub could not be connected. Try again."; }
function errorMessage(error: unknown) { if (error instanceof PlotApiError) { if (error.code === "GITHUB_NOT_CONFIGURED") return "GitHub is not configured for this environment."; if (error.code === "GITHUB_RATE_LIMITED") return "GitHub rate limit reached. Try again later."; if (error.code === "GITHUB_ACCESS_DENIED" || error.code === "CONNECTION_INACTIVE") return "GitHub access was revoked. Reconnect GitHub and try again."; if (error.code === "FORBIDDEN") return "Workspace owner must connect GitHub."; if (error.code === "IMPORT_ALREADY_RUNNING") return "An import is already running for this repository."; return error.message; } return "Could not load GitHub sources. Try again."; }
