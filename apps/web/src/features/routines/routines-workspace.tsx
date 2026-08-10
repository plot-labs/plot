"use client";

import { Add01Icon, Cancel01Icon, Search01Icon, ZapIcon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import Link from "next/link";
import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { LoaderCircle, Play, Power, RefreshCw } from "lucide-react";

import {
  getSelectedWorkspaceId,
  plotApiClient,
  type GitHubRepository,
  type Routine,
  type RoutineAgentRunDetail,
  type RoutineCadence,
} from "@/lib/api-client";

import { RoutineTriggerPicker } from "./routine-trigger-picker";
import { SourceRepositoryPicker, type SourceOption } from "./source-repository-picker";

const defaultInstruction = "Create a concise update from the latest changes.";

export function RoutinesWorkspace() {
  const [routines, setRoutines] = useState<Routine[]>([]);
  const [sources, setSources] = useState<SourceOption[]>([]);
  const [name, setName] = useState("");
  const [sourceScopeId, setSourceScopeId] = useState("");
  const [contextSourceScopeIds, setContextSourceScopeIds] = useState<string[]>([]);
  const [instruction, setInstruction] = useState(defaultInstruction);
  const [cadence, setCadence] = useState<RoutineCadence>("WEEKLY");
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [busyRoutineId, setBusyRoutineId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadNonce, setReloadNonce] = useState(0);
  const [routineQuery, setRoutineQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [expandedRoutineId, setExpandedRoutineId] = useState<string | null>(null);
  const [agentDetail, setAgentDetail] = useState<{ routineId: string; value: RoutineAgentRunDetail } | null>(null);
  const [agentDetailLoadingId, setAgentDetailLoadingId] = useState<string | null>(null);
  const [agentDetailError, setAgentDetailError] = useState<string | null>(null);
  const workspaceRevisionRef = useRef(0);
  const createAbortRef = useRef<AbortController | null>(null);
  const routineActionAbortRef = useRef<AbortController | null>(null);
  const agentDetailAbortRef = useRef<AbortController | null>(null);
  const createTriggerRef = useRef<HTMLButtonElement>(null);
  const createPanelRef = useRef<HTMLElement>(null);
  const nameInputRef = useRef<HTMLInputElement>(null);
  const restoreCreateFocusRef = useRef(false);

  useEffect(() => {
    function handleWorkspaceChanged() {
      workspaceRevisionRef.current += 1;
      createAbortRef.current?.abort();
      routineActionAbortRef.current?.abort();
      agentDetailAbortRef.current?.abort();
      createAbortRef.current = null;
      routineActionAbortRef.current = null;
      agentDetailAbortRef.current = null;
      restoreCreateFocusRef.current = false;
      setRoutines([]);
      setSources([]);
      setSourceScopeId("");
      setContextSourceScopeIds([]);
      setError(null);
      setLoadError(null);
      setIsLoading(true);
      setIsSaving(false);
      setBusyRoutineId(null);
      setCreateOpen(false);
      setExpandedRoutineId(null);
      setAgentDetail(null);
      setAgentDetailLoadingId(null);
      setAgentDetailError(null);
      setReloadNonce((value) => value + 1);
    }

    window.addEventListener("plot:workspace-changed", handleWorkspaceChanged);
    return () => {
      window.removeEventListener("plot:workspace-changed", handleWorkspaceChanged);
      createAbortRef.current?.abort();
      routineActionAbortRef.current?.abort();
      agentDetailAbortRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const workspaceRevision = workspaceRevisionRef.current;
    const workspaceId = getSelectedWorkspaceId();
    if (!workspaceId) {
      queueMicrotask(() => {
        if (controller.signal.aborted || workspaceRevisionRef.current !== workspaceRevision) return;
        setLoadError("Select a workspace to manage routines.");
        setIsLoading(false);
      });
      return () => controller.abort();
    }

    Promise.allSettled([
      plotApiClient.listRoutines({ signal: controller.signal }),
      plotApiClient.listGitHubConnections({ signal: controller.signal }),
    ])
      .then(([routineResult, connectionResult]) => {
        if (!requestIsCurrent(controller, workspaceRevision, workspaceId)) return;
        if (routineResult.status === "rejected") {
          setLoadError("Routines could not be loaded.");
          return;
        }
        const nextRoutines = routineResult.value;
        const connections = connectionResult.status === "fulfilled" ? connectionResult.value : [];
        const nextSources = connections
          .filter((connection) => connection.status === "ACTIVE")
          .flatMap((connection) => connection.repositories)
          .filter((repository): repository is GitHubRepository & { id: string } => Boolean(repository.id) && repository.status === "ACTIVE")
          .map(({ id, displayName }) => ({ id, displayName }));
        setRoutines(nextRoutines);
        if (nextRoutines.some((routine) => Boolean(routine.latestExecution?.chatId))) {
          window.dispatchEvent(new Event("plot:sessions-changed"));
        }
        setSources(nextSources);
        setSourceScopeId((current) => nextSources.some((source) => source.id === current) ? current : nextSources[0]?.id ?? "");
        setContextSourceScopeIds((current) => current
          .filter((id) => nextSources.some((source) => source.id === id))
          .slice(0, 4));
        setLoadError(null);
        setError(connectionResult.status === "rejected" ? "GitHub sources could not be loaded." : null);
      })
      .catch(() => {
        if (requestIsCurrent(controller, workspaceRevision, workspaceId)) {
          setLoadError("Routines could not be loaded.");
        }
      })
      .finally(() => {
        if (requestIsCurrent(controller, workspaceRevision, workspaceId)) setIsLoading(false);
      });

    return () => controller.abort();
  }, [reloadNonce]);

  useEffect(() => {
    if (createOpen) {
      createPanelRef.current?.scrollIntoView?.({ block: "start" });
      nameInputRef.current?.focus({ preventScroll: true });
      return;
    }
    if (restoreCreateFocusRef.current) {
      restoreCreateFocusRef.current = false;
      createTriggerRef.current?.focus();
    }
  }, [createOpen]);

  const canCreate = Boolean(name.trim() && sourceScopeId && instruction.trim() && !isSaving);
  const refreshDisabled = isLoading || isSaving || busyRoutineId !== null;
  const visibleRoutines = useMemo(() => {
    const query = routineQuery.trim().toLowerCase();
    return routines.filter((routine) => {
      if (!query) return true;
      return [routine.name, routine.sourceLabel, routine.instruction, formatCadence(routine.cadence)]
        .some((value) => value.toLowerCase().includes(query));
    });
  }, [routineQuery, routines]);

  function requestIsCurrent(controller: AbortController, workspaceRevision: number, workspaceId: string) {
    return !controller.signal.aborted
      && workspaceRevisionRef.current === workspaceRevision
      && getSelectedWorkspaceId() === workspaceId;
  }

  async function createRoutine(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canCreate || createAbortRef.current) return;
    const workspaceId = getSelectedWorkspaceId();
    if (!workspaceId) {
      setError("Select a workspace to create a routine.");
      return;
    }
    const controller = new AbortController();
    const workspaceRevision = workspaceRevisionRef.current;
    createAbortRef.current = controller;
    setIsSaving(true);
    setError(null);
    try {
      const routine = await plotApiClient.createRoutine({
        name: name.trim(),
        sourceScopeId,
        contextSourceScopeIds,
        instruction: instruction.trim(),
        cadence,
      }, { signal: controller.signal });
      if (!requestIsCurrent(controller, workspaceRevision, workspaceId)) return;
      setRoutines((current) => [routine, ...current]);
      setName("");
      setInstruction(defaultInstruction);
      setContextSourceScopeIds([]);
      restoreCreateFocusRef.current = true;
      setCreateOpen(false);
    } catch {
      if (requestIsCurrent(controller, workspaceRevision, workspaceId)) {
        setError("Routine could not be created. Check the connected GitHub source and try again.");
      }
    } finally {
      if (createAbortRef.current === controller) {
        createAbortRef.current = null;
        setIsSaving(false);
      }
    }
  }

  async function toggleRoutine(routine: Routine) {
    if (busyRoutineId || routineActionAbortRef.current) return;
    const workspaceId = getSelectedWorkspaceId();
    if (!workspaceId) return;
    const controller = new AbortController();
    const workspaceRevision = workspaceRevisionRef.current;
    routineActionAbortRef.current = controller;
    setBusyRoutineId(routine.id);
    setError(null);
    try {
      const updated = await plotApiClient.updateRoutine(
        routine.id,
        { enabled: !routine.enabled },
        { signal: controller.signal },
      );
      if (!requestIsCurrent(controller, workspaceRevision, workspaceId)) return;
      setRoutines((current) => current.map((item) => item.id === updated.id ? updated : item));
    } catch {
      if (requestIsCurrent(controller, workspaceRevision, workspaceId)) {
        setError("Routine could not be updated.");
      }
    } finally {
      if (routineActionAbortRef.current === controller) {
        routineActionAbortRef.current = null;
        setBusyRoutineId(null);
      }
    }
  }

  async function runRoutine(routine: Routine) {
    if (busyRoutineId || routineActionAbortRef.current) return;
    const workspaceId = getSelectedWorkspaceId();
    if (!workspaceId) return;
    const controller = new AbortController();
    const workspaceRevision = workspaceRevisionRef.current;
    routineActionAbortRef.current = controller;
    setBusyRoutineId(routine.id);
    setError(null);
    try {
      const updated = await plotApiClient.runRoutineNow(routine.id, crypto.randomUUID(), { signal: controller.signal });
      if (!requestIsCurrent(controller, workspaceRevision, workspaceId)) return;
      setRoutines((current) => current.map((item) => item.id === updated.id ? updated : item));
      if (updated.latestExecution?.chatId) window.dispatchEvent(new Event("plot:sessions-changed"));
      agentDetailAbortRef.current?.abort();
      agentDetailAbortRef.current = null;
      setExpandedRoutineId(null);
      setAgentDetail(null);
      setAgentDetailLoadingId(null);
      setAgentDetailError(null);
    } catch {
      if (requestIsCurrent(controller, workspaceRevision, workspaceId)) {
        setError("Routine could not run. Try again after checking the connected source.");
      }
    } finally {
      if (routineActionAbortRef.current === controller) {
        routineActionAbortRef.current = null;
        setBusyRoutineId(null);
      }
    }
  }

  function openCreate() {
    if (!sources.length || isLoading) return;
    restoreCreateFocusRef.current = false;
    setError(null);
    setCreateOpen(true);
  }

  function closeCreate() {
    restoreCreateFocusRef.current = true;
    setCreateOpen(false);
  }

  function retryLoad() {
    agentDetailAbortRef.current?.abort();
    agentDetailAbortRef.current = null;
    setExpandedRoutineId(null);
    setAgentDetail(null);
    setAgentDetailLoadingId(null);
    setAgentDetailError(null);
    setError(null);
    setLoadError(null);
    setIsLoading(true);
    setReloadNonce((value) => value + 1);
  }

  function changeTriggerSource(nextSourceScopeId: string) {
    setSourceScopeId(nextSourceScopeId);
    setContextSourceScopeIds((current) => current.filter((id) => id !== nextSourceScopeId));
  }

  function toggleContextSource(id: string) {
    setContextSourceScopeIds((current) => current.includes(id)
      ? current.filter((sourceId) => sourceId !== id)
      : current.length < 4 ? [...current, id] : current);
  }

  async function toggleAgentDetail(routine: Routine) {
    const agentRunId = routine.latestExecution?.agentRunId;
    if (!agentRunId) return;
    if (expandedRoutineId === routine.id) {
      agentDetailAbortRef.current?.abort();
      agentDetailAbortRef.current = null;
      setExpandedRoutineId(null);
      setAgentDetail(null);
      setAgentDetailLoadingId(null);
      setAgentDetailError(null);
      return;
    }

    const workspaceId = getSelectedWorkspaceId();
    if (!workspaceId) return;
    agentDetailAbortRef.current?.abort();
    const controller = new AbortController();
    const workspaceRevision = workspaceRevisionRef.current;
    agentDetailAbortRef.current = controller;
    setExpandedRoutineId(routine.id);
    setAgentDetail(null);
    setAgentDetailLoadingId(routine.id);
    setAgentDetailError(null);
    try {
      const detail = await plotApiClient.getRoutineAgentRun(routine.id, agentRunId, { signal: controller.signal });
      if (!requestIsCurrent(controller, workspaceRevision, workspaceId)) return;
      setAgentDetail({ routineId: routine.id, value: detail });
    } catch {
      if (requestIsCurrent(controller, workspaceRevision, workspaceId)) {
        setAgentDetailError("Agent activity could not be loaded.");
      }
    } finally {
      if (agentDetailAbortRef.current === controller) {
        agentDetailAbortRef.current = null;
        setAgentDetailLoadingId(null);
      }
    }
  }

  return (
    <div className="h-full overflow-y-auto bg-[#f7f8fa] dark:bg-[#18191d] lg:overflow-hidden">
      <div className={createOpen ? "grid min-h-full lg:h-full lg:grid-cols-[minmax(340px,0.88fr)_minmax(0,1.12fr)]" : "min-h-full"}>
        <section className={createOpen ? "min-w-0 border-b border-black/[0.08] bg-[#f7f8fa] dark:border-white/10 dark:bg-[#18191d] lg:h-full lg:overflow-y-auto lg:border-b-0 lg:border-r" : "mx-auto min-h-full w-full max-w-[760px] bg-[#f7f8fa] dark:bg-[#18191d] lg:h-full lg:overflow-y-auto"} aria-labelledby="routines-heading">
          <header className="border-b border-black/[0.08] px-6 pb-5 pt-8 dark:border-white/10">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <h1 id="routines-heading" className="font-serif text-[32px] font-normal leading-none tracking-[-0.025em] text-black/90 dark:text-white/92">Routines</h1>
                <p className="mt-2 text-[13px] leading-5 text-black/48 dark:text-white/50">Turn schedules and GitHub changes into source-backed drafts.</p>
              </div>
              <div className="flex shrink-0 items-center gap-1.5">
                <button type="button" onClick={retryLoad} disabled={refreshDisabled} aria-label="Refresh routines" title="Refresh routines" className="inline-flex size-9 items-center justify-center rounded-[9px] text-black/45 transition hover:bg-black/[0.04] hover:text-black/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:cursor-wait disabled:opacity-45 dark:text-white/48 dark:hover:bg-white/10 dark:hover:text-white/75"><RefreshCw className={`size-3.5 ${isLoading ? "animate-spin" : ""}`} /></button>
                {!createOpen && (
                  <button
                    ref={createTriggerRef}
                    type="button"
                    onClick={openCreate}
                    disabled={isLoading || !sources.length}
                    style={{
                      background: "linear-gradient(to bottom, rgba(0, 0, 0, 0.78), rgba(0, 0, 0, 0.88))",
                      backdropFilter: "saturate(200%) blur(40px)",
                      WebkitBackdropFilter: "saturate(200%) blur(40px)",
                      border: "1px solid rgba(255, 255, 255, 0.18)",
                      boxShadow: "inset 0 1px 1px rgba(255, 255, 255, 0.25), inset 0 -1px 1px rgba(0, 0, 0, 0.1), 0 8px 24px rgba(0, 0, 0, 0.12), 0 2px 6px rgba(0, 0, 0, 0.08)",
                      color: "#FFFFFF",
                    }}
                    className="inline-flex h-8.5 shrink-0 items-center gap-1.5 rounded-full px-3.5 text-[12px] font-semibold text-white transition-all duration-200 hover:opacity-90 active:scale-[0.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    <HugeiconsIcon icon={Add01Icon} size={15} color="currentColor" strokeWidth={1.5} aria-hidden="true" />
                    Create
                  </button>
                )}
              </div>
            </div>
            <label className="mt-5 flex h-10 items-center gap-2.5 rounded-[9px] border border-black/10 bg-white px-3 text-[12px] text-black/40 transition focus-within:border-black/20 focus-within:ring-2 focus-within:ring-black/[0.04] dark:border-white/12 dark:bg-white/[0.04] dark:text-white/42">
              <HugeiconsIcon icon={Search01Icon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" />
              <span className="sr-only">Search routines</span>
              <input type="search" value={routineQuery} onChange={(event) => setRoutineQuery(event.target.value)} placeholder="Search routines" className="min-w-0 flex-1 bg-transparent text-[13px] text-black/75 outline-none placeholder:text-black/35 dark:text-white/80 dark:placeholder:text-white/35" />
            </label>
          </header>

          {(loadError ?? error) && <div role="alert" className="mx-6 mt-4 flex items-center justify-between gap-3 rounded-[9px] border border-black/10 bg-white px-3 py-2.5 text-[12px] text-black/58 dark:border-white/12 dark:bg-white/[0.04] dark:text-white/60"><span>{loadError ?? error}</span><button type="button" onClick={retryLoad} disabled={refreshDisabled} aria-label="Retry loading routines" className="inline-flex size-7 items-center justify-center rounded-[7px] text-black/45 transition hover:bg-black/[0.04] disabled:cursor-wait disabled:opacity-45 dark:text-white/48 dark:hover:bg-white/10"><RefreshCw className="size-3.5" /></button></div>}

          {!isLoading && !loadError && !sources.length && <div className="mx-6 mt-4 flex items-center justify-between gap-3 rounded-[9px] border border-black/10 bg-white px-3 py-2.5 text-[12px] text-black/58 dark:border-white/12 dark:bg-white/[0.04] dark:text-white/60"><span>Connect a source before creating a routine.</span><Link href="/settings/integrations" className="shrink-0 font-medium text-black/72 underline underline-offset-4 dark:text-white/75">Integrations</Link></div>}

          {isLoading ? <div className="flex items-center gap-2 px-6 py-8 text-[13px] text-black/45 dark:text-white/45"><LoaderCircle className="size-4 animate-spin" /> Loading routines…</div> : loadError ? null : visibleRoutines.length ? (
            <div className="divide-y divide-black/[0.07] dark:divide-white/[0.08]">
              {visibleRoutines.map((routine) => {
                const busy = busyRoutineId === routine.id;
                const agentRunId = routine.latestExecution?.agentRunId;
                const chatId = routine.latestExecution?.chatId;
                const generationRunId = routine.latestExecution?.generationRunId;
                const artifactId = routine.latestExecution?.artifactId;
                const expanded = expandedRoutineId === routine.id;
                return (
                  <article key={routine.id} className="px-6 py-4 transition hover:bg-white/70 dark:hover:bg-white/[0.04]">
                    <div className="flex items-start gap-3">
                      <span className={`mt-1.5 size-2 shrink-0 rounded-full border ${routine.enabled ? "border-[#697482] bg-[#697482]" : "border-black/25 dark:border-white/25"}`} aria-hidden="true" />
                      <div className="min-w-0 flex-1">
                        <div className="flex items-start justify-between gap-3">
                          <div className="min-w-0">
                            <h2 className="truncate text-[14px] font-semibold text-black/82 dark:text-white/86">{routine.name}</h2>
                            <p className="mt-1 text-[12px] leading-5 text-black/45 dark:text-white/45">{formatCadence(routine.cadence)}</p>
                          </div>
                          <span className="shrink-0 text-[11px] font-medium text-black/38 dark:text-white/40">{routine.enabled ? "On" : "Paused"}</span>
                        </div>
                        <p className="mt-2 truncate text-[11px] text-black/38 dark:text-white/40">{routine.sourceLabel}</p>
                        <div className="mt-3 flex items-center justify-between gap-3">
                          <span className="truncate text-[11px] text-black/38 dark:text-white/40">{formatRoutineStatus(routine)}</span>
                          <div className="flex shrink-0 items-center gap-1">
                            {chatId && <Link href={`/chat?chat=${encodeURIComponent(chatId)}${generationRunId ? `&generation=${encodeURIComponent(generationRunId)}` : ""}${artifactId ? `&artifact=${encodeURIComponent(artifactId)}` : ""}`} aria-label={`Open Chat for ${routine.name}`} className="inline-flex h-7 items-center rounded-[7px] px-2 text-[11px] font-medium text-black/55 transition hover:bg-black/[0.04] hover:text-black/78 dark:text-white/58 dark:hover:bg-white/10 dark:hover:text-white/82">Chat</Link>}
                            {artifactId && <Link href={`/artifacts?artifact=${encodeURIComponent(artifactId)}`} aria-label={`Open artifact for ${routine.name}`} className="inline-flex h-7 items-center rounded-[7px] px-2 text-[11px] font-medium text-black/55 transition hover:bg-black/[0.04] hover:text-black/78 dark:text-white/58 dark:hover:bg-white/10 dark:hover:text-white/82">Artifact</Link>}
                            {agentRunId && <button type="button" onClick={() => { void toggleAgentDetail(routine); }} aria-expanded={expanded} aria-label={`View agent activity for ${routine.name}`} className="inline-flex h-7 items-center rounded-[7px] px-2 text-[11px] font-medium text-black/55 transition hover:bg-black/[0.04] hover:text-black/78 dark:text-white/58 dark:hover:bg-white/10 dark:hover:text-white/82">Activity</button>}
                            <button type="button" onClick={() => { void runRoutine(routine); }} disabled={busyRoutineId !== null || isRoutineRunInProgress(routine)} className="inline-flex h-7 items-center gap-1.5 rounded-[7px] px-2 text-[11px] font-medium text-black/55 transition hover:bg-black/[0.04] hover:text-black/78 disabled:cursor-wait disabled:opacity-50 dark:text-white/58 dark:hover:bg-white/10 dark:hover:text-white/82"><Play className="size-3" /> Run</button>
                            <button type="button" onClick={() => { void toggleRoutine(routine); }} disabled={busyRoutineId !== null} aria-label={routine.enabled ? `Pause ${routine.name}` : `Enable ${routine.name}`} title={routine.enabled ? "Pause routine" : "Enable routine"} className="inline-flex size-7 items-center justify-center rounded-[7px] text-black/42 transition hover:bg-black/[0.04] hover:text-black/72 disabled:cursor-wait disabled:opacity-50 dark:text-white/45 dark:hover:bg-white/10 dark:hover:text-white/75">{busy ? <LoaderCircle className="size-3.5 animate-spin" /> : <Power className="size-3.5" />}</button>
                          </div>
                        </div>
                        {expanded && <div className="mt-3 border-t border-black/[0.07] pt-3 dark:border-white/[0.08]">
                          {agentDetailLoadingId === routine.id ? <p className="text-[11px] text-black/42 dark:text-white/45">Loading agent activity…</p> : agentDetailError ? <p role="alert" className="text-[11px] text-black/55 dark:text-white/60">{agentDetailError}</p> : agentDetail?.routineId === routine.id ? (
                            <ol aria-label={`Agent activity for ${routine.name}`} className="space-y-1.5">
                              {[...agentDetail.value.steps].sort((left, right) => left.sequence - right.sequence).map((step) => <li key={step.sequence} className="flex items-center justify-between gap-3 text-[11px]"><span className="min-w-0 truncate text-black/55 dark:text-white/58">{formatAgentStep(step)}</span><span className="shrink-0 text-black/38 dark:text-white/40">{step.status.toLowerCase()}</span></li>)}
                            </ol>
                          ) : null}
                        </div>}
                      </div>
                    </div>
                  </article>
                );
              })}
            </div>
          ) : (
            <div className="px-6 py-14 text-center">
              <HugeiconsIcon icon={ZapIcon} size={20} color="currentColor" strokeWidth={1.5} className="mx-auto text-black/25 dark:text-white/30" aria-hidden="true" />
              <p className="mt-3 text-[13px] font-medium text-black/58 dark:text-white/62">{routines.length ? "No matching routines" : "No routines yet"}</p>
              <p className="mt-1 text-[12px] leading-5 text-black/40 dark:text-white/42">{routines.length ? "Try another search." : sources.length ? "Create one to keep your next draft moving." : "Connect a source to get started."}</p>
            </div>
          )}
        </section>

        {createOpen && <section ref={createPanelRef} className="min-w-0 bg-[#f7f8fa] dark:bg-[#18191d] lg:h-full lg:overflow-y-auto" aria-labelledby="create-routine-heading">
          <div className="flex min-h-full flex-col">
            <header className="border-b border-black/[0.08] px-6 pb-5 pt-8 dark:border-white/10 sm:px-8">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <h2 id="create-routine-heading" className="font-serif text-[28px] font-normal leading-tight tracking-[-0.02em] text-black/86 dark:text-white/90">Create routine</h2>
                  <p className="mt-1.5 text-[13px] leading-5 text-black/45 dark:text-white/46">Choose what Plot should watch and what the draft should cover.</p>
                </div>
                <button type="button" onClick={closeCreate} aria-label="Close create routine" className="inline-flex size-8 shrink-0 items-center justify-center rounded-[8px] text-black/40 transition hover:bg-black/[0.04] hover:text-black/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 dark:text-white/45 dark:hover:bg-white/10 dark:hover:text-white/75"><HugeiconsIcon icon={Cancel01Icon} size={17} color="currentColor" strokeWidth={1.5} aria-hidden="true" /></button>
              </div>
            </header>

            <form onSubmit={(event) => void createRoutine(event)} className="flex flex-1 flex-col">
              <div className="space-y-5 px-6 py-6 sm:px-8">
                <label className="flex flex-col gap-2.5 text-[12px] font-medium text-black/62 dark:text-white/65">
                  <span>Source repository</span>
                  <SourceRepositoryPicker sources={sources} value={sourceScopeId} onChange={changeTriggerSource} />
                </label>
                {sources.some((source) => source.id !== sourceScopeId) && <fieldset className="space-y-2">
                  <legend className="text-[12px] font-medium text-black/62 dark:text-white/65">Additional context</legend>
                  <p className="text-[11px] leading-4 text-black/40 dark:text-white/42">Optionally read up to four more repositories.</p>
                  <div className="space-y-1">
                    {sources.filter((source) => source.id !== sourceScopeId).map((source) => {
                      const selected = contextSourceScopeIds.includes(source.id);
                      return <label key={source.id} className="flex min-h-9 items-center gap-2 rounded-[8px] px-2 text-[12px] font-normal text-black/65 transition hover:bg-black/[0.03] dark:text-white/68 dark:hover:bg-white/[0.05]"><input type="checkbox" checked={selected} disabled={!selected && contextSourceScopeIds.length >= 4} onChange={() => toggleContextSource(source.id)} className="size-3.5 accent-[#252a30]" /><span className="truncate">{source.displayName}</span></label>;
                    })}
                  </div>
                </fieldset>}
                <label className="flex flex-col gap-2.5 text-[12px] font-medium text-black/62 dark:text-white/65">
                  <span>Routine name</span>
                  <input ref={nameInputRef} value={name} onChange={(event) => setName(event.target.value)} placeholder="Weekly product update" maxLength={80} className="h-10 w-full rounded-[9px] border border-black/10 bg-white px-3 text-sm font-normal text-black/80 outline-none placeholder:text-black/35 focus:border-black/25 focus:ring-2 focus:ring-black/[0.05] dark:border-white/12 dark:bg-white/[0.06] dark:text-white/85 dark:placeholder:text-white/35" />
                </label>
                <label className="flex flex-col gap-2.5 text-[12px] font-medium text-black/62 dark:text-white/65">
                  <span>Draft instruction</span>
                  <textarea value={instruction} onChange={(event) => setInstruction(event.target.value)} maxLength={2_000} rows={4} className="w-full resize-y rounded-[9px] border border-black/10 bg-white px-3 py-2.5 text-sm font-normal leading-5 text-black/80 outline-none placeholder:text-black/35 focus:border-black/25 focus:ring-2 focus:ring-black/[0.05] dark:border-white/12 dark:bg-white/[0.06] dark:text-white/85 dark:placeholder:text-white/35" />
                </label>
                <div className="flex flex-col gap-2.5 text-[12px] font-medium text-black/62 dark:text-white/65">
                  <span>Trigger</span>
                  <RoutineTriggerPicker value={cadence} onChange={setCadence} />
                </div>
              </div>
              <div className="mt-auto flex items-center justify-end gap-3 border-t border-black/[0.08] px-6 py-4 dark:border-white/10 sm:px-8">
                <button
                  type="submit"
                  disabled={!canCreate}
                  style={{
                    background: "linear-gradient(to bottom, rgba(0, 0, 0, 0.78), rgba(0, 0, 0, 0.88))",
                    backdropFilter: "saturate(200%) blur(40px)",
                    WebkitBackdropFilter: "saturate(200%) blur(40px)",
                    border: "1px solid rgba(255, 255, 255, 0.18)",
                    boxShadow: "inset 0 1px 1px rgba(255, 255, 255, 0.25), inset 0 -1px 1px rgba(0, 0, 0, 0.1), 0 8px 24px rgba(0, 0, 0, 0.12), 0 2px 6px rgba(0, 0, 0, 0.08)",
                    color: "#FFFFFF",
                  }}
                  className="inline-flex h-9 items-center gap-2 rounded-full px-4 text-[13px] font-semibold text-white transition-all duration-200 hover:opacity-90 active:scale-[0.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {isSaving ? <LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" /> : null}
                  {isSaving ? "Creating…" : "Create routine"}
                </button>
              </div>
            </form>
          </div>
        </section>}
      </div>
    </div>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

function formatCadence(cadence: RoutineCadence) {
  if (cadence === "DAILY") return "Daily";
  if (cadence === "WEEKLY") return "Weekly";
  if (cadence === "ON_GITHUB_CHANGE") return "When the default branch changes";
  if (cadence === "ON_GITHUB_RELEASE") return "When a release is published";
  return "When a git tag is pushed";
}

function isEventCadence(cadence: RoutineCadence) {
  return cadence !== "DAILY" && cadence !== "WEEKLY";
}

function formatRoutineStatus(routine: Routine) {
  const execution = routine.latestExecution;
  if (execution?.status === "NO_ACTIVITY") return "Checked · No new activity";
  if (execution?.status === "FAILED") return "Run failed";
  if (execution?.agentRunStatus === "QUEUED") return "Agent queued";
  if (execution?.agentRunStatus === "RUNNING") return "Agent running";
  if (execution?.agentRunStatus === "SUCCEEDED") return "Agent completed";
  if (execution?.agentRunStatus === "FAILED") return "Agent failed";
  if (execution?.status === "PROBING") return "Checking for activity";
  if (execution?.status === "DISPATCHED") return "Preparing agent";
  if (routine.lastRunStatus) return `Last run: ${routine.lastRunStatus.replaceAll("_", " ").toLowerCase()}`;
  return !isEventCadence(routine.cadence) ? `Next: ${formatDate(routine.nextRunAt)}` : "Waiting for activity";
}

function formatAgentStep(step: RoutineAgentRunDetail["steps"][number]) {
  const label = step.kind === "ARTIFACT_HANDOFF"
    ? "Create artifact"
    : step.toolName ? `Read ${step.toolName}` : "Read source context";
  return step.failureCode ? `${label} · ${step.failureCode.replaceAll("_", " ").toLowerCase()}` : label;
}

function isRoutineRunInProgress(routine: Routine) {
  const execution = routine.latestExecution;
  if (execution?.status === "PROBING") return true;
  if (execution?.status === "DISPATCHED" && (!execution.agentRunStatus || execution.agentRunStatus === "QUEUED" || execution.agentRunStatus === "RUNNING")) return true;
  const status = routine.lastRunStatus;
  // ponytail: refresh on demand; add background polling only if status latency proves it is needed.
  return status === "QUEUED"
    || status === "WRITING"
    || status === "REVIEWING"
    || status === "REWRITING";
}
