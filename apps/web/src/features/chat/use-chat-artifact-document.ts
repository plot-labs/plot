"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import type { Artifact, ArtifactHistoryDetail } from "@plot/api-client";
import type { SaveArtifactInput } from "@/features/citations/cited-draft-editor";
import { plotApiClient } from "@/lib/api-client";

export function useChatArtifactDocument({ requestedArtifactId, selectedActivityArtifactId }: { requestedArtifactId: string | null; selectedActivityArtifactId: string | null }) {
  const [generatedArtifact, setGeneratedArtifact] = useState<Artifact | null>(null);
  const [historicalArtifact, setHistoricalArtifact] = useState<ArtifactHistoryDetail | null>(null);
  const [historicalPosition, setHistoricalPosition] = useState<number | null>(null);
  const [artifactError, setArtifactError] = useState("");
  const [artifactLoading, setArtifactLoading] = useState(false);
  const [saveState, setSaveState] = useState<"saved" | "saving" | "dirty" | "error">("saved");
  const [drafts, setDrafts] = useState<Record<string, Omit<SaveArtifactInput, "expectedRevisionNumber">>>({});
  const previousArtifactIdRef = useRef<string | null>(null);
  const documentKeyRef = useRef("");
  const artifactId = selectedActivityArtifactId ?? requestedArtifactId;

  useEffect(() => {
    if (!artifactId) {
      queueMicrotask(() => {
        setGeneratedArtifact(null);
        setArtifactLoading(false);
        setArtifactError("");
      });
      return;
    }
    const restoredArtifactId = artifactId;
    const controller = new AbortController();

    queueMicrotask(() => {
      if (controller.signal.aborted) return;
      setArtifactLoading(true);
      setArtifactError("");
      setGeneratedArtifact(null);
      setHistoricalArtifact(null);
      setHistoricalPosition(null);
      setSaveState("saved");
    });

    async function restoreArtifact() {
      try {
        const artifact = await plotApiClient.getArtifact(restoredArtifactId, { signal: controller.signal });
        if (controller.signal.aborted) return;
        setGeneratedArtifact(artifact);
      } catch (error) {
        if (!controller.signal.aborted && !(error instanceof DOMException && error.name === "AbortError")) {
          setArtifactError(error instanceof Error && error.message ? error.message : "The saved artifact could not be restored.");
        }
      } finally {
        if (!controller.signal.aborted) setArtifactLoading(false);
      }
    }
    void restoreArtifact();
    return () => controller.abort();
  }, [artifactId]);

  const currentArtifact = historicalArtifact?.artifact ?? generatedArtifact;
  const currentArtifactId = currentArtifact?.id ?? null;
  const documentKey = `${historicalArtifact ? "history" : "current"}:${currentArtifactId ?? "none"}`;

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
    setHistoricalArtifact(null);
    setHistoricalPosition(null);
  }, []);

  const resetHistory = useCallback(() => {
    setHistoricalArtifact(null);
    setHistoricalPosition(null);
  }, []);

  const selectHistoricalArtifact = useCallback((detail: ArtifactHistoryDetail, position: number) => {
    if (documentKeyRef.current !== documentKey) return;
    setHistoricalArtifact(detail);
    setHistoricalPosition(position);
  }, [documentKey]);

  const onSaveStateChange = useCallback((state: "saved" | "saving" | "dirty" | "error") => {
    if (documentKeyRef.current === documentKey) setSaveState(state);
  }, [documentKey]);

  const onDraftChange = useCallback((draft: Omit<SaveArtifactInput, "expectedRevisionNumber">) => {
    if (historicalArtifact || documentKeyRef.current !== documentKey || !currentArtifactId) return;
    setDrafts((current) => ({ ...current, [currentArtifactId]: draft }));
  }, [currentArtifactId, documentKey, historicalArtifact]);

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
    setHistoricalArtifact(null);
    setHistoricalPosition(null);
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
    historicalArtifact,
    historicalPosition,
    onDraftChange,
    onPackChange,
    onSaveArtifact,
    onSaveStateChange,
    resetHistory,
    saveState,
    selectHistoricalArtifact,
  };
}
