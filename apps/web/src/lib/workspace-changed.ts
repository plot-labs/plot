import { useEffect, useEffectEvent } from "react";

/**
 * Fired on `window` when the selected workspace changes without a page reload.
 * Workspace-scoped views listen for it to drop stale state and refetch.
 */
export const WORKSPACE_CHANGED_EVENT = "plot:workspace-changed";

export function dispatchWorkspaceChanged(workspaceId: string | null): void {
  window.dispatchEvent(new CustomEvent(WORKSPACE_CHANGED_EVENT, { detail: { id: workspaceId } }));
}

/** Runs `onChange` whenever the selected workspace changes, always with the latest closure. */
export function useWorkspaceChanged(onChange: () => void): void {
  const handleChange = useEffectEvent(onChange);

  useEffect(() => {
    const listener = () => handleChange();
    window.addEventListener(WORKSPACE_CHANGED_EVENT, listener);
    return () => window.removeEventListener(WORKSPACE_CHANGED_EVENT, listener);
  }, []);
}
