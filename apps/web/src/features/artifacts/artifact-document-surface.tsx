"use client";

import type { ReactNode } from "react";
import type { Artifact, PlotApiClient } from "@plot/api-client";

import { ArtifactEditorStatus, artifactSaveStateLabel } from "@/features/artifacts/artifact-editor-chrome";
import { ExportDialog } from "@/features/citations/export-dialog";
import { PublishDialog } from "@/features/citations/publish-dialog";
import { TiptapDraftEditor, type SaveArtifactInput } from "@/features/citations/tiptap-draft-editor";

type ArtifactDocumentSurfaceProps = {
  pack: Artifact;
  client: PlotApiClient;
  initialDraft?: Omit<SaveArtifactInput, "expectedRevisionNumber">;
  saveState?: "saved" | "saving" | "dirty" | "error";
  onSaveStateChange?: (state: "saved" | "saving" | "dirty" | "error") => void;
  onDraftChange?: (draft: Omit<SaveArtifactInput, "expectedRevisionNumber">) => void;
  onSaveArtifact: (input: SaveArtifactInput) => Promise<Artifact>;
  onPackChange: (pack: Artifact) => void;
  presentation?: "panel" | "canvas" | "workspace";
  saveRequestToken?: number;
  editorLocked?: boolean;
  draftTitle?: string;
  onDraftTitleChange?: (title: string) => void;
  topActions?: ReactNode;
};

export function ArtifactDocumentSurface({
  pack,
  client,
  initialDraft,
  saveState,
  onSaveStateChange,
  onDraftChange,
  onSaveArtifact,
  onPackChange,
  presentation = "panel",
  saveRequestToken,
  editorLocked = false,
  draftTitle,
  onDraftTitleChange,
  topActions,
}: ArtifactDocumentSurfaceProps) {
  const readOnly = editorLocked;
  const shownPack = pack;

  if (presentation === "canvas" || presentation === "workspace") {
    const workspacePresentation = presentation === "workspace";
    return (
      <article
        aria-label="Artifact document surface"
        className={workspacePresentation
          ? "min-h-full w-full max-w-[980px] px-[clamp(24px,5vw,48px)] pb-16 text-black/88 dark:text-white/90"
          : "min-h-full w-full max-w-[1080px] self-start border-x border-black/10 bg-white px-[clamp(24px,4vw,52px)] pb-24 pt-12 dark:border-white/10 dark:bg-[#202024]"}
      >
        {!workspacePresentation ? (
          <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
            <span className="text-xs font-medium text-black/45 dark:text-white/45">
              {contentTypeLabel(shownPack.contentType)} · {shownPack.publication ? "Published" : "Draft"}
            </span>
            {topActions}
          </div>
        ) : null}
        {!workspacePresentation ? (
          readOnly ? (
            <h1 className="font-display text-[30px] leading-[38px] text-black/88 dark:text-white/90">{shownPack.title || "Generated artifact"}</h1>
          ) : (
            <>
              <label className="sr-only" htmlFor={`artifact-title-${shownPack.id}`}>Document title</label>
              <textarea
                id={`artifact-title-${shownPack.id}`}
                aria-invalid={!draftTitle?.trim()}
                value={draftTitle ?? shownPack.title ?? ""}
                onChange={(event) => onDraftTitleChange?.(event.target.value.replace(/[\r\n]+/g, " "))}
                onKeyDown={(event) => {
                  if (event.key === "Enter") event.preventDefault();
                }}
                disabled={saveState === "saving"}
                maxLength={200}
                rows={1}
                placeholder="Untitled artifact"
                className="block w-full resize-none overflow-hidden bg-transparent p-0 font-display text-[30px] leading-[38px] text-black/88 outline-none placeholder:text-black/25 [field-sizing:content] focus-visible:underline focus-visible:decoration-black/20 focus-visible:underline-offset-4 dark:text-white/90 dark:placeholder:text-white/30 dark:focus-visible:decoration-white/25"
              />
              {!draftTitle?.trim() ? <p className="mt-1 text-xs text-rose-600">Enter a title before saving.</p> : null}
            </>
          )
        ) : null}
        <TiptapDraftEditor
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
    <article aria-label="Artifact document surface" className="glass-card overflow-hidden rounded-xl border border-black/10 dark:border-white/10">
      <header className="flex flex-wrap items-start justify-between gap-4 border-b border-black/[0.07] px-4 py-4 dark:border-white/10 sm:px-6">
        <div className="min-w-0">
          <div className="text-xs font-semibold uppercase tracking-[0.08em] text-black/42 dark:text-white/45">Artifact</div>
          <h2 className="mt-1 truncate text-xl font-semibold text-black/88 dark:text-white/90">{shownPack.title || "Generated artifact"}</h2>
          <p className="mt-1 text-sm text-black/52 dark:text-white/55">
            {shownPack.status}
            {" · "}
            {contentTypeLabel(shownPack.contentType)}
          </p>
        </div>
        <div className="flex min-w-0 flex-col items-end gap-2">
          {saveState ? <ArtifactEditorStatus>{saveStateLabel(saveState, readOnly)}</ArtifactEditorStatus> : null}
          <div className="flex flex-col items-end gap-2">
            <div className="flex flex-wrap items-center justify-end gap-2">
              <ExportDialog pack={shownPack} client={client} />
              <PublishDialog pack={shownPack} client={client} presentation="inline" onPackChange={onPackChange} />
            </div>
          </div>
        </div>
      </header>
      <TiptapDraftEditor
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
  return artifactSaveStateLabel(state, readOnly);
}

function contentTypeLabel(contentType: Artifact["contentType"]) {
  if (contentType === "LAUNCH_ANNOUNCEMENT") return "Launch announcement";
  if (contentType === "CHANGELOG") return "Changelog";
  return "Artifact";
}
