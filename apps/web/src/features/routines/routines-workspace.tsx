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
  type RoutineCadence,
} from "@/lib/api-client";

import { RoutineTriggerPicker } from "./routine-trigger-picker";

type SourceOption = { id: string; displayName: string };

const defaultInstruction = "Create a concise update from the latest changes.";

export function RoutinesWorkspace() {
  const [routines, setRoutines] = useState<Routine[]>([]);
  const [sources, setSources] = useState<SourceOption[]>([]);
  const [name, setName] = useState("");
  const [sourceScopeId, setSourceScopeId] = useState("");
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
  const workspaceRevisionRef = useRef(0);
  const createAbortRef = useRef<AbortController | null>(null);
  const routineActionAbortRef = useRef<AbortController | null>(null);
  const createTriggerRef = useRef<HTMLButtonElement>(null);
  const createPanelRef = useRef<HTMLElement>(null);
  const nameInputRef = useRef<HTMLInputElement>(null);
  const restoreCreateFocusRef = useRef(false);

  useEffect(() => {
    function handleWorkspaceChanged() {
      workspaceRevisionRef.current += 1;
      createAbortRef.current?.abort();
      routineActionAbortRef.current?.abort();
      createAbortRef.current = null;
      routineActionAbortRef.current = null;
      restoreCreateFocusRef.current = false;
      setRoutines([]);
      setSources([]);
      setSourceScopeId("");
      setError(null);
      setLoadError(null);
      setIsLoading(true);
      setIsSaving(false);
      setBusyRoutineId(null);
      setCreateOpen(false);
      setReloadNonce((value) => value + 1);
    }

    window.addEventListener("plot:workspace-changed", handleWorkspaceChanged);
    return () => {
      window.removeEventListener("plot:workspace-changed", handleWorkspaceChanged);
      createAbortRef.current?.abort();
      routineActionAbortRef.current?.abort();
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
        setSources(nextSources);
        setSourceScopeId((current) => nextSources.some((source) => source.id === current) ? current : nextSources[0]?.id ?? "");
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
        instruction: instruction.trim(),
        cadence,
      }, { signal: controller.signal });
      if (!requestIsCurrent(controller, workspaceRevision, workspaceId)) return;
      setRoutines((current) => [routine, ...current]);
      setName("");
      setInstruction(defaultInstruction);
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
      const updated = await plotApiClient.runRoutineNow(routine.id, { signal: controller.signal });
      if (!requestIsCurrent(controller, workspaceRevision, workspaceId)) return;
      setRoutines((current) => current.map((item) => item.id === updated.id ? updated : item));
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
    restoreCreateFocusRef.current = false;
    setError(null);
    setCreateOpen(true);
  }

  function closeCreate() {
    restoreCreateFocusRef.current = true;
    setCreateOpen(false);
  }

  function retryLoad() {
    setError(null);
    setLoadError(null);
    setIsLoading(true);
    setReloadNonce((value) => value + 1);
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
                {!createOpen && <button ref={createTriggerRef} type="button" onClick={openCreate} className="inline-flex h-9 shrink-0 items-center gap-1.5 rounded-[9px] bg-[#252a30] px-3 text-[12px] font-medium text-white transition hover:bg-[#171a1e] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 focus-visible:ring-offset-2 dark:bg-white dark:text-[#18191b] dark:hover:bg-white/90"><HugeiconsIcon icon={Add01Icon} size={15} color="currentColor" strokeWidth={1.5} aria-hidden="true" />Create</button>}
              </div>
            </div>
            <label className="mt-5 flex h-10 items-center gap-2.5 rounded-[9px] border border-black/10 bg-white px-3 text-[12px] text-black/40 transition focus-within:border-black/20 focus-within:ring-2 focus-within:ring-black/[0.04] dark:border-white/12 dark:bg-white/[0.04] dark:text-white/42">
              <HugeiconsIcon icon={Search01Icon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" />
              <span className="sr-only">Search routines</span>
              <input type="search" value={routineQuery} onChange={(event) => setRoutineQuery(event.target.value)} placeholder="Search routines" className="min-w-0 flex-1 bg-transparent text-[13px] text-black/75 outline-none placeholder:text-black/35 dark:text-white/80 dark:placeholder:text-white/35" />
            </label>
          </header>

          {(loadError ?? error) && <div role="alert" className="mx-6 mt-4 flex items-center justify-between gap-3 rounded-[9px] border border-black/10 bg-white px-3 py-2.5 text-[12px] text-black/58 dark:border-white/12 dark:bg-white/[0.04] dark:text-white/60"><span>{loadError ?? error}</span><button type="button" onClick={retryLoad} disabled={refreshDisabled} aria-label="Retry loading routines" className="inline-flex size-7 items-center justify-center rounded-[7px] text-black/45 transition hover:bg-black/[0.04] disabled:cursor-wait disabled:opacity-45 dark:text-white/48 dark:hover:bg-white/10"><RefreshCw className="size-3.5" /></button></div>}

          {isLoading ? <div className="flex items-center gap-2 px-6 py-8 text-[13px] text-black/45 dark:text-white/45"><LoaderCircle className="size-4 animate-spin" /> Loading routines…</div> : loadError ? null : visibleRoutines.length ? (
            <div className="divide-y divide-black/[0.07] dark:divide-white/[0.08]">
              {visibleRoutines.map((routine) => {
                const busy = busyRoutineId === routine.id;
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
                          <span className="truncate text-[11px] text-black/38 dark:text-white/40">{routine.lastRunStatus ? `Last run: ${routine.lastRunStatus.replaceAll("_", " ").toLowerCase()}` : !isEventCadence(routine.cadence) ? `Next: ${formatDate(routine.nextRunAt)}` : "Waiting for activity"}</span>
                          <div className="flex shrink-0 items-center gap-1">
                            <button type="button" onClick={() => { void runRoutine(routine); }} disabled={busyRoutineId !== null || isRoutineRunInProgress(routine.lastRunStatus)} className="inline-flex h-7 items-center gap-1.5 rounded-[7px] px-2 text-[11px] font-medium text-black/55 transition hover:bg-black/[0.04] hover:text-black/78 disabled:cursor-wait disabled:opacity-50 dark:text-white/58 dark:hover:bg-white/10 dark:hover:text-white/82"><Play className="size-3" /> Run</button>
                            <button type="button" onClick={() => { void toggleRoutine(routine); }} disabled={busyRoutineId !== null} aria-label={routine.enabled ? `Pause ${routine.name}` : `Enable ${routine.name}`} title={routine.enabled ? "Pause routine" : "Enable routine"} className="inline-flex size-7 items-center justify-center rounded-[7px] text-black/42 transition hover:bg-black/[0.04] hover:text-black/72 disabled:cursor-wait disabled:opacity-50 dark:text-white/45 dark:hover:bg-white/10 dark:hover:text-white/75">{busy ? <LoaderCircle className="size-3.5 animate-spin" /> : <Power className="size-3.5" />}</button>
                          </div>
                        </div>
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
              <p className="mt-1 text-[12px] leading-5 text-black/40 dark:text-white/42">{routines.length ? "Try another search." : "Create one to keep your next draft moving."}</p>
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
                <label className="block space-y-1.5 text-[12px] font-medium text-black/62 dark:text-white/65">
                  Source repository
                  <select value={sourceScopeId} onChange={(event) => setSourceScopeId(event.target.value)} disabled={!sources.length} className="h-10 w-full rounded-[9px] border border-black/10 bg-white px-3 text-sm font-normal text-black/80 outline-none focus:border-black/25 focus:ring-2 focus:ring-black/[0.05] disabled:cursor-not-allowed disabled:opacity-50 dark:border-white/12 dark:bg-white/[0.06] dark:text-white/85">
                    {!sources.length ? <option value="">Connect GitHub first</option> : sources.map((source) => <option key={source.id} value={source.id}>{source.displayName}</option>)}
                  </select>
                </label>
                <label className="block space-y-1.5 text-[12px] font-medium text-black/62 dark:text-white/65">
                  Routine name
                  <input ref={nameInputRef} value={name} onChange={(event) => setName(event.target.value)} placeholder="Weekly product update" maxLength={80} className="h-10 w-full rounded-[9px] border border-black/10 bg-white px-3 text-sm font-normal text-black/80 outline-none placeholder:text-black/35 focus:border-black/25 focus:ring-2 focus:ring-black/[0.05] dark:border-white/12 dark:bg-white/[0.06] dark:text-white/85 dark:placeholder:text-white/35" />
                </label>
                <label className="block space-y-1.5 text-[12px] font-medium text-black/62 dark:text-white/65">
                  Draft instruction
                  <textarea value={instruction} onChange={(event) => setInstruction(event.target.value)} maxLength={2_000} rows={4} className="w-full resize-y rounded-[9px] border border-black/10 bg-white px-3 py-2.5 text-sm font-normal leading-5 text-black/80 outline-none placeholder:text-black/35 focus:border-black/25 focus:ring-2 focus:ring-black/[0.05] dark:border-white/12 dark:bg-white/[0.06] dark:text-white/85 dark:placeholder:text-white/35" />
                </label>
                <div className="space-y-1.5 text-[12px] font-medium text-black/62 dark:text-white/65">
                  <span>Trigger</span>
                  <RoutineTriggerPicker value={cadence} onChange={setCadence} />
                </div>
              </div>
              <div className="mt-auto flex items-center justify-between gap-3 border-t border-black/[0.08] px-6 py-4 dark:border-white/10 sm:px-8">
                {!sources.length && !isLoading ? <Link href="/settings/integrations" className="text-[12px] font-medium text-black/55 underline underline-offset-4 dark:text-white/58">Set up GitHub</Link> : <span />}
                <button type="submit" disabled={!canCreate} className="inline-flex h-9 items-center gap-2 rounded-[9px] bg-[#252a30] px-3.5 text-[13px] font-medium text-white transition hover:bg-[#171a1e] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-white dark:text-[#18191b] dark:hover:bg-white/90">
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
  if (cadence === "DAILY") return "Every day";
  if (cadence === "WEEKLY") return "Every week";
  if (cadence === "ON_GITHUB_CHANGE") return "When the default branch changes";
  if (cadence === "ON_GITHUB_RELEASE") return "When a release is published";
  return "When a git tag is pushed";
}

function isEventCadence(cadence: RoutineCadence) {
  return cadence !== "DAILY" && cadence !== "WEEKLY";
}

function isRoutineRunInProgress(status: Routine["lastRunStatus"]) {
  // ponytail: refresh on demand; add background polling only if status latency proves it is needed.
  return status === "QUEUED"
    || status === "WRITING"
    || status === "REVIEWING"
    || status === "REWRITING";
}
