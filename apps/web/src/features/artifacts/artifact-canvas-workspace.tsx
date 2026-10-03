"use client";

import Link from "next/link";
import { ArrowUpRight, Check, Copy, Ellipsis, Library, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type RefObject } from "react";

import type { Artifact, PlotApiClient } from "@plot/api-client";
import { GitHubMark } from "@/components/auth/github-mark";
import { ArtifactDocumentSurface } from "@/features/artifacts/artifact-document-surface";
import { ArtifactEditorStatus, ArtifactSaveDraftButton, artifactSaveStateLabel } from "@/features/artifacts/artifact-editor-chrome";
import { ExportDialog } from "@/features/citations/export-dialog";
import { PublishDialog } from "@/features/citations/publish-dialog";
import type { SaveArtifactInput } from "@/features/citations/tiptap-draft-editor";
import { isSafeHttpUrl } from "@/lib/safe-url";
import { useWorkspaceEntitlement } from "@/lib/use-workspace-entitlement";

type ArtifactCanvasWorkspaceProps = {
  artifact: Artifact;
  client: PlotApiClient;
  onSaveArtifact: (input: SaveArtifactInput) => Promise<Artifact>;
};

export function ArtifactCanvasWorkspace({ artifact, client, onSaveArtifact }: ArtifactCanvasWorkspaceProps) {
  const [currentArtifact, setCurrentArtifact] = useState(artifact);
  const [draftTitle, setDraftTitle] = useState(artifact.title ?? "");
  const [saveState, setSaveState] = useState<"saved" | "saving" | "dirty" | "error">("saved");
  const [drafts, setDrafts] = useState<Record<string, Omit<SaveArtifactInput, "expectedRevisionNumber">>>({});
  const [saveRequestToken, setSaveRequestToken] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);
  const [sourcesOpen, setSourcesOpen] = useState(false);
  const actionsRef = useRef<HTMLDivElement>(null);
  const overflowTriggerRef = useRef<HTMLButtonElement>(null);
  const entitlement = useWorkspaceEntitlement();
  const artifactTitle = currentArtifact.title ?? "Untitled artifact";
  const canEdit = entitlement?.capabilities.edit ?? true;
  const canPublish = entitlement?.capabilities.publish ?? true;
  const canUnpublish = entitlement?.capabilities.unpublish ?? true;
  const readOnly = !canEdit;
  const titleDirty = draftTitle.trim() !== (currentArtifact.title?.trim() ?? "");
  const visibleSaveState = titleDirty && saveState === "saved" ? "dirty" : saveState;
  const closeSources = useCallback(() => setSourcesOpen(false), []);

  useEffect(() => {
    if (!menuOpen) return;

    function dismiss(event: Event) {
      if (event.target instanceof Node && !actionsRef.current?.contains(event.target)) {
        setMenuOpen(false);
      }
    }

    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        setMenuOpen(false);
        overflowTriggerRef.current?.focus();
      }
    }

    document.addEventListener("pointerdown", dismiss, true);
    document.addEventListener("click", dismiss, true);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", dismiss, true);
      document.removeEventListener("click", dismiss, true);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [menuOpen]);

  function openSources() {
    setMenuOpen(false);
    setSourcesOpen(true);
  }

  return (
    <div className="relative flex h-full min-h-[calc(100dvh-49px)] min-w-0 flex-col overflow-hidden bg-[#eef0f3] dark:bg-[#18181b] lg:min-h-0">
      <header className="flex h-14 shrink-0 items-center gap-4 border-b border-black/[0.08] bg-[#f8fafc] px-5 dark:border-white/10 dark:bg-[#111113]">
        <nav aria-label="Breadcrumb" className="flex min-w-0 flex-1 items-center gap-1.5 font-sans text-[13px] leading-none">
          <Link
            href="/contents"
            className="glass-button shrink-0"
          >
            Contents
          </Link>
          <span aria-hidden="true" className="shrink-0 text-black/20 dark:text-white/22">/</span>
          <span aria-current="page" title={artifactTitle} className="min-w-0 truncate font-medium text-black/72 dark:text-white/76">
            {artifactTitle}
          </span>
        </nav>
        <div ref={actionsRef} className="relative flex shrink-0 items-center gap-2">
          <button
            ref={overflowTriggerRef}
            type="button"
            aria-label="Artifact actions"
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((open) => !open)}
            className="glass-button glass-icon inline-flex size-9 items-center justify-center"
          >
            <Ellipsis aria-hidden="true" className="size-4" />
          </button>
          {menuOpen ? (
            <div role="menu" aria-label="Artifact actions" className="glass-layer absolute right-0 top-full z-40 mt-2 w-[204px] rounded-[8px] border border-black/10 p-2 text-[13px] text-[#18181b] dark:border-white/10 dark:text-white">
              <MenuButton icon={Library} onClick={openSources}>Sources</MenuButton>
              <ExportDialog pack={currentArtifact} client={client} presentation="menu" />
            </div>
          ) : null}
        </div>
      </header>

      <main className="flex min-h-0 flex-1 items-stretch justify-center overflow-y-auto bg-[#eef0f3] px-4 dark:bg-[#18181b] sm:px-8">
        <ArtifactDocumentSurface
          pack={currentArtifact}
          client={client}
          presentation="canvas"
          editorLocked={!canEdit}
          draftTitle={draftTitle}
          onDraftTitleChange={setDraftTitle}
          topActions={<div className="flex flex-wrap items-center justify-end gap-2">
            <span className="mr-1 hidden sm:inline">
              <ArtifactEditorStatus>{saveStateLabel(visibleSaveState, readOnly)}</ArtifactEditorStatus>
            </span>
            {canEdit ? (
              <ArtifactSaveDraftButton
                saving={saveState === "saving"}
                disabled={!draftTitle.trim()}
                onClick={() => setSaveRequestToken((value) => value + 1)}
              />
            ) : null}
            {canPublish || (currentArtifact.publication && canUnpublish) ? (
              <PublishDialog pack={currentArtifact} client={client} onPackChange={setCurrentArtifact} />
            ) : null}
          </div>}
          saveRequestToken={saveRequestToken}
          saveState={visibleSaveState}
          initialDraft={drafts[currentArtifact.id]}
          onSaveStateChange={setSaveState}
          onDraftChange={(draft) => setDrafts((current) => ({ ...current, [currentArtifact.id]: draft }))}
          onSaveArtifact={(input) => {
            if (!draftTitle.trim()) throw new Error("A document title is required.");
            return onSaveArtifact({ ...input, title: draftTitle.trim() });
          }}
          onPackChange={(next) => {
            setDrafts((current) => {
              const nextDrafts = { ...current };
              delete nextDrafts[next.id];
              return nextDrafts;
            });
            setCurrentArtifact(next);
            setDraftTitle(next.title ?? "");
          }}
        />
      </main>

      <ArtifactDrawer
        open={sourcesOpen}
        title="Sources"
        subtitle="Evidence linked to this draft"
        triggerRef={overflowTriggerRef}
        onClose={closeSources}
      >
        <ArtifactSources
          sources={currentArtifact.variant.sources}
          relatedArtifacts={currentArtifact.relatedArtifacts}
        />
      </ArtifactDrawer>

    </div>
  );
}

