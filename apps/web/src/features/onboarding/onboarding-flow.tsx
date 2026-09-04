"use client";

import { CheckmarkCircle02Icon, GithubIcon, Loading03Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import Link from "next/link";
import { LockKeyhole, Search } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { getSelectedWorkspaceId, plotApiClient, PlotApiError, type GitHubConnection, type GitHubRepository, type Routine } from "@/lib/api-client";

type RepositoryOption = { connectionId: string; repository: GitHubRepository };
type SetupState = "loading" | "ready" | "error";
type Step = 1 | 2 | 3;

const repositoryKey = ({ connectionId, repository }: RepositoryOption) => `${connectionId}:${repository.externalRepositoryId}`;
const defaultInstruction = "Create a concise, customer-facing changelog from this release with citations.";

export function OnboardingFlow({ embedded = false, initialStep = 1, onComplete, onResultReview }: { embedded?: boolean; initialStep?: Step; onComplete?: () => void; onResultReview?: () => void }) {
  const [step, setStep] = useState<Step>(initialStep);
  const [connections, setConnections] = useState<GitHubConnection[]>([]);
  const [repositories, setRepositories] = useState<RepositoryOption[]>([]);
  const [routines, setRoutines] = useState<Routine[]>([]);
  const [activeRoutineId, setActiveRoutineId] = useState<string | null>(null);
  const [selectedKey, setSelectedKey] = useState("");
  const [name, setName] = useState("Release changelog");
  const [instruction, setInstruction] = useState(defaultInstruction);
  const [setupState, setSetupState] = useState<SetupState>("loading");
  const [busy, setBusy] = useState<"connect" | "routine" | "run" | "poll" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const activeConnections = useMemo(() => connections.filter((item) => item.status === "ACTIVE"), [connections]);
  const hasConnection = activeConnections.length > 0;
  const selectedRepository = repositories.find((repository) => repositoryKey(repository) === selectedKey) ?? null;
  const routine = routines.find((item) => item.id === activeRoutineId) ?? routines.find((item) => item.cadence === "ON_GITHUB_RELEASE") ?? null;
  const routineId = routine?.id;

  useEffect(() => {
    const controller = new AbortController();
    if (!getSelectedWorkspaceId()) {
      queueMicrotask(() => {
        if (!controller.signal.aborted) {
          setSetupState("error");
          setError("Select a workspace before setting up Plot.");
        }
      });
      return () => controller.abort();
    }

    void Promise.all([
      plotApiClient.listGitHubConnections({ signal: controller.signal }),
      plotApiClient.listRoutines({ signal: controller.signal }),
    ]).then(async ([nextConnections, nextRoutines]) => {
      if (controller.signal.aborted) return;
      setConnections(nextConnections);
      setRoutines(nextRoutines);
      const releaseRoutine = nextRoutines.find((item) => item.cadence === "ON_GITHUB_RELEASE") ?? null;
      setActiveRoutineId(releaseRoutine?.id ?? null);
      const active = nextConnections.filter((item) => item.status === "ACTIVE");
      if (!embedded && initialStep === 1 && releaseRoutine) setStep(3);
      else if (!embedded && initialStep === 1 && active.length) setStep(2);
      const availableByConnection = await Promise.all(active.map(async (connection) => {
        const available = await plotApiClient.listGitHubRepositories(connection.id, { signal: controller.signal }).catch(() => []);
        const merged = new Map<number, GitHubRepository>();
        [...connection.repositories, ...available].forEach((repository) => merged.set(repository.externalRepositoryId, repository));
        return [...merged.values()].map((repository) => ({ connectionId: connection.id, repository }));
      }));
      if (controller.signal.aborted) return;
      const nextRepositories = availableByConnection.flat();
      setRepositories(nextRepositories);
      setSelectedKey(nextRepositories[0] ? repositoryKey(nextRepositories[0]) : "");
      setSetupState("ready");
    }).catch((failure: unknown) => {
      if (controller.signal.aborted) return;
      setSetupState("error");
      setError(failure instanceof Error ? failure.message : "Onboarding status could not be loaded.");
    });

    return () => controller.abort();
  }, [embedded, initialStep]);

  useEffect(() => {
    if (busy !== "poll" || !routineId) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function refreshRun() {
      try {
        const updated = await plotApiClient.getRoutine(routineId!, { signal: controller.signal });
        if (controller.signal.aborted) return;
        setRoutines([updated]);
        if (routineRunFinished(updated)) {
          setBusy(null);
          if (updated.latestExecution?.chatId) window.dispatchEvent(new Event("plot:sessions-changed"));
          return;
        }
        timer = setTimeout(refreshRun, 1_500);
      } catch {
        if (!controller.signal.aborted) {
          setBusy(null);
          setError("Routine status could not be refreshed.");
        }
      }
    }
    timer = setTimeout(refreshRun, 1_500);
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [busy, routineId]);

  async function connectGitHub() {
    if (busy) return;
    setBusy("connect");
    setError(null);
    try {
      let synced = false;
      try {
        await plotApiClient.syncGitHubInstallation();
        synced = true;
      } catch (failure) {
        if (!(failure instanceof PlotApiError && failure.code === "GITHUB_INSTALLATION_NOT_FOUND")) {
          throw failure;
        }
      }
      if (synced) {
        const nextConnections = await plotApiClient.listGitHubConnections();
        setConnections(nextConnections);
        const active = nextConnections.filter((item) => item.status === "ACTIVE");
        const availableByConnection = await Promise.all(active.map(async (connection) => {
          const available = await plotApiClient.listGitHubRepositories(connection.id).catch(() => []);
          const merged = new Map<number, GitHubRepository>();
          [...connection.repositories, ...available].forEach((repository) => merged.set(repository.externalRepositoryId, repository));
          return [...merged.values()].map((repository) => ({ connectionId: connection.id, repository }));
        }));
        const nextRepositories = availableByConnection.flat();
        setRepositories(nextRepositories);
        setSelectedKey(nextRepositories[0] ? repositoryKey(nextRepositories[0]) : "");
        setStep(2);
        setBusy(null);
        return;
      }
      const request = await plotApiClient.createGitHubInstallationRequest();
      window.location.assign(request.installUrl);
    } catch (failure) {
      setBusy(null);
      setError(failure instanceof Error ? failure.message : "GitHub connection could not start.");
    }
  }

  async function createFirstRoutine() {
    if (!selectedRepository || !name.trim() || !instruction.trim() || busy) return;
    setBusy("routine");
    setError(null);
    try {
      let sourceScopeId = selectedRepository.repository.id;
      if (!sourceScopeId || selectedRepository.repository.status !== "ACTIVE") {
        const connected = await plotApiClient.connectGitHubRepository(selectedRepository.connectionId, selectedRepository.repository.externalRepositoryId);
        if (!connected.id) throw new Error(`${selectedRepository.repository.displayName} could not be prepared.`);
        sourceScopeId = connected.id;
      }
      const created = await plotApiClient.createRoutine({
        name: name.trim(),
        sourceScopeId,
        instruction: instruction.trim(),
        cadence: "ON_GITHUB_RELEASE",
      });
      setRoutines([created]);
      setActiveRoutineId(created.id);
      setStep(3);
      setBusy(null);
    } catch (failure) {
      setBusy(null);
      setError(failure instanceof Error ? failure.message : "Routine could not be created.");
    }
  }

  async function runFirstRoutine() {
    if (!routine || busy) return;
    setBusy("run");
    setError(null);
    try {
      const to = new Date();
      const from = new Date(to.getTime() - 366 * 24 * 60 * 60 * 1_000);
      await plotApiClient.importGitHubRepository(routine.sourceScopeId, { from: from.toISOString(), to: to.toISOString() });
      const updated = await plotApiClient.runRoutineNow(routine.id, crypto.randomUUID());
      setRoutines([updated]);
      setBusy(routineRunFinished(updated) ? null : "poll");
      if (updated.latestExecution?.chatId) window.dispatchEvent(new Event("plot:sessions-changed"));
    } catch (failure) {
      setBusy(null);
      setError(failure instanceof Error ? failure.message : "Routine could not run.");
    }
  }

  return <div className={embedded ? "text-[#18181b] dark:text-white" : "min-h-full overflow-y-auto bg-[#f4f6f8] px-5 py-8 text-[#18181b] dark:bg-[#101112] dark:text-white sm:px-8 sm:py-10 lg:px-10"}><div className={embedded ? "mx-auto max-w-[600px]" : "mx-auto max-w-[600px] pb-16 pt-10 sm:pt-16"}>
    <div className="flex items-center gap-1.5" aria-label={`Step ${step} of 3`} role="progressbar">{[1, 2, 3].map((item) => <span key={item} className={`h-1.5 rounded-full transition-all ${item === step ? "w-7 bg-[#252a30] dark:bg-white" : item < step ? "w-2 bg-[#252a30]/60 dark:bg-white/60" : "w-2 bg-black/15 dark:bg-white/20"}`} />)}</div>
    {step === 1 ? <ConnectStep connected={hasConnection} connectionCount={activeConnections.length} loading={setupState === "loading"} busy={busy === "connect"} error={error} onConnect={connectGitHub} onContinue={() => setStep(2)} /> : step === 2 ? <RoutineStep existingRoutine={routine} repositories={repositories} selectedKey={selectedKey} name={name} instruction={instruction} loading={setupState === "loading"} busy={busy === "routine"} error={error} onRepositoryChange={setSelectedKey} onNameChange={setName} onInstructionChange={setInstruction} onCreate={createFirstRoutine} onContinue={() => setStep(3)} onBack={() => setStep(1)} onSkip={onComplete} /> : <ResultStep routine={routine} running={busy === "run" || busy === "poll"} error={error} onRun={runFirstRoutine} onReview={onResultReview} onDismiss={onComplete} />}
  </div></div>;
}

function ConnectStep({ connected, connectionCount, loading, busy, error, onConnect, onContinue }: { connected: boolean; connectionCount: number; loading: boolean; busy: boolean; error: string | null; onConnect: () => void; onContinue: () => void }) {
  return <section className="space-y-6"><header><h1 className="font-display text-[34px] font-normal leading-[1.08] tracking-[-0.03em] sm:text-[38px]">Install the Plot GitHub App</h1><p className="mt-3 text-[14px] leading-6 text-black/55 dark:text-white/52">Choose repository access on GitHub. Plot only requests the read permissions needed to create source-backed results.</p></header><div className={`rounded-[12px] border bg-white p-5 shadow-[0_1px_2px_rgb(15_23_42_/_0.025)] dark:bg-white/[0.045] ${connected ? "border-black/20 dark:border-white/20" : "border-black/[0.1] dark:border-white/12"}`}><div className="flex items-center justify-between gap-4"><div className="flex min-w-0 items-center gap-3"><div className="flex size-10 shrink-0 items-center justify-center rounded-[10px] bg-black/[0.05] dark:bg-white/[0.08]"><HugeiconsIcon icon={GithubIcon} size={21} strokeWidth={1.5} /></div><div className="min-w-0"><p className="text-[14px] font-semibold text-black/82 dark:text-white/88">GitHub App</p><p className="mt-1 text-[12px] text-black/45 dark:text-white/45">{loading ? "Checking your connections…" : connected ? `${connectionCount} GitHub account${connectionCount === 1 ? "" : "s"} connected.` : "Choose an account and repository access on GitHub."}</p></div></div>{connected ? <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-black/[0.06] px-2.5 py-1 text-xs font-medium text-black/65 dark:bg-white/[0.09] dark:text-white/70"><HugeiconsIcon icon={CheckmarkCircle02Icon} size={14} strokeWidth={1.7} />Connected</span> : <button type="button" onClick={onConnect} disabled={busy || loading} className="inline-flex h-9 shrink-0 items-center gap-2 rounded-full bg-[#252a30] px-3.5 text-[12px] font-medium text-white disabled:opacity-50 dark:bg-white dark:text-[#18191b]"><HugeiconsIcon icon={GithubIcon} size={15} strokeWidth={1.6} />{busy ? "Redirecting…" : "Install on GitHub"}</button>}</div></div><div><h2 className="text-[13px] font-semibold text-black/72 dark:text-white/75">Read-only permissions</h2><div className="mt-2 grid grid-cols-2 gap-x-5">{["Repository metadata", "Contents", "Pull requests", "Releases and tags"].map((permission) => <div key={permission} className="border-b border-black/[0.07] py-2.5 text-[12px] text-black/55 dark:border-white/[0.08] dark:text-white/52">{permission}</div>)}</div></div>{error && <p className="text-sm text-rose-700 dark:text-rose-300" role="alert">{error}</p>}<button type="button" onClick={onContinue} disabled={!connected || loading} className="inline-flex h-10 w-full items-center justify-center rounded-full bg-[#252a30] px-4 text-[13px] font-medium text-white disabled:opacity-40 dark:bg-white dark:text-[#18191b]">Continue</button></section>;
}

function RoutineStep({ existingRoutine, repositories, selectedKey, name, instruction, loading, busy, error, onRepositoryChange, onNameChange, onInstructionChange, onCreate, onContinue, onBack, onSkip }: { existingRoutine: Routine | null; repositories: RepositoryOption[]; selectedKey: string; name: string; instruction: string; loading: boolean; busy: boolean; error: string | null; onRepositoryChange: (value: string) => void; onNameChange: (value: string) => void; onInstructionChange: (value: string) => void; onCreate: () => void; onContinue: () => void; onBack: () => void; onSkip?: () => void }) {
  const [query, setQuery] = useState("");
  const filteredRepositories = repositories.filter(({ repository }) => repository.displayName.toLowerCase().includes(query.trim().toLowerCase()));
  if (existingRoutine) return <CompletedRoutineStep routine={existingRoutine} onContinue={onContinue} onBack={onBack} onDismiss={onSkip} />;
  return <section className="space-y-5"><header><h1 className="font-display text-[34px] font-normal leading-[1.08] tracking-[-0.03em] sm:text-[38px]">Create your first Routine</h1><p className="mt-3 text-[14px] leading-6 text-black/55 dark:text-white/52">Tell Plot which release to watch and what result to create.</p></header><fieldset><legend className="text-[13px] font-semibold text-black/72 dark:text-white/75">Source repository</legend><div className="mt-2 overflow-hidden rounded-[13px] border border-white/75 bg-white/58 shadow-[0_10px_30px_rgb(30_38_52_/_0.08),inset_0_1px_0_rgb(255_255_255_/_0.9)] backdrop-blur-2xl dark:border-white/12 dark:bg-white/[0.055] dark:shadow-[0_12px_32px_rgb(0_0_0_/_0.22),inset_0_1px_0_rgb(255_255_255_/_0.08)]"><div className="relative border-b border-black/[0.06] p-2 dark:border-white/[0.08]"><Search aria-hidden="true" className="pointer-events-none absolute left-5 top-1/2 size-3.5 -translate-y-1/2 text-black/30 dark:text-white/32" strokeWidth={1.7} /><input type="search" value={query} onChange={(event) => setQuery(event.target.value)} aria-label="Search repositories" placeholder="Search repositories" className="h-9 w-full rounded-[9px] border border-white/80 bg-white/52 pl-8 pr-3 text-[13px] text-black/78 outline-none shadow-[inset_0_1px_2px_rgb(15_23_42_/_0.055),0_1px_0_rgb(255_255_255_/_0.75)] placeholder:text-black/32 focus:border-black/15 focus:bg-white/72 dark:border-white/10 dark:bg-white/[0.045] dark:text-white/82 dark:placeholder:text-white/30 dark:focus:border-white/18 dark:focus:bg-white/[0.07]" /></div><div className="max-h-[190px] overflow-y-auto">{loading ? <div className="px-4 py-7 text-center text-sm text-black/45 dark:text-white/45">Loading repositories…</div> : repositories.length && filteredRepositories.length ? filteredRepositories.map((option) => { const key = repositoryKey(option); return <label key={key} className="flex cursor-pointer items-center gap-3 border-b border-black/[0.06] px-4 py-3 last:border-b-0 hover:bg-white/48 dark:border-white/[0.07] dark:hover:bg-white/[0.045]"><input type="radio" name="routine-repository" checked={selectedKey === key} onChange={() => onRepositoryChange(key)} className="size-4 accent-[#252a30]" /><span className="flex min-w-0 flex-1 items-center gap-1.5"><span className="truncate text-[13px] font-medium text-black/75 dark:text-white/78">{option.repository.displayName}</span>{option.repository.visibility === "PRIVATE" && <LockKeyhole aria-label="Private repository" className="size-3 shrink-0 text-black/35 dark:text-white/38" strokeWidth={1.6} />}</span></label>; }) : <div className="px-4 py-7 text-center text-sm text-black/45 dark:text-white/45">{repositories.length ? "No repositories found." : "No repositories are available from this GitHub installation."}</div>}</div></div></fieldset><label className="block"><span className="text-[13px] font-semibold text-black/72 dark:text-white/75">Routine name</span><input value={name} onChange={(event) => onNameChange(event.target.value)} maxLength={80} className="mt-2 h-10 w-full rounded-[9px] border border-black/10 bg-white px-3 text-sm text-black/80 outline-none focus:border-black/25 dark:border-white/12 dark:bg-white/[0.06] dark:text-white/85" /></label><label className="block"><span className="text-[13px] font-semibold text-black/72 dark:text-white/75">Draft instruction</span><textarea value={instruction} onChange={(event) => onInstructionChange(event.target.value)} maxLength={2_000} rows={3} className="mt-2 w-full resize-y rounded-[9px] border border-black/10 bg-white px-3 py-2.5 text-sm leading-5 text-black/80 outline-none focus:border-black/25 dark:border-white/12 dark:bg-white/[0.06] dark:text-white/85" /></label><div className="flex items-center justify-between rounded-[10px] border border-black/[0.08] bg-black/[0.025] px-4 py-3 text-[12px] dark:border-white/10 dark:bg-white/[0.035]"><span className="text-black/52 dark:text-white/52">Trigger</span><strong className="font-medium text-black/72 dark:text-white/72">Release published</strong></div>{error && <p className="text-sm text-rose-700 dark:text-rose-300" role="alert">{error}</p>}<div className="flex items-center gap-2"><button type="button" onClick={onCreate} disabled={!selectedKey || !name.trim() || !instruction.trim() || busy} className="inline-flex h-10 items-center gap-2 rounded-full bg-[#252a30] px-4 text-[13px] font-medium text-white disabled:opacity-40 dark:bg-white dark:text-[#18191b]">{busy && <HugeiconsIcon icon={Loading03Icon} size={15} className="animate-spin" strokeWidth={1.6} />}Create Routine</button><button type="button" onClick={onBack} disabled={busy} className="inline-flex h-10 items-center rounded-full px-3.5 text-[13px] text-black/48 hover:bg-black/[0.04] dark:text-white/48 dark:hover:bg-white/[0.07]">Back</button><Link href="/chat" onClick={onSkip} className="ml-auto text-[12px] text-black/40 underline-offset-4 hover:underline dark:text-white/40">Do this later</Link></div></section>;
}

function CompletedRoutineStep({ routine, onContinue, onBack, onDismiss }: { routine: Routine; onContinue: () => void; onBack: () => void; onDismiss?: () => void }) {
  return <section className="space-y-6"><header><h1 className="font-display text-[34px] font-normal leading-[1.08] tracking-[-0.03em] sm:text-[38px]">Your first Routine</h1><p className="mt-3 text-[14px] leading-6 text-black/52 dark:text-white/50">Plot will run this Routine whenever the repository publishes a release.</p></header><dl className="border-y border-black/[0.075] text-[13px] dark:border-white/[0.09]"><div className="grid grid-cols-[96px_1fr] gap-4 border-b border-black/[0.06] py-3 dark:border-white/[0.07]"><dt className="text-black/38 dark:text-white/38">Routine</dt><dd className="font-medium text-black/76 dark:text-white/78">{routine.name}</dd></div><div className="grid grid-cols-[96px_1fr] gap-4 border-b border-black/[0.06] py-3 dark:border-white/[0.07]"><dt className="text-black/38 dark:text-white/38">Repository</dt><dd className="text-black/68 dark:text-white/70">{routine.sourceLabel}</dd></div><div className="grid grid-cols-[96px_1fr] gap-4 py-3"><dt className="text-black/38 dark:text-white/38">Trigger</dt><dd className="text-black/68 dark:text-white/70">Release published</dd></div></dl><div className="flex items-center gap-2"><button type="button" onClick={onContinue} className="inline-flex h-10 items-center rounded-full bg-[#252a30] px-4 text-[13px] font-medium text-white transition hover:-translate-y-px hover:bg-[#16191d] active:translate-y-0 dark:bg-white dark:text-[#18191b]">Continue</button><button type="button" onClick={onBack} className="inline-flex h-10 items-center rounded-full px-3.5 text-[13px] text-black/44 transition hover:bg-white/50 hover:text-black/68 dark:text-white/45 dark:hover:bg-white/[0.07] dark:hover:text-white/70">Back</button><Link href="/routines" onClick={onDismiss} className="ml-auto text-[12px] text-black/40 underline-offset-4 hover:underline dark:text-white/40">View Routines</Link></div></section>;
}

function ResultStep({ routine, running, error, onRun, onReview, onDismiss }: { routine: Routine | null; running: boolean; error: string | null; onRun: () => void; onReview?: () => void; onDismiss?: () => void }) {
  const execution = routine?.latestExecution;
  const artifactId = execution?.artifactId;
  const noActivity = execution?.status === "NO_ACTIVITY";
  const failed = execution?.status === "FAILED" || execution?.agentRunStatus === "FAILED";
  const title = artifactId ? "Your first result is ready" : running ? "Creating your first result" : noActivity ? "Your Routine is ready" : failed ? "The first run did not finish" : "Run your first Routine";
  const description = artifactId ? "Review the source-backed artifact Plot created." : running ? "Plot is importing repository activity and preparing the result." : noActivity ? "No unprocessed pull-request activity was available, so Plot completed the check without creating an artifact. The next release will trigger this Routine automatically." : failed ? "Check the repository connection and try the Routine again." : "Plot will import up to one year of repository activity and run the Routine now.";
  return <section className="pt-5"><header><h1 className="max-w-[480px] text-balance font-display text-[32px] font-normal leading-[1.08] tracking-[-0.035em] sm:text-[36px]">{title}</h1><p className="mt-3 max-w-[500px] text-pretty text-[14px] leading-6 text-black/52 dark:text-white/50">{description}</p></header>{routine && <dl className="mt-7 border-y border-black/[0.075] text-[13px] dark:border-white/[0.09]"><div className="grid grid-cols-[96px_1fr] gap-4 border-b border-black/[0.06] py-3 dark:border-white/[0.07]"><dt className="text-black/38 dark:text-white/38">Routine</dt><dd className="font-medium text-black/76 dark:text-white/78">{routine.name}</dd></div><div className="grid grid-cols-[96px_1fr] gap-4 border-b border-black/[0.06] py-3 dark:border-white/[0.07]"><dt className="text-black/38 dark:text-white/38">Repository</dt><dd className="text-black/68 dark:text-white/70">{routine.sourceLabel}</dd></div><div className="grid grid-cols-[96px_1fr] gap-4 py-3"><dt className="text-black/38 dark:text-white/38">Trigger</dt><dd className="text-black/68 dark:text-white/70">Release published</dd></div></dl>}{error && <p className="mt-5 text-sm text-rose-700 dark:text-rose-300" role="alert">{error}</p>}<div className="mt-7 flex items-center gap-2">{artifactId ? <Link href={`/artifacts?artifact=${encodeURIComponent(artifactId)}`} onClick={onReview} className="inline-flex h-10 items-center rounded-full bg-[#252a30] px-4 text-[13px] font-medium text-white transition hover:-translate-y-px hover:bg-[#16191d] active:translate-y-0 dark:bg-white dark:text-[#18191b]">Review result</Link> : noActivity ? <Link href="/chat" onClick={onDismiss} className="inline-flex h-10 items-center rounded-full bg-[#252a30] px-4 text-[13px] font-medium text-white transition hover:-translate-y-px hover:bg-[#16191d] active:translate-y-0 dark:bg-white dark:text-[#18191b]">Done</Link> : <button type="button" onClick={onRun} disabled={!routine || running} className="inline-flex h-10 items-center gap-2 rounded-full bg-[#252a30] px-4 text-[13px] font-medium text-white transition hover:-translate-y-px hover:bg-[#16191d] active:translate-y-0 disabled:cursor-wait disabled:opacity-45 dark:bg-white dark:text-[#18191b]">{running && <HugeiconsIcon icon={Loading03Icon} size={15} className="animate-spin" strokeWidth={1.6} />}{running ? "Running Routine…" : "Run Routine"}</button>}<Link href="/routines" onClick={onDismiss} className="inline-flex h-10 items-center rounded-full px-3.5 text-[13px] text-black/44 transition hover:bg-white/50 hover:text-black/68 dark:text-white/45 dark:hover:bg-white/[0.07] dark:hover:text-white/70">View Routines</Link></div></section>;
}

function routineRunFinished(routine: Routine) {
  const execution = routine.latestExecution;
  return Boolean(execution?.artifactId || execution?.status === "NO_ACTIVITY" || execution?.status === "FAILED" || execution?.agentRunStatus === "SUCCEEDED" || execution?.agentRunStatus === "FAILED");
}
