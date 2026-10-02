"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { PlotApiError } from "@plot/api-client";
import type {
  ChatModel,
  ChatReasoningEffort,
  ChatAgentRun,
  ChatRunSnapshot,
  ChatResponseVersion,
  ChatTurn,
} from "@plot/api-client";
import { abortableDelay, isTerminalChatAgentStatus, watchChatAgentRun } from "@/lib/chat-agent-stream";
import { getSelectedWorkspaceId, plotApiClient } from "@/lib/api-client";

import {
  isNonRetryableRequestError,
  messageFor,
  pendingAgentRequestKey,
  upsertActivity,
  type PendingAgentRequest,
} from "@/features/chat/chat-workspace-utils";

type UseChatAgentActivityProps = {
  chatId: string;
  requestedAgentId: string | null;
  requestedArtifactId: string | null;
  requestedVersionId?: string | null;
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
  const selectedRunIdRef = useRef<string | null>(null);
  const watchAbortRef = useRef<AbortController | null>(null);
  const [admission, setAdmission] = useState<{ id: string; requestedAgentId: string | null } | null>(null);
  const historyReadRef = useRef(0);
  const [progress, setProgress] = useState<ChatRunSnapshot | null>(null);
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
          responseText: act.responseText,
          artifactId: act.artifactId,
          artifact: act.artifact ? {
            id: act.artifact.id,
            status: act.artifact.status,
            title: act.artifact.title,
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
          status: version.status,
          failureCode: version.failureCode,
          responseText: version.responseText,
          artifactId: version.artifactId,
          artifact: version.artifactId
            ? {
                id: version.artifactId,
                status: version.status === "SUCCEEDED" ? "READY" : "DRAFT",
                title: version.artifact?.title || "Generated artifact",
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

  const historySelection = useMemo(() => {
    if (selectedVersionId) {
      const version = effectiveTurns.flatMap((turn) => turn.versions).find((v) => v.id === selectedVersionId);
      const verActivity = activities.find((a) => a.id === version?.agentRunId);
      if (verActivity) return verActivity;
    }
    if (requestedAgentId) return activities.find((activity) => activity.id === requestedAgentId) ?? null;
    if (requestedArtifactId) {
      const artifactActivity = activities.find((activity) => activity.artifactId === requestedArtifactId);
      if (artifactActivity) return artifactActivity;
    }
    return activities[activities.length - 1] ?? null;
  }, [activities, effectiveTurns, selectedVersionId, requestedAgentId, requestedArtifactId]);

  const selectedActivity = agentRun?.id === historySelection?.id ? agentRun : historySelection;

  useEffect(() => { selectedRunIdRef.current = selectedActivity?.id ?? null; }, [selectedActivity?.id]);

  const isPendingRun = useMemo(() => {
    if (agentBusy) return true;
    const latestTurn = effectiveTurns[effectiveTurns.length - 1];
    if (!latestTurn) return false;
    return latestTurn.versions.some((v) => v.status === "QUEUED" || v.status === "RUNNING");
  }, [agentBusy, effectiveTurns]);

  useEffect(() => {
    const controller = new AbortController();
    queueMicrotask(() => {
      if (!controller.signal.aborted) setSelectedVersionId(requestedVersionId);
    });
    return () => controller.abort();
  }, [requestedAgentId, requestedVersionId]);

  useEffect(() => {
    const controller = new AbortController();
    const historyRead = ++historyReadRef.current;
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
        setActivitiesLoadedFor(chatId);
        if (historyRead !== historyReadRef.current) return;
        if (loadError && !turnsResult && !runsResult) {
          setActivitiesError(messageFor(loadError, "Chat activity could not be loaded."));
          setActivitiesLoadedFor(chatId);
          return;
        }
        if (turnsResult && turnsResult.length > 0) {
          setTurns(turnsResult);
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
    };
  }, [chatId, requestedVersionId]);

  const activeHistoryRunId = activities.findLast((run) => !isTerminalChatAgentStatus(run.status))?.id ?? null;
  const admittedRunId = admission && (requestedAgentId === admission.id ||
    (requestedAgentId === admission.requestedAgentId && agentRun?.id === admission.id)) ? admission.id : null;
  const watchedRunId = admittedRunId ?? activeHistoryRunId ?? requestedAgentId ?? agentRun?.id ?? null;

  useEffect(() => {
    if (!watchedRunId) return;
    const controller = new AbortController();
    const workspaceId = getSelectedWorkspaceId();
    watchAbortRef.current = controller;
    const isCurrent = () => !controller.signal.aborted && watchAbortRef.current === controller && getSelectedWorkspaceId() === workspaceId;
    queueMicrotask(() => {
      if (!isCurrent()) return;
      setAgentBusy(true);
      setAgentError("");
      setProgress((current) => current?.runId === watchedRunId ? current : null);
    });
    async function restoreAgent() {
      try {
        let current: ChatAgentRun;
        let delay = 500;
        while (true) {
          try {
            current = await plotApiClient.getChatAgentRun(watchedRunId!, { signal: controller.signal });
            break;
          } catch (error) {
            if (!isCurrent() || (error instanceof PlotApiError && [401, 403, 404].includes(error.status))) throw error;
            await abortableDelay(delay, controller.signal);
            delay = Math.min(delay * 2, 4_000);
          }
        }
        if (!isCurrent()) return;
        if (current.chatId !== chatId) throw new Error("That Agent request is not part of this chat.");
        setAgentRun(current);
        const restored = await watchChatAgentRun(plotApiClient, current.id, {
          signal: controller.signal,
          initialRun: current,
          onUpdate: (next) => {
            if (!isCurrent()) return;
            setAgentRun(next);
            upsertActivity(setRawActivities, next);
          },
          onProgress: (next) => {
            if (!isCurrent() || next.runId !== current.id) return;
            setProgress((previous) => previous?.runId === next.runId &&
              (next.epoch < previous.epoch || (next.epoch === previous.epoch && next.revision <= previous.revision)) ? previous : next);
          },
        });
        if (!isCurrent()) return;
        setAgentRun(restored);
        upsertActivity(setRawActivities, restored);
        if (restored.artifactId && (!selectedRunIdRef.current || selectedRunIdRef.current === restored.id)) onAgentArtifact(restored);
        if (typeof plotApiClient.listChatTurns === "function") {
          const historyRead = ++historyReadRef.current;
          let refreshedTurns: ChatTurn[];
          delay = 500;
          while (true) {
            try {
              refreshedTurns = await plotApiClient.listChatTurns(chatId, { signal: controller.signal });
              break;
            } catch (error) {
              if (!isCurrent() || (error instanceof PlotApiError && [401, 403, 404].includes(error.status))) throw error;
              await abortableDelay(delay, controller.signal);
              delay = Math.min(delay * 2, 4_000);
            }
          }
          if (!isCurrent() || historyRead !== historyReadRef.current) return;
          setTurns((previous) => refreshedTurns.map((turn) => {
            const selection = previous.find((item) => item.id === turn.id)?.selectedVersionId;
            return selection && turn.versions.some((version) => version.id === selection) ? { ...turn, selectedVersionId: selection } : turn;
          }));
        }
      } catch (error) {
        if (isCurrent() && !(error instanceof DOMException && error.name === "AbortError")) {
          setAgentError(messageFor(error, "The Agent request could not be loaded."));
        }
      } finally {
        if (isCurrent()) { setAgentBusy(false); setRetrying(false); }
      }
    }
    void restoreAgent();
    return () => {
      controller.abort();
      if (watchAbortRef.current === controller) watchAbortRef.current = null;
    };
  }, [chatId, onAgentArtifact, watchedRunId]);

  useEffect(() => () => { agentAbortRef.current?.abort(); }, [chatId]);

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
        status: ver.status,
        failureCode: ver.failureCode,
        responseText: ver.responseText,
        artifactId: ver.artifactId,
        artifact: {
          id: ver.artifactId,
          status: ver.status === "SUCCEEDED" ? "READY" : "DRAFT",
          title: ver.artifact?.title || "Generated artifact",
          updatedAt: ver.updatedAt,
        },
        createdAt: ver.createdAt,
        updatedAt: ver.updatedAt,
      });
    }
  }, [chatId, effectiveTurns, onAgentArtifact]);

  const retryResponse = useCallback(async (versionId: string) => {
    if (retrying || isPendingRun) return;
    setRetrying(true);
    setAgentError("");
    let idempotencyKey = retryIdempotencyKeysRef.current.get(versionId);
    if (!idempotencyKey) {
      idempotencyKey = crypto.randomUUID();
      retryIdempotencyKeysRef.current.set(versionId, idempotencyKey);
    }
    agentAbortRef.current?.abort();
    const controller = new AbortController();
    const workspaceId = getSelectedWorkspaceId();
    agentAbortRef.current = controller;
    const isCurrent = () => !controller.signal.aborted && agentAbortRef.current === controller && getSelectedWorkspaceId() === workspaceId;

    try {
      const newVersion = await plotApiClient.retryChatResponse(versionId, idempotencyKey, { signal: controller.signal });
      if (!isCurrent()) return;
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

      const admittedRun: ChatAgentRun = {
        id: newVersion.agentRunId,
        chatId,
        instruction: newVersion.instruction,
        status: newVersion.status,
        failureCode: null,
        responseText: null,
        artifactId: null,
        artifact: null,
        createdAt: newVersion.createdAt,
        updatedAt: newVersion.updatedAt,
      };
      setAgentRun(admittedRun);
      onAdmitted(admittedRun);

      setAdmission({ id: admittedRun.id, requestedAgentId });
    } catch (err) {
      if (isCurrent() && !(err instanceof DOMException && err.name === "AbortError")) {
        // Reconcile indeterminate state
        try {
          const historyRead = ++historyReadRef.current;
          const freshTurns = await plotApiClient.listChatTurns(chatId, { signal: controller.signal });
          if (!isCurrent() || historyRead !== historyReadRef.current) return;
          if (freshTurns) {
            setTurns(freshTurns);
            if (freshTurns.some((turn) => turn.versions.some((version) => isConfirmedRetryChild(version, versionId)))) {
              retryIdempotencyKeysRef.current.delete(versionId);
            }
          }
        } catch {
          // ignore
        }
        if (isCurrent()) setAgentError(messageFor(err, "Retry failed"));
      }
    } finally {
      if (isCurrent()) setRetrying(false);
    }
  }, [chatId, isPendingRun, onAdmitted, requestedAgentId, retrying]);

  async function submitMessage(
    message: string,
    onRequestStart?: () => void,
    skillIds: string[] = [],
    model: ChatModel = "auto",
    reasoningEffort: ChatReasoningEffort = "medium",
  ) {
    onRequestStart?.();
    agentAbortRef.current?.abort();
    const controller = new AbortController();
    agentAbortRef.current = controller;
    const workspaceId = getSelectedWorkspaceId();
    const isCurrent = () => !controller.signal.aborted && agentAbortRef.current === controller && getSelectedWorkspaceId() === workspaceId;
    const idempotencyKey = pendingAgentRequestKey(
      pendingRequestRef,
      message,
      [],
      JSON.stringify({ skillIds, model, reasoningEffort }),
    );
    setAgentInstruction(message);
    setAgentRun(null);
    setAgentBusy(true);
    setAgentError("");
    let admitted = false;
    try {
      const run = await plotApiClient.createChatAgentRun({
        instruction: message,
        writingBlockIds: [],
        workSessionId: chatId,
        skillIds,
        model,
        reasoningEffort,
      }, idempotencyKey, { signal: controller.signal });
      if (!isCurrent()) return;
      admitted = true;
      pendingRequestRef.current = null;
      setSelectedVersionId(null);
      setAgentRun(run);
      onAdmitted(run);

      setAdmission({ id: run.id, requestedAgentId });
      const historyRead = ++historyReadRef.current;
      const turnsUpdate = await plotApiClient.listChatTurns(chatId, { signal: controller.signal }).catch(() => null);
      if (isCurrent() && historyRead === historyReadRef.current && turnsUpdate?.length) setTurns(turnsUpdate);
    } catch (error) {
      if (isCurrent() && !(error instanceof DOMException && error.name === "AbortError")) {
        if (isNonRetryableRequestError(error)) pendingRequestRef.current = null;
        setAgentError(messageFor(error, "The request could not be started."));
      }
    } finally {
      if (isCurrent() && !admitted) setAgentBusy(false);
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
    agentRun: progress && agentRun?.id === progress.runId ? {
      ...agentRun, responseText: progress.responseText, status: progress.status, failureCode: progress.failureCode,
    } : agentRun,
    progress,
    agentError,
    agentBusy,
    agentInstruction,
    submitMessage,
  };
}
