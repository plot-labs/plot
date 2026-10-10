import type { ChatReasoningEffort } from "@plot/api-client";

export const REASONING_EFFORT_OPTIONS: readonly {
  value: ChatReasoningEffort;
  label: string;
}[] = [
  { value: "none", label: "None" },
  { value: "minimal", label: "Minimal" },
  { value: "low", label: "Low" },
  { value: "medium", label: "Medium" },
  { value: "high", label: "High" },
  { value: "xhigh", label: "Extra high" },
  { value: "max", label: "Max" },
];

const ALL_REASONING_EFFORTS: readonly ChatReasoningEffort[] = REASONING_EFFORT_OPTIONS.map((option) => option.value);

type ReasoningModel = {
  reasoningEfforts?: readonly ChatReasoningEffort[];
  reasoningDefault?: ChatReasoningEffort;
};

/** The efforts a model accepts; a model that declares none accepts every effort. */
export function reasoningEffortsFor(model: ReasoningModel): readonly ChatReasoningEffort[] {
  return model.reasoningEfforts ?? ALL_REASONING_EFFORTS;
}

/**
 * Picks the effort to fall back to when the current one is unsupported: the
 * model's default, then the nearest to medium. Null when nothing is supported.
 */
export function preferredReasoningEffort(
  model: ReasoningModel,
  supported: readonly ChatReasoningEffort[],
): ChatReasoningEffort | null {
  const preferred: (ChatReasoningEffort | undefined)[] = [
    model.reasoningDefault,
    "medium",
    "high",
    "low",
    "minimal",
    "none",
    "xhigh",
    "max",
  ];
  return preferred.find((effort): effort is ChatReasoningEffort => effort !== undefined && supported.includes(effort))
    ?? supported[0]
    ?? null;
}
