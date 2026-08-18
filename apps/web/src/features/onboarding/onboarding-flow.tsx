"use client";

import { CheckmarkCircle02Icon, GithubIcon, Loading03Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

import { getSelectedWorkspaceId, plotApiClient, type GitHubConnection, type GitHubRepository } from "@/lib/api-client";

type RepositoryOption = { connectionId: string; repository: GitHubRepository };
type SetupState = "loading" | "ready" | "error";

const repositoryKey = ({ connectionId, repository }: RepositoryOption) => `${connectionId}:${repository.externalRepositoryId}`;

export function OnboardingFlow({ embedded = false, initialStep = 1, onComplete }: { embedded?: boolean; initialStep?: 1 | 2; onComplete?: () => void }) {
  const router = useRouter();
  const [step, setStep] = useState<1 | 2>(initialStep);
  const [connections, setConnections] = useState<GitHubConnection[]>([]);
  const [repositories, setRepositories] = useState<RepositoryOption[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [setupState, setSetupState] = useState<SetupState>("loading");
  const [busy, setBusy] = useState<"connect" | "enable" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const activeConnections = useMemo(() => connections.filter((item) => item.status === "ACTIVE"), [connections]);
  const activeKeys = useMemo(() => new Set(repositories.filter(({ repository }) => Boolean(repository.id) && repository.status === "ACTIVE").map(repositoryKey)), [repositories]);
  const hasConnection = activeConnections.length > 0;

  useEffect(() => {
    const controller = new AbortController();
    if (!getSelectedWorkspaceId()) {
      queueMicrotask(() => {
        if (!controller.signal.aborted) {
          setSetupState("error");
          setError("Select a workspace before setting up release monitoring.");
        }
      });
      return () => controller.abort();
    }

    void plotApiClient.listGitHubConnections({ signal: controller.signal })
      .then(async (nextConnections) => {
        if (controller.signal.aborted) return;
        setConnections(nextConnections);
        const active = nextConnections.filter((item) => item.status === "ACTIVE");
        const availableByConnection = await Promise.all(active.map(async (connection) => {
          const available = await plotApiClient.listGitHubRepositories(connection.id, { signal: controller.signal }).catch(() => []);
          const merged = new Map<number, GitHubRepository>();
          [...available, ...connection.repositories].forEach((repository) => merged.set(repository.externalRepositoryId, repository));
          return [...merged.values()].map((repository) => ({ connectionId: connection.id, repository }));
        }));
        if (controller.signal.aborted) return;
        const nextRepositories = availableByConnection.flat();
        const nextActiveKeys = nextRepositories.filter(({ repository }) => Boolean(repository.id) && repository.status === "ACTIVE").map(repositoryKey);
        setRepositories(nextRepositories);
        setSelectedKeys(nextActiveKeys.length ? nextActiveKeys : nextRepositories.length === 1 ? [repositoryKey(nextRepositories[0])] : []);
        setSetupState("ready");
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted) return;
        setSetupState("error");
        setError(failure instanceof Error ? failure.message : "GitHub connections could not be loaded.");
      });

    return () => controller.abort();
  }, []);

  async function connectGitHub() {
    if (busy) return;
    setBusy("connect");
    setError(null);
    try {
      const request = await plotApiClient.createGitHubInstallationRequest();
      window.location.assign(request.installUrl);
    } catch (failure) {
      setBusy(null);
      setError(failure instanceof Error ? failure.message : "GitHub connection could not start.");
    }
  }

  async function startMonitoring() {
    if (!selectedKeys.length || busy) return;
    setBusy("enable");
    setError(null);
    try {
      const selected = repositories.filter((repository) => selectedKeys.includes(repositoryKey(repository)) && !activeKeys.has(repositoryKey(repository)));
      await Promise.all(selected.map(async ({ connectionId, repository }) => {
        const connected = await plotApiClient.connectGitHubRepository(connectionId, repository.externalRepositoryId);
        if (!connected.id) throw new Error(`${repository.displayName} was connected without a source scope.`);
        const to = new Date();
        const from = new Date(to.getTime() - 30 * 24 * 60 * 60 * 1_000);
        await plotApiClient.importGitHubRepository(connected.id, { from: from.toISOString(), to: to.toISOString() }).catch(() => undefined);
      }));
      if (onComplete) onComplete();
      else router.replace("/chat");
    } catch (failure) {
      setBusy(null);
      setError(failure instanceof Error ? failure.message : "Repository setup could not finish.");
    }
  }

  function toggleRepository(key: string) {
    if (activeKeys.has(key)) return;
    setSelectedKeys((current) => current.includes(key) ? current.filter((item) => item !== key) : [...current, key]);
  }

  return (
    <div className={embedded ? "text-[#18181b] dark:text-white" : "min-h-full overflow-y-auto bg-[#f4f6f8] px-5 py-8 text-[#18181b] dark:bg-[#101112] dark:text-white sm:px-8 sm:py-10 lg:px-10"}>
      <div className={embedded ? "mx-auto max-w-[600px]" : "mx-auto max-w-[600px] pb-16 pt-10 sm:pt-16"}>
        <div className="flex items-center gap-1.5" aria-label={`Step ${step} of 2`} role="progressbar">
          {[1, 2].map((item) => <span key={item} className={`h-1.5 rounded-full transition-all ${item === step ? "w-7 bg-[#252a30] dark:bg-white" : item < step ? "w-2 bg-[#252a30]/60 dark:bg-white/60" : "w-2 bg-black/15 dark:bg-white/20"}`} />)}
        </div>
        {step === 1 ? (
          <ConnectStep connected={hasConnection} connectionCount={activeConnections.length} loading={setupState === "loading"} busy={busy === "connect"} error={error} onConnect={connectGitHub} onContinue={() => setStep(2)} />
        ) : (
          <RepositoryStep repositories={repositories} selectedKeys={selectedKeys} activeKeys={activeKeys} busy={busy === "enable"} error={error} onToggle={toggleRepository} onStart={startMonitoring} onBack={() => setStep(1)} />
        )}
      </div>
    </div>
  );
}

function ConnectStep({ connected, connectionCount, loading, busy, error, onConnect, onContinue }: { connected: boolean; connectionCount: number; loading: boolean; busy: boolean; error: string | null; onConnect: () => void; onContinue: () => void }) {
  return <section className="space-y-6">
    <header><h1 className="font-display text-[34px] font-normal leading-[1.08] tracking-[-0.03em] sm:text-[38px]">Set up your release sources</h1><p className="mt-3 text-[14px] leading-6 text-black/55 dark:text-white/52">Connect GitHub so Plot can monitor your product repositories and prepare changelogs from exact release boundaries.</p></header>
    <div className={`rounded-[12px] border bg-white p-5 shadow-[0_1px_2px_rgb(15_23_42_/_0.025)] dark:bg-white/[0.045] ${connected ? "border-emerald-500/45" : "border-black/[0.1] dark:border-white/12"}`}><div className="flex items-center justify-between gap-4"><div className="flex min-w-0 items-center gap-3"><div className="flex size-10 shrink-0 items-center justify-center rounded-[10px] bg-black/[0.05] dark:bg-white/[0.08]"><HugeiconsIcon icon={GithubIcon} size={21} strokeWidth={1.5} /></div><div className="min-w-0"><p className="text-[14px] font-semibold text-black/82 dark:text-white/88">GitHub App</p><p className="mt-1 text-[12px] text-black/45 dark:text-white/45">{loading ? "Checking your connections…" : connected ? `${connectionCount} GitHub account${connectionCount === 1 ? "" : "s"} connected.` : "Choose accounts and repository access on GitHub."}</p></div></div>{connected ? <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-emerald-500/10 px-2.5 py-1 text-xs font-medium text-emerald-700 dark:text-emerald-300"><HugeiconsIcon icon={CheckmarkCircle02Icon} size={14} strokeWidth={1.7} />Connected</span> : <button type="button" onClick={onConnect} disabled={busy || loading} className="inline-flex h-9 shrink-0 items-center gap-2 rounded-full bg-[#252a30] px-3.5 text-[12px] font-medium text-white disabled:opacity-50 dark:bg-white dark:text-[#18191b]"><HugeiconsIcon icon={GithubIcon} size={15} strokeWidth={1.6} />{busy ? "Redirecting…" : "Connect GitHub"}</button>}</div></div>
    <div><h2 className="text-[13px] font-semibold text-black/72 dark:text-white/75">Read-only permissions</h2><div className="mt-2 grid grid-cols-2 gap-x-5">{["Repository metadata", "Contents", "Pull requests", "Releases and tags"].map((permission) => <div key={permission} className="border-b border-black/[0.07] py-2.5 text-[12px] text-black/55 dark:border-white/[0.08] dark:text-white/52">{permission}</div>)}</div></div>
    <div className="rounded-[10px] border border-black/[0.08] bg-black/[0.025] px-4 py-3 text-[12px] leading-5 text-black/52 dark:border-white/10 dark:bg-white/[0.035] dark:text-white/52">Plot never publishes releases or writes to your repositories. Access can be changed from GitHub at any time.</div>
    {error && <p className="text-sm text-rose-700 dark:text-rose-300" role="alert">{error}</p>}
    <button type="button" onClick={onContinue} disabled={!connected || loading} className="inline-flex h-10 w-full items-center justify-center rounded-full bg-[#252a30] px-4 text-[13px] font-medium text-white disabled:cursor-not-allowed disabled:opacity-40 dark:bg-white dark:text-[#18191b]">Continue</button>
  </section>;
}

function RepositoryStep({ repositories, selectedKeys, activeKeys, busy, error, onToggle, onStart, onBack }: { repositories: RepositoryOption[]; selectedKeys: string[]; activeKeys: Set<string>; busy: boolean; error: string | null; onToggle: (key: string) => void; onStart: () => void; onBack: () => void }) {
  return <section className="space-y-6">
    <header><h1 className="font-display text-[34px] font-normal leading-[1.08] tracking-[-0.03em] sm:text-[38px]">Choose repositories to monitor</h1><p className="mt-3 text-[14px] leading-6 text-black/55 dark:text-white/52">Select every repository that publishes product releases. You can add or remove repositories later.</p></header>
    <div><div className="flex items-center justify-between"><h2 className="text-[13px] font-semibold text-black/72 dark:text-white/75">Repositories</h2><span className="text-xs text-black/40 dark:text-white/40">{selectedKeys.length} selected</span></div><div className="mt-2 max-h-[330px] overflow-y-auto rounded-[12px] border border-black/[0.09] bg-white dark:border-white/10 dark:bg-white/[0.045]">{repositories.length ? repositories.map((option) => { const key = repositoryKey(option); const active = activeKeys.has(key); const checked = selectedKeys.includes(key); return <label key={key} className="flex cursor-pointer items-center gap-3 border-b border-black/[0.07] px-4 py-3.5 last:border-b-0 dark:border-white/[0.08]"><input type="checkbox" checked={checked} disabled={active || busy} onChange={() => onToggle(key)} className="size-4 accent-[#252a30]" /><span className="min-w-0 flex-1 truncate text-[13px] font-medium text-black/75 dark:text-white/78">{option.repository.displayName}</span>{active && <span className="text-[11px] font-medium text-emerald-700 dark:text-emerald-300">Monitoring</span>}</label>; }) : <div className="px-4 py-8 text-center text-sm text-black/45 dark:text-white/45">No repositories are available from this GitHub installation.</div>}</div></div>
    <div className="rounded-[10px] border border-sky-500/20 bg-sky-500/[0.05] px-4 py-3 text-[12px] leading-5 text-sky-900/70 dark:text-sky-100/70"><strong className="font-semibold">Per-repository baselines.</strong> Each repository establishes its own trusted release boundary. A first release may set the baseline without producing a draft.</div>
    {error && <p className="text-sm text-rose-700 dark:text-rose-300" role="alert">{error}</p>}
    <div className="flex items-center gap-2"><button type="button" onClick={onStart} disabled={!selectedKeys.length || busy} className="inline-flex h-10 items-center gap-2 rounded-full bg-[#252a30] px-4 text-[13px] font-medium text-white disabled:cursor-wait disabled:opacity-40 dark:bg-white dark:text-[#18191b]">{busy && <HugeiconsIcon icon={Loading03Icon} size={15} className="animate-spin" strokeWidth={1.6} />}Start monitoring {selectedKeys.length ? `(${selectedKeys.length})` : ""}</button><button type="button" onClick={onBack} disabled={busy} className="inline-flex h-10 items-center rounded-full px-3.5 text-[13px] text-black/48 hover:bg-black/[0.04] disabled:opacity-40 dark:text-white/48 dark:hover:bg-white/[0.07]">Back</button><Link href="/chat" className="ml-auto text-[12px] text-black/40 underline-offset-4 hover:underline dark:text-white/40">Do this later</Link></div>
  </section>;
}
