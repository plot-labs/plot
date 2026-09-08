"use client";

import Link from "next/link";
import { Citation } from "@astryxdesign/core/Citation";
import { Copy, Ellipsis, History, Library, Sparkles } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type RefObject } from "react";

import type { Artifact, ArtifactHistoryDetail, PlotApiClient } from "@plot/api-client";
import { ArtifactDocumentSurface } from "@/features/artifacts/artifact-document-surface";
import { ArtifactEditorStatus, ArtifactSaveDraftButton, artifactSaveStateLabel } from "@/features/artifacts/artifact-editor-chrome";
import { CreateRelatedContentDialog } from "@/features/artifacts/create-related-content-dialog";
import { ArtifactHistoryPanel } from "@/features/citations/artifact-history-panel";
import { ExportDialog } from "@/features/citations/export-dialog";
import { PublishDialog } from "@/features/citations/publish-dialog";
import type { SaveArtifactInput } from "@/features/citations/cited-draft-editor";
import { useWorkspaceEntitlement } from "@/lib/use-workspace-entitlement";

type ArtifactCanvasWorkspaceProps = {
  artifact: Artifact;
  client: PlotApiClient;
  onSaveArtifact: (input: SaveArtifactInput) => Promise<Artifact>;
};

type Drawer = "history" | "sources" | null;

