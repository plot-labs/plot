"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import type { ChatAgentRun, SourceReference } from "@plot/api-client";
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
  references: SourceReference[];
  sourceError: string;
  onAgentArtifact: (run: ChatAgentRun) => void;
  onAdmitted: (run: ChatAgentRun) => void;
};

export function useChatAgentActivity({
  chatId,
  requestedAgentId,
  requestedArtifactId,
  references,
  sourceError,
  onAgentArtifact,
  onAdmitted,
}: UseChatAgentActivityProps) {
  const [activities, setActivities] = useState<ChatAgentRun[]>([]);
  const [activitiesLoadedFor, setActivitiesLoadedFor] = useState<string | null>(null);
  const [activitiesError, setActivitiesError] = useState("");
  const [agentRun, setAgentRun] = useState<ChatAgentRun | null>(null);
  const [agentError, setAgentError] = useState("");
  const [agentBusy, setAgentBusy] = useState(false);
  const [agentInstruction, setAgentInstruction] = useState("");
  const agentAbortRef = useRef<AbortController | null>(null);
  const pendingRequestRef = useRef<PendingAgentRequest | null>(null);
  const activitiesLoading = activitiesLoadedFor !== chatId;

  const selectedActivity = useMemo(() => {
    if (requestedAgentId) return activities.find((activity) => activity.id === requestedAgentId) ?? null;
    if (requestedArtifactId) {
      const artifactActivity = activities.find((activity) => activity.artifactId === requestedArtifactId);
      if (artifactActivity) return artifactActivity;
    }
    return activities[activities.length - 1] ?? null;
  }, [activities, requestedAgentId, requestedArtifactId]);

  useEffect(() => {
    const controller = new AbortController();
    void plotApiClient.listSessionAgentRuns(chatId, { signal: controller.signal })
      .then((value) => {
        if (controller.signal.aborted) return;
        setActivities(value);
        setActivitiesError("");
        setActivitiesLoadedFor(chatId);
      })
      .catch((error) => {
        if (controller.signal.aborted) return;
        setActivitiesError(messageFor(error, "Chat activity could not be loaded."));
        setActivitiesLoadedFor(chatId);
      });
    return () => controller.abort();
  }, [chatId]);

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
                  upsertActivity(setActivities, next);
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

  async function submitMessage(message: string, referenceIds: string[]) {
    const selected = selectReferences(references, referenceIds);
    const validationError = validateSourceSelection(references, selected, sourceError);
    if (validationError) {
      setAgentError(validationError);
      return;
    }
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
      }, idempotencyKey, { signal: controller.signal });
      if (agentAbortRef.current !== controller || controller.signal.aborted) return;
      admitted = true;
      pendingRequestRef.current = null;
      setAgentRun(run);
      onAdmitted(run);
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
    activities,
    selectedActivity,
    activitiesLoading,
    activitiesError,
    agentRun,
    agentError,
    agentBusy,
    agentInstruction,
    submitMessage,
  };
}
