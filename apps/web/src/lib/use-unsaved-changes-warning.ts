"use client";

import { useEffect } from "react";

/** Asks the browser to confirm before closing or reloading the tab while edits are unsaved. */
export function useUnsavedChangesWarning(active: boolean) {
  useEffect(() => {
    if (!active) return;

    function onBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
      // Older browsers only show the prompt when returnValue is set.
      event.returnValue = "";
    }

    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [active]);
}
