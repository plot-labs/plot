"use client";

import { Check, CircleAlert, CircleDot, CircleX, FileText, GitPullRequest, LoaderCircle } from "lucide-react";
import { useEffect, useState } from "react";

import type { GenerationArtifact, GenerationRun, GenerationStepTiming } from "@plot/api-client";

type GenerationWorkLogProps = {
  run: GenerationRun;
};

export function GenerationWorkLog({ run }: GenerationWorkLogProps) {
  const timing = run.timing;
  const [now, setNow] = useState(() => Date.now());
  const active = !isTerminal(run.status);

  useEffect(() => {
    if (!active) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [active]);

  const elapsedMs = timing?.startedAt
    ? (timing.finishedAt ? Date.parse(timing.finishedAt) : now) - Date.parse(timing.startedAt)
    : null;
  const steps = timing?.steps ?? [];
  const artifacts = run.artifacts;

  return (
    <section className="overflow-hidden rounded-xl border border-black/10 bg-black/[0.025] dark:border-white/10 dark:bg-white/[0.04]" aria-label="Generation work log">
      <div className="flex items-center gap-2 px-4 py-3 text-sm text-black/72 dark:text-white/72">
        {headerIcon(run.status)}
        <span className="font-medium">{headerLabel(run.status, elapsedMs)}</span>
        <span className="ml-auto text-xs text-black/40 dark:text-white/40">{run.status.toLowerCase().replaceAll("_", " ")}</span>
      </div>

      <div className="border-t border-black/[0.06] px-4 py-3 dark:border-white/[0.08]">
        <ol className="space-y-2 text-sm text-black/62 dark:text-white/62">
          <WorkLogRow icon={<GitPullRequest className="size-3.5" />} text={`Loaded ${countLabel(run.evidence.length, "reference")}`} />
          {steps.map((step) => (
            <WorkLogRow key={`${step.kind}-${step.sequence}`} icon={stepIcon(step)} text={stepLabel(step, findArtifact(step, steps, artifacts), run)} active={step.status === "RUNNING"} failed={step.status === "FAILED"} />
          ))}
          {artifacts.filter((artifact) => artifact.kind === "CONFLICT" && artifact.detail).map((artifact) => (
            <WorkLogRow key={`conflict-${artifact.sequence}`} icon={<CircleAlert className="size-3.5" />} text={artifact.detail!} failed />
          ))}
        </ol>
      </div>

      {timing?.model ? (
        <div className="border-t border-black/[0.06] px-4 py-2 text-xs text-black/40 dark:border-white/[0.08] dark:text-white/40">
          {timing.model.modelName} · {formatTokens(timing.model.totalTokens)} tokens
        </div>
      ) : null}
    </section>
  );
}

function WorkLogRow({ icon, text, active = false, failed = false }: { icon: React.ReactNode; text: string; active?: boolean; failed?: boolean }) {
  return (
    <li aria-busy={active || undefined} className={`flex items-start gap-2 ${failed ? "text-rose-700 dark:text-rose-300" : ""}`}>
      <span className={`mt-0.5 shrink-0 ${active ? "animate-pulse text-black/55 dark:text-white/55" : "text-black/38 dark:text-white/38"}`} aria-hidden="true">{active ? <CircleDot className="size-3.5" /> : icon}</span>
      <span>{text}</span>
    </li>
  );
}

function stepIcon(step: GenerationStepTiming) {
  if (step.kind === "WRITER") return <FileText className="size-3.5" />;
  if (step.kind === "REVIEWER") return <GitPullRequest className="size-3.5" />;
  return <LoaderCircle className="size-3.5" />;
}

function stepLabel(step: GenerationStepTiming, artifact: GenerationArtifact | undefined, run: GenerationRun): string {
  const suffix = step.durationMs == null ? "" : ` · ${formatDuration(step.durationMs)}`;
  if (step.status === "FAILED") return `${step.kind.toLowerCase()} failed${step.failureCode ? ` (${step.failureCode})` : ""}${suffix}`;
  if (!artifact && step.status === "RUNNING") {
    if (step.kind === "WRITER") return "Drafting from selected references…";
    if (step.kind === "REVIEWER") return "Checking source support…";
    return "Revising failed sentences…";
  }
  if (step.kind === "WRITER") return `Drafted ${countLabel(artifact?.sentenceIds.length ?? 0, "sentence")}${suffix}`;
  if (step.kind === "REVIEWER") {
    const reviews = artifact?.reviews ?? [];
    const supported = reviews.filter((review) => review.verdict === "SUPPORTED").length;
    return `Checked source support — ${supported}/${reviews.length} supported${suffix}`;
  }
  return `Rewrote ${countLabel(artifact?.sentenceIds.length ?? 0, "sentence")} (attempt ${run.semanticRewriteAttempt})${suffix}`;
}

function findArtifact(step: GenerationStepTiming, steps: GenerationStepTiming[], artifacts: GenerationArtifact[]): GenerationArtifact | undefined {
  const kind = `${step.kind}_OUTPUT` as GenerationArtifact["kind"];
  const occurrence = steps.filter((candidate) => candidate.kind === step.kind && candidate.sequence <= step.sequence).length - 1;
  const candidates = artifacts.filter((artifact) => artifact.kind === kind);
  return candidates[occurrence];
}

function headerIcon(status: GenerationRun["status"]) {
  if (status === "FAILED") return <CircleX className="size-4 text-rose-600 dark:text-rose-400" aria-hidden="true" />;
  if (status === "NEEDS_REVIEW" || status === "NEEDS_YOUR_CALL") return <CircleAlert className="size-4 text-amber-600 dark:text-amber-400" aria-hidden="true" />;
  if (!isTerminal(status)) return <LoaderCircle className="size-4 animate-spin text-black/45 dark:text-white/45" aria-hidden="true" />;
  return <Check className="size-4 text-emerald-600 dark:text-emerald-400" aria-hidden="true" />;
}

function headerLabel(status: GenerationRun["status"], elapsedMs: number | null): string {
  if (status === "FAILED") return `Generation failed after ${formatDuration(elapsedMs)}`;
  if (status === "NEEDS_REVIEW" || status === "NEEDS_YOUR_CALL") return `Review needed after ${formatDuration(elapsedMs)}`;
  return `${isTerminal(status) ? "Worked" : "Working"} for ${formatDuration(elapsedMs)}`;
}

function isTerminal(status: GenerationRun["status"]): boolean {
  return status === "READY" || status === "NEEDS_REVIEW" || status === "NEEDS_YOUR_CALL" || status === "FAILED";
}

function countLabel(count: number, singular: string): string {
  return `${count} ${singular}${count === 1 ? "" : "s"}`;
}

function formatDuration(milliseconds: number | null): string {
  if (milliseconds == null || !Number.isFinite(milliseconds)) return "0s";
  const totalSeconds = Math.max(0, Math.round(milliseconds / 1_000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return minutes ? `${minutes}m ${seconds}s` : `${seconds}s`;
}

function formatTokens(tokens: number): string {
  if (tokens < 1_000) return String(tokens);
  return `${(tokens / 1_000).toFixed(1).replace(/\.0$/, "")}k`;
}