function MenuButton({ icon: Icon, children, onClick }: { icon: typeof Copy; children: string; onClick: () => void }) {
  return (
    <button type="button" role="menuitem" onClick={onClick} className="glass-control flex h-8 w-full items-center gap-2 rounded-[4px] px-2.5 text-left">
      <Icon aria-hidden="true" className="size-4" />
      {children}
    </button>
  );
}

function ArtifactDrawer({ open, title, subtitle, triggerRef, onClose, children }: { open: boolean; title: string; subtitle: string; triggerRef: RefObject<HTMLButtonElement | null>; onClose: () => void; children: React.ReactNode }) {
  const dialogRef = useRef<HTMLElement>(null);

  useEffect(() => {
    if (!open) return;
    const dialog = dialogRef.current;
    const trigger = triggerRef.current;
    dialog?.focus();

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== "Tab" || !dialog) return;
      const elements = focusableElements(dialog);
      if (!elements.length) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const current = elements.indexOf(document.activeElement as HTMLElement);
      if (event.shiftKey && current <= 0) {
        event.preventDefault();
        elements[elements.length - 1]?.focus();
      } else if (!event.shiftKey && (current === -1 || current === elements.length - 1)) {
        event.preventDefault();
        elements[0]?.focus();
      }
    }

    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      trigger?.focus();
    };
  }, [onClose, open, triggerRef]);

  if (!open) return null;

  return (
    <div className="fixed inset-y-0 left-0 right-0 z-50 lg:left-[252px]">
      <button type="button" aria-label={`Close ${title.toLowerCase()}`} onClick={onClose} className="absolute inset-0 bg-black/[0.06]" />
      <aside ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby={`artifact-${title.toLowerCase()}-title`} tabIndex={-1} className="glass-layer absolute inset-y-0 right-0 flex w-full max-w-[420px] flex-col overflow-y-auto rounded-l-[12px] border border-black/10 px-6 py-6 outline-none dark:border-white/10">
        <header className="flex h-10 shrink-0 items-center justify-between">
          <h2 id={`artifact-${title.toLowerCase()}-title`} className="font-display text-[24px] leading-8 text-black/88 dark:text-white/90">{title}</h2>
          <button type="button" aria-label="Close" onClick={onClose} className="glass-button glass-icon inline-flex size-8 items-center justify-center">
            <X aria-hidden="true" className="size-4" />
          </button>
        </header>
        <p className="mt-2 text-[13px] leading-5 text-black/50 dark:text-white/52">{subtitle}</p>
        <div className="mt-6 min-h-0">{children}</div>
      </aside>
    </div>
  );
}

