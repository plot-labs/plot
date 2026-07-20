import { AlertTriangle, Check, CircleDot, PencilLine, XCircle } from "lucide-react";

import type { GenerationSentence } from "@plot/api-client";

export function SentenceReviewState({ sentence }: { sentence: GenerationSentence }) {
  if (!sentence.verdict || sentence.verdict === "NOT_REQUIRED") return null;

  const states = {
    SUPPORTED: { label: "Verified", Icon: Check, tone: "text-emerald-800 dark:text-emerald-300" },
    NEEDS_SUPPORT: { label: "Needs support", Icon: AlertTriangle, tone: "text-amber-800 dark:text-amber-300" },
    CONFLICT: { label: "Conflicting sources", Icon: CircleDot, tone: "text-rose-800 dark:text-rose-300" },
    USER_MODIFIED: { label: "Edited · unverified", Icon: PencilLine, tone: "text-violet-800 dark:text-violet-300" },
    REVIEW_FAILED: { label: "Review failed", Icon: XCircle, tone: "text-rose-800 dark:text-rose-300" },
  } as const;
  const state = states[sentence.verdict];

  return (
    <div className="mt-2">
      <span role="status" aria-label={state.label} className={`inline-flex items-center gap-1.5 text-xs font-semibold ${state.tone}`}>
        <state.Icon className="size-3.5" aria-hidden="true" />
        {state.label}
      </span>
      {sentence.reason && sentence.verdict !== "SUPPORTED" ? (
        <p className="mt-1 text-xs leading-5 text-black/55 dark:text-white/55">{sentence.reason}</p>
      ) : null}
    </div>
  );
}
