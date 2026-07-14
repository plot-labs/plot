"use client";

import { AlertTriangle } from "lucide-react";
import { useState } from "react";

import type { GenerationRun } from "@plot/api-client";

type ResolutionInput = {
  expectedVersion: number;
  action: "PREFER_SOURCE" | "OMIT_CLAIM" | "PROVIDE_WORDING";
  preferredEvidenceId?: string;
  providedWording?: string;
};

type InterventionPanelProps = {
  run: GenerationRun;
  onResolve: (input: ResolutionInput) => Promise<GenerationRun>;
  onResolved?: (run: GenerationRun) => void;
};

export function InterventionPanel({ run, onResolve, onResolved }: InterventionPanelProps) {
  const intervention = run.pendingIntervention;
  const evidence = run.evidence.filter((item) => intervention?.evidenceIds.includes(item.id));
  const [action, setAction] = useState<ResolutionInput["action"] | null>(null);
  const [evidenceId, setEvidenceId] = useState("");
  const [wording, setWording] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [message, setMessage] = useState("");

  if (!intervention) return null;

  const valid = action === "OMIT_CLAIM" || (action === "PREFER_SOURCE" && evidenceId) || (action === "PROVIDE_WORDING" && wording.trim());

  async function submit() {
    if (!action || !valid || submitting || submitted) return;
    setSubmitting(true);
    setMessage("");
    const input: ResolutionInput = { expectedVersion: intervention!.version, action };
    if (action === "PREFER_SOURCE") input.preferredEvidenceId = evidenceId;
    if (action === "PROVIDE_WORDING") input.providedWording = wording;
    try {
      const next = await onResolve(input);
      setSubmitted(true);
      setMessage("Resolution accepted. Review is continuing.");
      onResolved?.(next);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "The resolution could not be submitted.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section aria-labelledby="intervention-heading" className="rounded-xl border border-amber-300/60 bg-amber-50/70 p-4 dark:border-amber-400/25 dark:bg-amber-400/[0.07] sm:p-5">
      <div className="flex items-start gap-3">
        <AlertTriangle className="mt-0.5 size-5 shrink-0 text-amber-700 dark:text-amber-300" />
        <div>
          <h2 id="intervention-heading" className="font-semibold text-black/88 dark:text-white/90">Needs your call</h2>
          <p className="mt-1 text-sm leading-6 text-black/62 dark:text-white/62">{intervention.reason}</p>
        </div>
      </div>

      <fieldset disabled={submitting || submitted} className="mt-4 space-y-2">
        <legend className="sr-only">Choose how to resolve the sentence</legend>
        <ResolutionChoice checked={action === "PREFER_SOURCE"} label="Prefer a source" onChange={() => setAction("PREFER_SOURCE")} />
        {action === "PREFER_SOURCE" ? (
          <div className="ml-7 grid gap-2 sm:grid-cols-2">
            {evidence.map((item) => (
              <label key={item.id} className="flex min-h-11 cursor-pointer items-center gap-2 rounded-lg border border-black/10 bg-white/75 px-3 text-sm dark:border-white/10 dark:bg-white/[0.04]">
                <input type="radio" name="preferred-evidence" checked={evidenceId === item.id} onChange={() => setEvidenceId(item.id)} />
                <span>{providerName(item.provider)} · {item.sourceLabel}</span>
              </label>
            ))}
          </div>
        ) : null}
        <ResolutionChoice checked={action === "OMIT_CLAIM"} label="Omit this claim" onChange={() => setAction("OMIT_CLAIM")} />
        <ResolutionChoice checked={action === "PROVIDE_WORDING"} label="Provide exact wording" onChange={() => setAction("PROVIDE_WORDING")} />
        {action === "PROVIDE_WORDING" ? (
          <div className="ml-7">
            <label htmlFor="exact-sentence-wording" className="text-xs font-semibold text-black/55 dark:text-white/55">Exact sentence wording</label>
            <textarea id="exact-sentence-wording" value={wording} onChange={(event) => setWording(event.target.value)} className="mt-1 min-h-24 w-full rounded-lg border border-black/15 bg-white p-3 text-sm leading-6 outline-none focus:border-black/40 dark:border-white/15 dark:bg-[#18181b]" />
          </div>
        ) : null}
      </fieldset>
      <button type="button" disabled={!valid || submitting || submitted} onClick={() => void submit()} className="mt-4 min-h-11 rounded-lg bg-black px-4 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-35 dark:bg-white dark:text-black">
        {submitting ? "Resolving…" : submitted ? "Resolution submitted" : "Resolve and continue"}
      </button>
      <p className="mt-2 text-xs text-black/58 dark:text-white/58" aria-live="polite">{message}</p>
    </section>
  );
}

function ResolutionChoice({ checked, label, onChange }: { checked: boolean; label: string; onChange: () => void }) {
  return (
    <label className="flex min-h-11 cursor-pointer items-center gap-2 rounded-lg px-2 text-sm font-medium hover:bg-black/[0.035] dark:hover:bg-white/[0.05]">
      <input type="radio" name="resolution-action" checked={checked} onChange={onChange} />
      {label}
    </label>
  );
}

function providerName(provider: string) {
  return provider === "GITHUB" ? "GitHub" : provider === "SLACK" ? "Slack" : "Linear";
}
