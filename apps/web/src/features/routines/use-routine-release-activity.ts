"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { PlotApiError, plotApiClient, type GitHubReleaseActivity, type GitHubReleaseRangeInput } from "@/lib/api-client";

import { isReleaseActivityInFlight, normalizeCommitSha } from "./release-activity-utils";

const POLL_INTERVAL_MS = 8_000;

export function useRoutineReleaseActivity(sourceScopeId: string | null, releaseRequestId: string | null = null) {
  const [activity, setActivity] = useState<GitHubReleaseActivity | null>(null);
  const [isLoading, setIsLoading] = useState(Boolean(sourceScopeId));
  const [error, setError] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const [savingRange, setSavingRange] = useState(false);
  const fetchAbortRef = useRef<AbortController | null>(null);
  const retryAbortRef = useRef<AbortController | null>(null);
  const rangeAbortRef = useRef<AbortController | null>(null);
  const workspaceRevisionRef = useRef(0);

  const fetchActivity = useCallback(async (signal: AbortSignal, requestId?: string | null) => {
    if (!sourceScopeId) return null;
    const id = requestId ?? releaseRequestId;
    if (id) return plotApiClient.getGitHubReleaseActivityById(sourceScopeId, id, { signal });
    return plotApiClient.getGitHubReleaseActivity(sourceScopeId, { signal });
  }, [sourceScopeId, releaseRequestId]);

  useEffect(() => {
    function handleWorkspaceChanged() {
      workspaceRevisionRef.current += 1;
      fetchAbortRef.current?.abort();
      retryAbortRef.current?.abort();
      rangeAbortRef.current?.abort();
      fetchAbortRef.current = null;
      retryAbortRef.current = null;
      rangeAbortRef.current = null;
      setActivity(null);
      setIsLoading(Boolean(sourceScopeId));
      setError(null);
      setRetrying(false);
      setSavingRange(false);
    }

    window.addEventListener("plot:workspace-changed", handleWorkspaceChanged);
    return () => {
      window.removeEventListener("plot:workspace-changed", handleWorkspaceChanged);
      fetchAbortRef.current?.abort();
      retryAbortRef.current?.abort();
      rangeAbortRef.current?.abort();
    };
  }, [sourceScopeId]);

  useEffect(() => {
    if (!sourceScopeId) return;

    const controller = new AbortController();
    const workspaceRevision = workspaceRevisionRef.current;
    fetchAbortRef.current = controller;
    queueMicrotask(() => {
      if (fetchAbortRef.current !== controller) return;
      setIsLoading(true);
      setError(null);
    });

    void fetchActivity(controller.signal)
      .then((value) => {
        if (controller.signal.aborted || workspaceRevisionRef.current !== workspaceRevision) return;
        setActivity(value);
        setError(null);
      })
      .catch((err) => {
        if (controller.signal.aborted || workspaceRevisionRef.current !== workspaceRevision) return;
        if (err instanceof DOMException && err.name === "AbortError") return;
        setError("Latest release activity could not be loaded.");
      })
      .finally(() => {
        if (fetchAbortRef.current === controller && workspaceRevisionRef.current === workspaceRevision) {
          fetchAbortRef.current = null;
          setIsLoading(false);
        }
      });

    return () => controller.abort();
  }, [sourceScopeId, releaseRequestId, fetchActivity]);

  useEffect(() => {
    if (!sourceScopeId || !activity || !isReleaseActivityInFlight(activity.status)) return;

    const controller = new AbortController();
    const workspaceRevision = workspaceRevisionRef.current;
    const requestId = activity.id;

    const intervalId = window.setInterval(() => {
      void fetchActivity(controller.signal, requestId)
        .then((value) => {
          if (controller.signal.aborted || workspaceRevisionRef.current !== workspaceRevision) return;
          setActivity(value);
        })
        .catch(() => undefined);
    }, POLL_INTERVAL_MS);

    return () => {
      controller.abort();
      window.clearInterval(intervalId);
    };
  }, [sourceScopeId, activity, fetchActivity]);

  const retry = useCallback(async () => {
    if (!sourceScopeId || !activity || retrying || savingRange) return;

    const controller = new AbortController();
    const workspaceRevision = workspaceRevisionRef.current;
    retryAbortRef.current = controller;
    setRetrying(true);
    setError(null);

    try {
      const updated = await plotApiClient.retryGitHubReleaseDraft(sourceScopeId, activity.id, { signal: controller.signal });
      if (controller.signal.aborted || workspaceRevisionRef.current !== workspaceRevision) return;
      setActivity(updated);
    } catch (err) {
      if (controller.signal.aborted || workspaceRevisionRef.current !== workspaceRevision) return;
      if (err instanceof PlotApiError) {
        if (err.code === "FORBIDDEN") {
          setError("Workspace owner access is required to retry.");
          return;
        }
        if (err.code === "RELEASE_NOT_RETRYABLE") {
          try {
            const refreshed = await fetchActivity(controller.signal, activity.id);
            if (!controller.signal.aborted && workspaceRevisionRef.current === workspaceRevision) {
              setActivity(refreshed);
            }
          } catch {
            // ignore refresh failure after non-retryable response
          }
          return;
        }
      }
      setError("Release draft could not be retried.");
    } finally {
      if (retryAbortRef.current === controller) {
        retryAbortRef.current = null;
        setRetrying(false);
      }
    }
  }, [sourceScopeId, activity, retrying, savingRange, fetchActivity]);

  const selectRange = useCallback(async (range: GitHubReleaseRangeInput) => {
    if (!sourceScopeId || !activity || retrying || savingRange) return;

    const controller = new AbortController();
    const workspaceRevision = workspaceRevisionRef.current;
    rangeAbortRef.current = controller;
    setSavingRange(true);
    setError(null);

    try {
      const updated = await plotApiClient.selectGitHubReleaseRange(
        sourceScopeId,
        activity.id,
        { baseSha: normalizeCommitSha(range.baseSha), headSha: normalizeCommitSha(range.headSha) },
        { signal: controller.signal },
      );
      if (controller.signal.aborted || workspaceRevisionRef.current !== workspaceRevision) return;
      setActivity(updated);
    } catch (err) {
      if (controller.signal.aborted || workspaceRevisionRef.current !== workspaceRevision) return;
      if (err instanceof PlotApiError) {
        if (err.code === "FORBIDDEN") {
          setError("Workspace owner access is required to choose a range.");
          return;
        }
        if (err.code === "RELEASE_RANGE_NOT_SELECTABLE") {
          try {
            const refreshed = await fetchActivity(controller.signal, activity.id);
            if (!controller.signal.aborted && workspaceRevisionRef.current === workspaceRevision) {
              setActivity(refreshed);
            }
          } catch {
            // ignore refresh failure after a stale range
          }
          setError("This release no longer accepts a new range. Its tag head cannot change.");
          return;
        }
      }
      setError("Release range could not be saved.");
    } finally {
      if (rangeAbortRef.current === controller) {
        rangeAbortRef.current = null;
        setSavingRange(false);
      }
    }
  }, [sourceScopeId, activity, retrying, savingRange, fetchActivity]);

  return {
    activity: sourceScopeId ? activity : null,
    isLoading: sourceScopeId ? isLoading : false,
    error: sourceScopeId ? error : null,
    retry,
    retrying: sourceScopeId ? retrying : false,
    selectRange,
    savingRange: sourceScopeId ? savingRange : false,
  };
}
