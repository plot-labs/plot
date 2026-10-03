"use client";

import { LoaderCircle, Save } from "lucide-react";

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
    <button aria-busy={Boolean(saving)}
      type="button"
      disabled={disabled || saving}
      onClick={onClick}
      className="glass-button glass-primary min-w-[116px]"
    >
      {saving ? <LoaderCircle aria-hidden="true" className="size-3.5 animate-spin" /> : <Save aria-hidden="true" className="size-3.5" />}
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
