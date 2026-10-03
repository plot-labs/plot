import type { Dispatch, SetStateAction } from "react";

import type { ChatAgentRun, ChatRunSnapshot } from "@plot/api-client";

export type PendingAgentRequest = { key: string; fingerprint: string };

export function chatHref(
  chatId: string,
  agentRunId: string | null = null,
  artifactId: string | null = null,
  versionId: string | null = null,
) {
  const params = new URLSearchParams({ chat: chatId });
  if (versionId) params.set("version", versionId);
  if (agentRunId) params.set("agent", agentRunId);
  if (artifactId) params.set("artifact", artifactId);
  return `/chat?${params.toString()}`;
}

export function pendingAgentRequestKey(ref: { current: PendingAgentRequest | null }, instruction: string, writingBlockIds: string[], settings = "") {
  const fingerprint = `${instruction}\u0000${writingBlockIds.join("\u0000")}\u0000${settings}`;
  if (ref.current?.fingerprint === fingerprint) return ref.current.key;
  const next = { key: crypto.randomUUID(), fingerprint };
  ref.current = next;
  return next.key;
}

export function isNonRetryableRequestError(error: unknown) {
  const status = error && typeof error === "object" && "status" in error ? error.status : null;
  return typeof status === "number" && status >= 400 && status < 500 && status !== 408 && status !== 429;
}

export function messageFor(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

export function formatActivity(value: string | null) {
  if (!value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}


export const CONNECTION_ERROR_CODES = new Set([
  "SOURCE_NOT_READY",
  "GITHUB_ACCESS_DENIED",
  "GITHUB_NOT_FOUND",
  "INVALID_SESSION",
  "REPOSITORY_INACTIVE",
]);


export function agentStatusLabel(status: ChatAgentRun["status"]) {
  const label = status.toLowerCase().replaceAll("_", " ");
  return label.charAt(0).toUpperCase() + label.slice(1);
}

const RUNNING_PHASE_LABELS: Partial<Record<ChatRunSnapshot["phase"], string>> = {
  RESEARCHING: "Reading the linked changes…",
  RESPONDING: "Writing a reply…",
  WRITING: "Drafting the content…",
  REVIEWING: "Checking the draft against its sources…",
  REWRITING: "Revising the draft…",
};

const DOCUMENT_PHASE_STATUS: Partial<Record<ChatRunSnapshot["phase"], string>> = {
  WRITING: "Drafting",
  REVIEWING: "Checking sources",
  REWRITING: "Revising",
};

export function agentProgressLabel(status: ChatAgentRun["status"], phase?: ChatRunSnapshot["phase"] | null) {
  if (status === "QUEUED") return "Queued…";
  if (status === "RUNNING") return (phase && RUNNING_PHASE_LABELS[phase]) || "Plot is working on a response…";
  if (status === "SUCCEEDED") return "Response complete.";
  return "Plot could not complete the response.";
}

/** Short status for the document panel while a draft streams in. */
export function documentPhaseStatus(phase?: ChatRunSnapshot["phase"] | null) {
  return (phase && DOCUMENT_PHASE_STATUS[phase]) || "Generating";
}

export function upsertActivity(setActivities: Dispatch<SetStateAction<ChatAgentRun[]>>, next: ChatAgentRun) {
  setActivities((current) => {
    const existingIndex = current.findIndex((activity) => activity.id === next.id);
    if (existingIndex < 0) return [...current, next].sort(compareActivity);
    const updated = [...current];
    const existing = updated[existingIndex]!;
    updated[existingIndex] = {
      ...existing,
      ...next,
      createdAt: existing.createdAt || next.createdAt,
      updatedAt: next.updatedAt || existing.updatedAt,
      failureCode: next.failureCode ?? existing.failureCode,
      responseText: next.responseText ?? existing.responseText,
      artifact: next.artifact ?? existing.artifact,
      instruction: next.instruction || existing.instruction,
    };
    return updated.sort(compareActivity);
  });
}

function compareActivity(left: ChatAgentRun, right: ChatAgentRun) {
  return left.createdAt.localeCompare(right.createdAt) || left.id.localeCompare(right.id);
}
