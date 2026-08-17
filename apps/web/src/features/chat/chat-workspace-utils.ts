import type { Dispatch, SetStateAction } from "react";

import type { ChatAgentRun, SourceReference } from "@plot/api-client";

export type PendingAgentRequest = { key: string; fingerprint: string };

export function chatHref(chatId: string, agentRunId: string | null = null, artifactId: string | null = null) {
  const params = new URLSearchParams({ chat: chatId });
  if (agentRunId) params.set("agent", agentRunId);
  if (artifactId) params.set("artifact", artifactId);
  return `/chat?${params.toString()}`;
}

export function toComposerReferences(references: SourceReference[]) {
  return references.map((reference) => ({ id: reference.id, label: `${reference.repositoryLabel} · ${reference.sourceLabel}`, available: true, groupId: reference.sourceScopeId }));
}

export function selectReferences(references: SourceReference[], ids: string[]) {
  return references.filter((reference) => ids.includes(reference.id));
}

export function validateSourceSelection(all: SourceReference[], _selected: SourceReference[], sourceError: string) {
  if (sourceError) return sourceError;
  if (!all.length) return "Connect and import a source before starting a Chat.";
  return "";
}

export function pendingAgentRequestKey(ref: { current: PendingAgentRequest | null }, instruction: string, writingBlockIds: string[]) {
  const fingerprint = `${instruction}\u0000${writingBlockIds.join("\u0000")}`;
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

export function agentStatusLabel(status: ChatAgentRun["status"]) {
  const label = status.toLowerCase().replaceAll("_", " ");
  return label.charAt(0).toUpperCase() + label.slice(1);
}

export function agentProgressLabel(status: ChatAgentRun["status"]) {
  if (status === "QUEUED") return "Queued to explore the connected sources…";
  if (status === "RUNNING") return "Reading the connected sources…";
  if (status === "SUCCEEDED") return "Source exploration is complete. Preparing the artifact…";
  return "Source exploration stopped before an artifact was produced.";
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
      artifact: next.artifact ?? existing.artifact,
      instruction: next.instruction || existing.instruction,
    };
    return updated.sort(compareActivity);
  });
}

function compareActivity(left: ChatAgentRun, right: ChatAgentRun) {
  return left.createdAt.localeCompare(right.createdAt) || left.id.localeCompare(right.id);
}