function ArtifactSources({
  sources,
  relatedArtifacts,
}: {
  sources: Artifact["variant"]["sources"];
  relatedArtifacts?: Artifact["relatedArtifacts"];
}) {
  const uniqueSources = sources.filter((source, index, all) => all.findIndex((candidate) => candidate.evidenceId === source.evidenceId) === index);

  return (
    <div className="space-y-8">
      <div>
        <h3 className="mb-3 text-xs font-semibold text-black/55 dark:text-white/55">
          Attached sources <span className="ml-1 rounded-full bg-black/[0.05] px-1.5 py-0.5 text-[11px] tabular-nums dark:bg-white/10">{uniqueSources.length}</span>
        </h3>
        {!uniqueSources.length ? (
          <p className="rounded-[8px] border border-dashed border-black/10 px-3.5 py-4 text-sm leading-6 text-black/52 dark:border-white/12 dark:text-white/55">
            No current sources are available for this artifact.
          </p>
        ) : (
          <ol className="divide-y divide-black/[0.07] border-y border-black/[0.07] dark:divide-white/10 dark:border-white/10" aria-label="Current sources">
            {uniqueSources.map((source) => (
              <li key={source.evidenceId}>
                <SourceRow source={source} />
              </li>
            ))}
          </ol>
        )}
      </div>

      {relatedArtifacts && relatedArtifacts.length > 0 ? (
        <div>
          <h3 className="mb-3 text-xs font-semibold text-black/55 dark:text-white/55">
            Related content <span className="ml-1 rounded-full bg-black/[0.05] px-1.5 py-0.5 text-[11px] tabular-nums dark:bg-white/10">{relatedArtifacts.length}</span>
          </h3>
          <ul className="divide-y divide-black/[0.07] border-y border-black/[0.07] dark:divide-white/10 dark:border-white/10" aria-label="Related artifacts">
            {relatedArtifacts.map((related) => (
              <li key={related.id}>
                <Link
                  href={`/contents?artifact=${encodeURIComponent(related.id)}`}
                  className="flex items-center justify-between gap-3 px-1 py-3 transition hover:bg-black/[0.025] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/15 dark:hover:bg-white/[0.04]"
                >
                  <div className="min-w-0 flex-1">
                    <p className="line-clamp-2 text-[13px] font-medium leading-5 text-black/85 dark:text-white/85">
                      {related.title || "Untitled artifact"}
                    </p>
                    <span className="mt-0.5 inline-block text-[11px] text-black/50 dark:text-white/50">
                      {related.contentType === "LAUNCH_ANNOUNCEMENT"
                        ? "Launch announcement"
                        : related.contentType === "CHANGELOG"
                          ? "Changelog"
                          : "Artifact"}
                    </span>
                  </div>
                  <ArrowUpRight aria-hidden="true" className="size-4 shrink-0 text-black/35 dark:text-white/40" />
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

function SourceRow({ source }: { source: Artifact["variant"]["sources"][number] }) {
  const url = typeof source.originalUrl === "string" && isSafeHttpUrl(source.originalUrl)
    ? source.originalUrl
    : null;
  const detail = source.provider === "USER_CONFIRMED"
    ? "Confirmed in Plot"
    : url ? sourceDisplayUrl(url) : "Source link unavailable";
  const content = (
    <>
      <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center text-black/65 dark:text-white/75">
        {source.provider === "GITHUB" ? <GitHubMark className="size-[18px]" /> : <Check aria-hidden="true" className="size-4" />}
      </span>
      <span className="min-w-0 flex-1">
        <span className="line-clamp-2 text-[13px] font-medium leading-5 text-black/82 dark:text-white/85">
          {source.sourceLabel || "Untitled source"}
        </span>
        <span className="mt-1 block truncate text-[11px] text-black/45 dark:text-white/50">{detail}</span>
      </span>
      {url ? <ArrowUpRight aria-hidden="true" className="mt-1 size-4 shrink-0 text-black/35 dark:text-white/40" /> : null}
    </>
  );
  const rowClassName = "flex items-start gap-3 px-1 py-3 text-left";

  return url ? (
    <a href={url} target="_blank" rel="noopener noreferrer" className={`${rowClassName} transition hover:bg-black/[0.025] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/15 dark:hover:bg-white/[0.04]`}>
      {content}
    </a>
  ) : (
    <div className={rowClassName}>{content}</div>
  );
}

function sourceDisplayUrl(value: string) {
  try {
    const url = new URL(value);
    return `${url.hostname}${url.pathname === "/" ? "" : url.pathname}`;
  } catch {
    return value;
  }
}

function saveStateLabel(state: "saved" | "saving" | "dirty" | "error", readOnly: boolean) {
  return artifactSaveStateLabel(state, readOnly);
}

function focusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex="-1"])')).filter((element) => element.getAttribute("aria-hidden") !== "true");
}
