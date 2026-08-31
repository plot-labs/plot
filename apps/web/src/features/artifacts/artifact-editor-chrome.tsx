"use client";

import { Save } from "lucide-react";

export function artifactSaveStateLabel(
  state: "saved" | "saving" | "dirty" | "error",
  readOnly: boolean,
) {
  if (readOnly) return "Saved snapshot";
  if (state === "saving") return "Saving…";
  if (state === "dirty") return "Unsaved changes";
  if (state === "error") return "Save needs attention";
  return "Saved";
}

type ArtifactSaveDraftButtonProps = {
  disabled?: boolean;
  saving?: boolean;
  onClick: () => void;
};

export function ArtifactSaveDraftButton({ disabled = false, saving = false, onClick }: ArtifactSaveDraftButtonProps) {
  return (
    <button
      type="button"
      disabled={disabled || saving}
      onClick={onClick}
      className="inline-flex min-h-8 items-center gap-1.5 rounded-lg bg-black px-3 text-xs font-medium text-white transition hover:bg-black/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:pointer-events-none disabled:opacity-40 dark:bg-white dark:text-black dark:hover:bg-white/85 dark:focus-visible:ring-white/25"
    >
      <Save aria-hidden="true" className="size-3.5" />
      {saving ? "Saving…" : "Save draft"}
    </button>
  );
}

export function ArtifactEditorStatus({ children }: { children: string }) {
  return (
    <span role="status" aria-live="polite" className="text-xs text-black/50 dark:text-white/52">
      {children}
    </span>
  );
}
