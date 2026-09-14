"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import type {
  ChatAgentRun,
  ChatResponseVersion,
  ChatTurn,
  ContentBrief,
  ContentType,
  SourceReference,
} from "@plot/api-client";
import { isTerminalChatAgentStatus, pollChatAgentRun } from "@/lib/chat-agent-polling";
import { plotApiClient } from "@/lib/api-client";

import {
  isNonRetryableRequestError,
  messageFor,
  pendingAgentRequestKey,
  selectReferences,
  upsertActivity,
  validateSourceSelection,
  type PendingAgentRequest,
} from "@/features/chat/chat-workspace-utils";

type UseChatAgentActivityProps = {
  chatId: string;
  requestedAgentId: string | null;
  requestedArtifactId: string | null;
  requestedVersionId?: string | null;
  references: SourceReference[];
  sourceError: string;
  brief?: ContentBrief;
  contentType?: ContentType;
  onAgentArtifact: (run: ChatAgentRun) => void;
  onAdmitted: (run: ChatAgentRun) => void;
};

function isConfirmedRetryChild(version: ChatResponseVersion, parentVersionId: string): boolean {
  return version.id !== parentVersionId && version.lineageParentVersionId === parentVersionId;
}

export function useChatAgentActivity({
  chatId,
  requestedAgentId,
  requestedArtifactId,
  requestedVersionId = null,
  references,
  sourceError,
  brief,
  contentType = "CHANGELOG",
  onAgentArtifact,
  onAdmitted,
}: UseChatAgentActivityProps) {
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [rawActivities, setRawActivities] = useState<ChatAgentRun[]>([]);
  const [selectedVersionId, setSelectedVersionId] = useState<string | null>(requestedVersionId);
  const [activitiesLoadedFor, setActivitiesLoadedFor] = useState<string | null>(null);
  const [activitiesError, setActivitiesError] = useState("");
  const [agentRun, setAgentRun] = useState<ChatAgentRun | null>(null);
  const [agentError, setAgentError] = useState("");
  const [agentBusy, setAgentBusy] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const [agentInstruction, setAgentInstruction] = useState("");
  const agentAbortRef = useRef<AbortController | null>(null);
  const pendingRequestRef = useRef<PendingAgentRequest | null>(null);
  const retryIdempotencyKeysRef = useRef<Map<string, string>>(new Map());
  const activitiesLoading = activitiesLoadedFor !== chatId;

  const effectiveTurns = useMemo<ChatTurn[]>(() => {
    if (turns.length > 0) return turns;
    if (rawActivities.length === 0) return [];
    return [
      {
        id: `turn-synthetic-${chatId}`,
        workSessionId: chatId,
        turnIndex: 0,
        userMessage: rawActivities[0]?.instruction || "User message",
        createdAt: rawActivities[0]?.createdAt || new Date().toISOString(),
        updatedAt: rawActivities[0]?.updatedAt || new Date().toISOString(),
        selectedVersionId: selectedVersionId ?? rawActivities[rawActivities.length - 1]?.id ?? "",
        versions: rawActivities.map((act, index) => ({
          id: act.id,
          turnId: `turn-synthetic-${chatId}`,
          versionIndex: index,
          lineageParentVersionId: null,
          agentRunId: act.id,
          status: act.status,
          instruction: act.instruction || "",
          failureCode: act.failureCode,
          artifactId: act.artifactId,
          artifact: act.artifact ? {
            id: act.artifact.id,
            status: act.artifact.status,
            title: act.artifact.title,
            contentType: act.contentType,
            updatedAt: act.artifact.updatedAt,
          } : null,
          createdAt: act.createdAt,
          updatedAt: act.updatedAt,
          retryEligibility: {
            eligible: false,
            reason: "AUTHORITATIVE_HISTORY_UNAVAILABLE",
          },
        })),
      },
    ];
  }, [turns, rawActivities, chatId, selectedVersionId]);

  const activities = useMemo(() => {
    if (turns.length > 0) {
      return turns.flatMap((turn) =>
        turn.versions.map((version) => ({
          id: version.agentRunId,
          chatId,
          instruction: version.instruction || turn.userMessage,
          contentType: "CHANGELOG" as ContentType,
          contentProfileRevisionId: null,
          brief: null,
          status: version.status,
          failureCode: version.failureCode,
          artifactId: version.artifactId,
          artifact: version.artifactId
            ? {
                id: version.artifactId,
                status: version.status === "SUCCEEDED" ? "READY" : "DRAFT",
                title: version.artifact?.title || "Generated artifact",
                contentType: "CHANGELOG" as ContentType,
                updatedAt: version.updatedAt,
              }
            : null,
          createdAt: version.createdAt,
          updatedAt: version.updatedAt,
        })),
      );
    }
    return rawActivities;
  }, [turns, chatId, rawActivities]);

  const selectedActivity = useMemo(() => {
    if (selectedVersionId) {
      const verActivity = activities.find((a) => a.id === selectedVersionId);
      if (verActivity) return verActivity;
    }
    if (requestedAgentId) return activities.find((activity) => activity.id === requestedAgentId) ?? null;
    if (requestedArtifactId) {
      const artifactActivity = activities.find((activity) => activity.artifactId === requestedArtifactId);
      if (artifactActivity) return artifactActivity;
    }
    return activities[activities.length - 1] ?? null;
  }, [activities, selectedVersionId, requestedAgentId, requestedArtifactId]);

  const isPendingRun = useMemo(() => {
    if (agentBusy) return true;
    const latestTurn = effectiveTurns[effectiveTurns.length - 1];
    if (!latestTurn) return false;
    return latestTurn.versions.some((v) => v.status === "QUEUED" || v.status === "RUNNING");
  }, [agentBusy, effectiveTurns]);

  useEffect(() => {
    const controller = new AbortController();
    let loadError: unknown = null;
    Promise.all([
      typeof plotApiClient.listChatTurns === "function"
        ? plotApiClient.listChatTurns(chatId, { selectedVersionId: requestedVersionId ?? undefined, signal: controller.signal })
            .catch((err) => { loadError = err; return null; })
        : Promise.resolve(null),
      typeof plotApiClient.listSessionAgentRuns === "function"
        ? plotApiClient.listSessionAgentRuns(chatId, { signal: controller.signal })
            .catch((err) => { if (!loadError) loadError = err; return null; })
        : Promise.resolve(null),
    ])
      .then(([turnsResult, runsResult]) => {
        if (controller.signal.aborted) return;
        if (loadError && !turnsResult && !runsResult) {
          setActivitiesError(messageFor(loadError, "Chat activity could not be loaded."));
          setActivitiesLoadedFor(chatId);
          return;
        }
        if (turnsResult && turnsResult.length > 0) {
          setTurns(turnsResult);
          if (requestedVersionId) {
            setSelectedVersionId(requestedVersionId);
          }
        } else {
          setTurns([]);
        }
        setRawActivities(runsResult || []);
        setActivitiesError("");
        setActivitiesLoadedFor(chatId);
      })
      .catch((error) => {
        if (controller.signal.aborted) return;
        setActivitiesError(messageFor(error, "Chat activity could not be loaded."));
        setActivitiesLoadedFor(chatId);
      });

    return () => {
      controller.abort();
      agentAbortRef.current?.abort();
    };
  }, [chatId, requestedVersionId]);

  useEffect(() => {
    if (!requestedAgentId) return;
    const agentId = requestedAgentId;
    agentAbortRef.current?.abort();

    const controller = new AbortController();
    agentAbortRef.current = controller;
    queueMicrotask(() => {
      if (agentAbortRef.current !== controller) return;
      setAgentBusy(true);
      setAgentError("");
    });

    async function restoreAgent() {
      try {
        const current = await plotApiClient.getChatAgentRun(agentId, { signal: controller.signal });
        if (agentAbortRef.current !== controller) return;
        if (current.chatId !== chatId) throw new Error("That Agent request is not part of this chat.");
        setAgentRun(current);
        const restored = current.artifactId || isTerminalChatAgentStatus(current.status)
          ? current
          : await pollChatAgentRun(plotApiClient, current.id, {
              signal: controller.signal,
              initialRun: current,
              onUpdate: (next) => {
                if (agentAbortRef.current === controller) {
                  setAgentRun(next);
                  upsertActivity(setRawActivities, next);
                }
              },
            });
        if (agentAbortRef.current !== controller) return;
        setAgentRun(restored);
        if (restored.artifactId) onAgentArtifact(restored);
      } catch (error) {
        if (agentAbortRef.current === controller && !(error instanceof DOMException && error.name === "AbortError")) {
          setAgentError(messageFor(error, "The Agent request could not be loaded."));
        }
      } finally {
        if (agentAbortRef.current === controller) setAgentBusy(false);
      }
    }

    void restoreAgent();
    return () => {
      controller.abort();
      if (agentAbortRef.current === controller) {
        agentAbortRef.current = null;
        setAgentBusy(false);
      }
    };
  }, [chatId, onAgentArtifact, requestedAgentId]);

  const selectVersion = useCallback((turnId: string, versionId: string) => {
    setSelectedVersionId(versionId);
    setTurns((prev) =>
      prev.map((t) => (t.id === turnId ? { ...t, selectedVersionId: versionId } : t)),
    );
    const turn = effectiveTurns.find((t) => t.id === turnId);
    const ver = turn?.versions.find((v) => v.id === versionId);
    if (ver?.artifactId) {
      onAgentArtifact({
        id: ver.agentRunId,
        chatId,
        instruction: ver.instruction,
        contentType,
        contentProfileRevisionId: null,
        brief: null,
        status: ver.status,
        failureCode: ver.failureCode,
        artifactId: ver.artifactId,
        artifact: {
          id: ver.artifactId,
          status: ver.status === "SUCCEEDED" ? "READY" : "DRAFT",
          title: ver.artifact?.title || "Generated artifact",
          contentType,
          updatedAt: ver.updatedAt,
        },
        createdAt: ver.createdAt,
        updatedAt: ver.updatedAt,
      });
    }
  }, [chatId, contentType, effectiveTurns, onAgentArtifact]);

  const retryResponse = useCallback(async (versionId: string) => {
    if (retrying || isPendingRun) return;
    setRetrying(true);
    setAgentError("");
    let idempotencyKey = retryIdempotencyKeysRef.current.get(versionId);
    if (!idempotencyKey) {
      idempotencyKey = crypto.randomUUID();
      retryIdempotencyKeysRef.current.set(versionId, idempotencyKey);
    }
    const controller = new AbortController();
    agentAbortRef.current = controller;

    try {
      const newVersion = await plotApiClient.retryChatResponse(versionId, idempotencyKey, { signal: controller.signal });
      if (isConfirmedRetryChild(newVersion, versionId)) {
        retryIdempotencyKeysRef.current.delete(versionId);
      }
      setSelectedVersionId(newVersion.id);
      setTurns((prev) => {
        const latestIdx = prev.length - 1;
        if (latestIdx < 0) return prev;
        const latest = prev[latestIdx]!;
        const updatedVersions = [...latest.versions.filter((v) => v.id !== newVersion.id), newVersion];
        return [
          ...prev.slice(0, latestIdx),
          { ...latest, selectedVersionId: newVersion.id, versions: updatedVersions },
        ];
      });

      const fakeRun: ChatAgentRun = {
        id: newVersion.agentRunId,
        chatId,
        instruction: newVersion.instruction,
        contentType,
        contentProfileRevisionId: null,
        brief: null,
        status: newVersion.status,
        failureCode: null,
        artifactId: null,
        artifact: null,
        createdAt: newVersion.createdAt,
        updatedAt: newVersion.updatedAt,
      };
      setAgentRun(fakeRun);
      onAdmitted(fakeRun);

      const completedRun = await pollChatAgentRun(plotApiClient, newVersion.agentRunId, {
        signal: controller.signal,
        initialRun: fakeRun,
        onUpdate: (next) => {
          setAgentRun(next);
        },
      });
      setAgentRun(completedRun);
      if (completedRun.artifactId) onAgentArtifact(completedRun);

      // Refresh turns to get finalized status and retryEligibility
      const freshTurns = await plotApiClient.listChatTurns(chatId, { signal: controller.signal });
      if (freshTurns) setTurns(freshTurns);
    } catch (err) {
      if (!(err instanceof DOMException && err.name === "AbortError")) {
        // Reconcile indeterminate state
        try {
          const freshTurns = await plotApiClient.listChatTurns(chatId);
          if (freshTurns) {
            setTurns(freshTurns);
            if (freshTurns.some((turn) => turn.versions.some((version) => isConfirmedRetryChild(version, versionId)))) {
              retryIdempotencyKeysRef.current.delete(versionId);
            }
          }
        } catch {
          // ignore
        }
        setAgentError(messageFor(err, "Retry failed"));
      }
    } finally {
      setRetrying(false);
    }
  }, [chatId, contentType, isPendingRun, onAdmitted, onAgentArtifact, retrying]);

  async function submitMessage(message: string, referenceIds: string[], onRequestStart?: () => void) {
    const selected = selectReferences(references, referenceIds);
    const validationError = validateSourceSelection(references, selected, sourceError);
    if (validationError) {
      setAgentError(validationError);
      return;
    }
    onRequestStart?.();
    agentAbortRef.current?.abort();
    const controller = new AbortController();
    agentAbortRef.current = controller;
    const idempotencyKey = pendingAgentRequestKey(pendingRequestRef, message, selected.map((reference) => reference.id));
    setAgentInstruction(message);
    setAgentRun(null);
    setAgentBusy(true);
    setAgentError("");
    let admitted = false;
    try {
      const run = await plotApiClient.createChatAgentRun({
        instruction: message,
        writingBlockIds: selected.map((reference) => reference.id),
        workSessionId: chatId,
        contentType,
        brief,
      }, idempotencyKey, { signal: controller.signal });
      if (agentAbortRef.current !== controller || controller.signal.aborted) return;
      admitted = true;
      pendingRequestRef.current = null;
      setAgentRun(run);
      onAdmitted(run);

      // Fetch updated turns
      const turnsUpdate = await plotApiClient.listChatTurns(chatId, { signal: controller.signal }).catch(() => null);
      if (turnsUpdate && turnsUpdate.length > 0) {
        setTurns(turnsUpdate);
      }
    } catch (error) {
      if (agentAbortRef.current === controller && !(error instanceof DOMException && error.name === "AbortError")) {
        if (isNonRetryableRequestError(error)) pendingRequestRef.current = null;
        setAgentError(messageFor(error, "The request could not be started."));
      }
    } finally {
      if (agentAbortRef.current === controller && !admitted) setAgentBusy(false);
    }
  }

  return {
    turns: effectiveTurns,
    activities,
    selectedActivity,
    selectedVersionId,
    selectVersion,
    retryResponse,
    retrying,
    isPendingRun,
    activitiesLoading,
    activitiesError,
    agentRun,
    agentError,
    agentBusy,
    agentInstruction,
    submitMessage,
  };
}
