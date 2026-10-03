"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { PlotApiError, type Artifact } from "@plot/api-client";
import type { SaveArtifactInput } from "@/features/citations/tiptap-draft-editor";
import { getSelectedWorkspaceId, plotApiClient } from "@/lib/api-client";

import { abortableDelay } from "@/lib/chat-agent-stream";

export function useChatArtifactDocument({ requestedArtifactId, selectedActivityArtifactId, retainCurrentArtifact = false, retryFinalRead = false }: { requestedArtifactId: string | null; selectedActivityArtifactId: string | null; retainCurrentArtifact?: boolean; retryFinalRead?: boolean }) {
  const [generatedArtifact, setGeneratedArtifact] = useState<Artifact | null>(null);
  const [artifactError, setArtifactError] = useState("");
  const [artifactLoading, setArtifactLoading] = useState(false);
  const [saveState, setSaveState] = useState<"saved" | "saving" | "dirty" | "error">("saved");
  const [drafts, setDrafts] = useState<Record<string, Omit<SaveArtifactInput, "expectedRevisionNumber">>>({});
  const previousArtifactIdRef = useRef<string | null>(null);
  const documentKeyRef = useRef("");
  const requestedDocumentId = selectedActivityArtifactId ?? requestedArtifactId;
  const artifactId = retainCurrentArtifact && generatedArtifact ? generatedArtifact.id : requestedDocumentId;
  const retrySelectedArtifact = retryFinalRead && artifactId === requestedDocumentId;

  useEffect(() => {
    const controller = new AbortController();
    const workspaceId = getSelectedWorkspaceId();
    const isCurrent = () => !controller.signal.aborted && getSelectedWorkspaceId() === workspaceId;
    if (!artifactId) {
      queueMicrotask(() => {
        if (!isCurrent()) return;
        setGeneratedArtifact(null);
        setArtifactLoading(false);
        setArtifactError("");
      });
      return () => controller.abort();
    }
    const restoredArtifactId = artifactId;

    queueMicrotask(() => {
      if (!isCurrent()) return;
      setArtifactLoading(true);
      setArtifactError("");
      setGeneratedArtifact(null);
      setSaveState("saved");
    });

    async function restoreArtifact() {
      let delay = 500;
      try {
        while (isCurrent()) {
          try {
            const artifact = await plotApiClient.getArtifact(restoredArtifactId, { signal: controller.signal });
            if (!isCurrent()) return;
            setGeneratedArtifact(artifact);
            setArtifactError("");
            return;
          } catch (error) {
            if (!isCurrent() || (error instanceof DOMException && error.name === "AbortError")) return;
            setArtifactError(error instanceof Error && error.message ? error.message : "The saved artifact could not be restored.");
            if (!retrySelectedArtifact || (error instanceof PlotApiError && [401, 403, 404].includes(error.status))) return;
            await abortableDelay(delay, controller.signal);
            delay = Math.min(delay * 2, 4_000);
          }
        }
      } finally {
        if (isCurrent()) setArtifactLoading(false);
      }
    }
    void restoreArtifact().catch(() => undefined);
    return () => controller.abort();
  }, [artifactId, retrySelectedArtifact]);

  const currentArtifact = generatedArtifact;
  const currentArtifactId = currentArtifact?.id ?? null;
  const documentKey = `current:${currentArtifactId ?? "none"}`;

  useEffect(() => {
    documentKeyRef.current = documentKey;
  }, [documentKey]);

  useEffect(() => {
    if (previousArtifactIdRef.current === currentArtifactId) return;
    previousArtifactIdRef.current = currentArtifactId;
    setSaveState(currentArtifactId && drafts[currentArtifactId] ? "dirty" : "saved");
  }, [currentArtifactId, drafts]);

  const clearArtifactSelection = useCallback(() => {
    setGeneratedArtifact(null);
  }, []);

  const onSaveStateChange = useCallback((state: "saved" | "saving" | "dirty" | "error") => {
    if (documentKeyRef.current === documentKey) setSaveState(state);
  }, [documentKey]);

  const onDraftChange = useCallback((draft: Omit<SaveArtifactInput, "expectedRevisionNumber">) => {
    if (documentKeyRef.current !== documentKey || !currentArtifactId) return;
    setDrafts((current) => ({ ...current, [currentArtifactId]: draft }));
  }, [currentArtifactId, documentKey]);

  const onSaveArtifact = useCallback((input: SaveArtifactInput) => {
    if (!currentArtifact) return Promise.reject(new Error("No artifact is selected."));
    return plotApiClient.saveArtifactVariant(currentArtifact.variant.id, input);
  }, [currentArtifact]);

  const onPackChange = useCallback((next: Artifact) => {
    setDrafts((current) => {
      const nextDrafts = { ...current };
      delete nextDrafts[next.id];
      return nextDrafts;
    });
    if (documentKeyRef.current !== documentKey) return;
    setGeneratedArtifact(next);
  }, [documentKey]);

  return {
    artifactError,
    artifactLoading,
    clearArtifactSelection,
    currentArtifact,
    currentArtifactId,
    documentKey,
    documentKeyRef,
    drafts,
    onDraftChange,
    onPackChange,
    onSaveArtifact,
    onSaveStateChange,
    saveState,
  };
}