export function ArtifactCanvasWorkspace({ artifact, client, onSaveArtifact }: ArtifactCanvasWorkspaceProps) {
  const [currentArtifact, setCurrentArtifact] = useState(artifact);
  const [historical, setHistorical] = useState<ArtifactHistoryDetail | null>(null);
  const [historicalPosition, setHistoricalPosition] = useState<number | null>(null);
  const [saveState, setSaveState] = useState<"saved" | "saving" | "dirty" | "error">("saved");
  const [drafts, setDrafts] = useState<Record<string, Omit<SaveArtifactInput, "expectedRevisionNumber">>>({});
  const [saveRequestToken, setSaveRequestToken] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);
  const [drawer, setDrawer] = useState<Drawer>(null);
  const [replicateOpen, setReplicateOpen] = useState(false);
  const actionsRef = useRef<HTMLDivElement>(null);
  const overflowTriggerRef = useRef<HTMLButtonElement>(null);
  const entitlement = useWorkspaceEntitlement();
  const shownArtifact = historical?.artifact ?? currentArtifact;
  const artifactTitle = shownArtifact.title ?? "Untitled artifact";
  const canEdit = entitlement?.capabilities.edit ?? true;
  const canPublish = entitlement?.capabilities.publish ?? true;
  const canUnpublish = entitlement?.capabilities.unpublish ?? true;
  const readOnly = Boolean(historical) || !canEdit;
  const closeDrawer = useCallback(() => setDrawer(null), []);

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

  function openDrawer(next: Exclude<Drawer, null>) {
    setMenuOpen(false);
    setDrawer(next);
  }

  return (
    <div className="relative flex h-full min-h-[calc(100dvh-49px)] min-w-0 flex-col overflow-hidden bg-[#eef0f3] dark:bg-[#18181b] lg:min-h-0">
      <header className="flex h-14 shrink-0 items-center justify-between gap-4 border-b border-black/[0.08] bg-[#f8fafc] px-5 dark:border-white/10 dark:bg-[#111113]">
        <nav aria-label="Breadcrumb" className="flex min-w-0 flex-1 items-center gap-1.5 font-sans text-[13px] leading-none">
          <Link
            href="/artifacts"
            className="shrink-0 rounded-sm font-medium text-black/50 transition hover:text-black/72 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/15 focus-visible:ring-offset-2 dark:text-white/50 dark:hover:text-white/75"
          >
            Artifacts
          </Link>
          <span aria-hidden="true" className="shrink-0 text-black/20 dark:text-white/22">/</span>
          <span aria-current="page" title={artifactTitle} className="min-w-0 truncate font-medium text-black/72 dark:text-white/76">
            {artifactTitle}
          </span>
        </nav>
        <div ref={actionsRef} className="relative flex shrink-0 items-center gap-2">
          <span className="mr-1 hidden sm:inline">
            <ArtifactEditorStatus>{saveStateLabel(saveState, readOnly)}</ArtifactEditorStatus>
          </span>
          {!historical ? (
            <>
              {canEdit ? (
                <ArtifactSaveDraftButton
                  saving={saveState === "saving"}
                  onClick={() => setSaveRequestToken((value) => value + 1)}
                />
              ) : null}
              <ExportDialog pack={shownArtifact} client={client} presentation="copy" />
              {canPublish || (shownArtifact.publication && canUnpublish) ? (
                <PublishDialog pack={shownArtifact} client={client} onPackChange={setCurrentArtifact} />
              ) : null}
              <button
                type="button"
                onClick={() => setReplicateOpen(true)}
                aria-label="Create related content"
                title="Create related content from this evidence"
                className="inline-flex min-h-8 items-center gap-1.5 rounded-lg border border-black/15 bg-white px-3 text-xs font-medium text-black/70 transition hover:bg-black/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/15 dark:border-white/15 dark:bg-white/[0.04] dark:text-white/70 dark:hover:bg-white/[0.08] dark:focus-visible:ring-white/25"
              >
                <Sparkles aria-hidden="true" className="size-3.5 text-black/60 dark:text-white/60" />
                <span className="hidden sm:inline">Create related</span>
              </button>
            </>
          ) : null}
          <button
            ref={overflowTriggerRef}
            type="button"
            aria-label="Artifact actions"
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((open) => !open)}
            className="inline-flex size-9 items-center justify-center rounded-[8px] text-[#18181b] transition hover:bg-black/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/15 dark:text-white dark:hover:bg-white/10"
          >
            <Ellipsis aria-hidden="true" className="size-4" />
          </button>

          {menuOpen ? (
            <div role="menu" aria-label="Artifact actions" className="absolute right-0 top-[54px] z-40 w-[204px] rounded-[8px] border border-black/10 bg-white p-2 text-[13px] text-[#18181b] shadow-[0_8px_20px_rgba(0,0,0,0.1)] dark:border-white/10 dark:bg-[#242529] dark:text-white">
              <MenuButton icon={History} onClick={() => openDrawer("history")}>History</MenuButton>
              <MenuButton icon={Library} onClick={() => openDrawer("sources")}>Sources</MenuButton>
              <MenuButton icon={Sparkles} onClick={() => { setMenuOpen(false); setReplicateOpen(true); }}>
                Create related content
              </MenuButton>
            </div>
          ) : null}
        </div>
      </header>

      <main className="flex min-h-0 flex-1 items-start justify-center overflow-y-auto bg-[#eef0f3] px-4 pb-16 pt-9 dark:bg-[#18181b] sm:px-8">
        <ArtifactDocumentSurface
          pack={currentArtifact}
          historical={historical}
          client={client}
          presentation="canvas"
          editorLocked={!canEdit}
          saveRequestToken={saveRequestToken}
          saveState={saveState}
          initialDraft={historical ? undefined : drafts[currentArtifact.id]}
          onSaveStateChange={setSaveState}
          onDraftChange={(draft) => {
            if (historical) return;
            setDrafts((current) => ({ ...current, [currentArtifact.id]: draft }));
          }}
          onSaveArtifact={onSaveArtifact}
          onPackChange={(next) => {
            setDrafts((current) => {
              const nextDrafts = { ...current };
              delete nextDrafts[next.id];
              return nextDrafts;
            });
            setCurrentArtifact(next);
            setHistorical(null);
            setHistoricalPosition(null);
          }}
        />
      </main>

      <ArtifactDrawer
        open={drawer === "history"}
        title="History"
        subtitle="Newest first"
        triggerRef={overflowTriggerRef}
        onClose={closeDrawer}
      >
        <ArtifactHistoryPanel
          variantId={currentArtifact.variant.id}
          client={client}
          refreshKey={currentArtifact.variant.revisionId}
          selectedPosition={historicalPosition}
          presentation="drawer"
          onSelect={(detail, position) => {
            setHistorical(detail);
            setHistoricalPosition(position);
          }}
        />
      </ArtifactDrawer>

      <ArtifactDrawer
        open={drawer === "sources"}
        title="Sources"
        subtitle="Attached sources and related content"
        triggerRef={overflowTriggerRef}
        onClose={closeDrawer}
      >
        <ArtifactSources
          sources={shownArtifact.variant.sources}
          relatedArtifacts={shownArtifact.relatedArtifacts}
        />
      </ArtifactDrawer>

      {replicateOpen ? (
        <CreateRelatedContentDialog
          pack={shownArtifact}
          client={client}
          open={replicateOpen}
          onClose={() => setReplicateOpen(false)}
        />
      ) : null}
    </div>
  );
}

