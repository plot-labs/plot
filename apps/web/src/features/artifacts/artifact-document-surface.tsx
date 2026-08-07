"use client";

import type { Artifact, ArtifactHistoryDetail, PlotApiClient } from "@plot/api-client";

import { CitedDraftEditor, type SaveArtifactInput } from "@/features/citations/cited-draft-editor";
import { ExportDialog } from "@/features/citations/export-dialog";

type ArtifactDocumentSurfaceProps = {
  pack: Artifact;
  historical: ArtifactHistoryDetail | null;
  client: PlotApiClient;
  initialDraft?: Omit<SaveArtifactInput, "expectedRevisionNumber">;
  saveState?: "saved" | "saving" | "dirty" | "error";
  onSaveStateChange?: (state: "saved" | "saving" | "dirty" | "error") => void;
  onDraftChange?: (draft: Omit<SaveArtifactInput, "expectedRevisionNumber">) => void;
  onSaveArtifact: (input: SaveArtifactInput) => Promise<Artifact>;
  onPackChange: (pack: Artifact) => void;
  presentation?: "panel" | "canvas";
  saveRequestToken?: number;
};

export function ArtifactDocumentSurface({
  pack,
  historical,
  client,
  initialDraft,
  saveState,
  onSaveStateChange,
  onDraftChange,
  onSaveArtifact,
  onPackChange,
  presentation = "panel",
  saveRequestToken,
}: ArtifactDocumentSurfaceProps) {
  const readOnly = Boolean(historical);
  const shownPack = historical?.artifact ?? pack;

  if (presentation === "canvas") {
    return (
      <article aria-label="Artifact document surface" className="min-h-[min(956px,calc(100dvh-128px))] w-full max-w-[980px] overflow-hidden rounded-[8px] border border-black/10 bg-white px-[clamp(28px,7.35vw,72px)] pb-[52px] pt-16 shadow-[0_8px_20px_rgba(0,0,0,0.08)] dark:border-white/10 dark:bg-[#202024]">
        <h1 className="font-display text-[30px] leading-[38px] text-black/88 dark:text-white/90">{shownPack.title || "Generated artifact"}</h1>
        <CitedDraftEditor
          pack={shownPack}
          embedded
          presentation="document"
          saveRequestToken={saveRequestToken}
          readOnly={readOnly}
          initialDraft={readOnly ? undefined : initialDraft}
          onSaveStateChange={onSaveStateChange}
          onDraftChange={onDraftChange}
          onSaveArtifact={onSaveArtifact}
          onPackChange={onPackChange}
        />
      </article>
    );
  }

  return (
    <article aria-label="Artifact document surface" className="overflow-hidden rounded-xl border border-black/10 bg-white dark:border-white/10 dark:bg-white/[0.04]">
      <header className="flex flex-wrap items-start justify-between gap-4 border-b border-black/[0.07] px-4 py-4 dark:border-white/10 sm:px-6">
        <div className="min-w-0">
          <div className="text-xs font-semibold uppercase tracking-[0.08em] text-black/42 dark:text-white/45">Artifact</div>
          <h2 className="mt-1 truncate text-xl font-semibold text-black/88 dark:text-white/90">{shownPack.title || "Generated artifact"}</h2>
          <p className="mt-1 text-sm text-black/52 dark:text-white/55">{historical ? `${historical.cause} · historical preview` : shownPack.status}</p>
        </div>
        <div className="flex min-w-0 flex-col items-end gap-2">
          {saveState ? <span role="status" aria-live="polite" className="text-xs text-black/45 dark:text-white/48">{saveStateLabel(saveState, readOnly)}</span> : null}
          {!readOnly ? <ExportDialog pack={shownPack} client={client} /> : <p className="text-right text-xs text-black/48 dark:text-white/52">Editing and delivery are disabled for this snapshot.</p>}
        </div>
      </header>
      <CitedDraftEditor
        pack={shownPack}
        embedded
        readOnly={readOnly}
        initialDraft={readOnly ? undefined : initialDraft}
        onSaveStateChange={onSaveStateChange}
        onDraftChange={onDraftChange}
        onSaveArtifact={onSaveArtifact}
        onPackChange={onPackChange}
      />
    </article>
  );
}

function saveStateLabel(state: "saved" | "saving" | "dirty" | "error", readOnly: boolean) {
  if (readOnly) return "Saved snapshot";
  if (state === "saving") return "Saving…";
  if (state === "dirty") return "Unsaved changes";
  if (state === "error") return "Save needs attention";
  return "Saved";
}