function MenuButton({ icon: Icon, children, onClick }: { icon: typeof Copy; children: string; onClick: () => void }) {
  return (
    <button type="button" role="menuitem" onClick={onClick} className="flex h-8 w-full items-center gap-2 rounded-[4px] px-2.5 text-left transition hover:bg-black/[0.04] focus-visible:bg-black/[0.04] focus-visible:outline-none dark:hover:bg-white/10 dark:focus-visible:bg-white/10">
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
      <aside ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby={`artifact-${title.toLowerCase()}-title`} tabIndex={-1} className="absolute inset-y-0 right-0 flex w-full max-w-[420px] flex-col overflow-y-auto rounded-l-[12px] border border-black/10 bg-white px-7 py-8 shadow-[-8px_0_24px_rgba(0,0,0,0.06)] outline-none dark:border-white/10 dark:bg-[#202024]">
        <header className="flex h-10 shrink-0 items-center justify-between">
          <h2 id={`artifact-${title.toLowerCase()}-title`} className="font-display text-[26px] leading-8 text-black/88 dark:text-white/90">{title}</h2>
          <button type="button" onClick={onClose} className="inline-flex h-[34px] items-center gap-2 rounded-full border border-black/10 px-3 text-sm font-medium text-black/60 transition hover:bg-black/[0.03] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/15 dark:border-white/12 dark:text-white/65 dark:hover:bg-white/10">
            Close
          </button>
        </header>
        <p className="mt-[18px] text-sm leading-5 text-black/52 dark:text-white/52">{subtitle}</p>
        <div className="mt-[18px] min-h-0">{children}</div>
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
    <div className="space-y-6">
      <div>
        <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-black/45 dark:text-white/45">
          Attached sources ({uniqueSources.length})
        </h3>
        {!uniqueSources.length ? (
          <p className="rounded-[8px] border border-dashed border-black/10 px-3.5 py-4 text-sm leading-6 text-black/52 dark:border-white/12 dark:text-white/55">
            No current sources are available for this artifact.
          </p>
        ) : (
          <ol className="space-y-2.5" aria-label="Current sources">
            {uniqueSources.map((source, index) => (
              <li key={source.evidenceId} className="rounded-[8px] border border-black/10 px-3.5 py-3 dark:border-white/10">
                <Citation source={{ title: source.sourceLabel, url: source.originalUrl ?? undefined }} number={index + 1} variant="label" className="max-w-full text-[15px] font-semibold" />
                <p className="mt-1 truncate text-xs text-black/45 dark:text-white/45">
                  {source.provider === "USER_CONFIRMED" ? "Confirmed in Plot" : sourceHostname(source.originalUrl)}
                </p>
              </li>
            ))}
          </ol>
        )}
      </div>

      {relatedArtifacts && relatedArtifacts.length > 0 ? (
        <div>
          <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-black/45 dark:text-white/45">
            Related content ({relatedArtifacts.length})
          </h3>
          <p className="mb-2.5 text-xs text-black/50 dark:text-white/50">
            Artifacts generated from the same verified source bundle.
          </p>
          <ul className="space-y-2" aria-label="Related artifacts">
            {relatedArtifacts.map((related) => (
              <li key={related.id}>
                <Link
                  href={`/artifacts/${related.id}`}
                  className="flex items-center justify-between rounded-lg border border-black/10 p-2.5 transition hover:border-black/20 hover:bg-black/[0.02] dark:border-white/10 dark:hover:border-white/20 dark:hover:bg-white/[0.02]"
                >
                  <div className="min-w-0 flex-1 pr-2">
                    <p className="truncate text-xs font-medium text-black/85 dark:text-white/85">
                      {related.title || "Untitled artifact"}
                    </p>
                    <span className="mt-0.5 inline-block text-[10px] text-black/50 dark:text-white/50">
                      {related.contentType === "LAUNCH_ANNOUNCEMENT" ? "Launch announcement" : "Changelog"}
                    </span>
                  </div>
                  <span className="shrink-0 rounded bg-black/5 px-1.5 py-0.5 text-[10px] font-medium text-black/60 dark:bg-white/10 dark:text-white/60">
                    {related.status}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

function sourceHostname(value: string | null) {
  if (!value) return "";
  try {
    return new URL(value).hostname;
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
