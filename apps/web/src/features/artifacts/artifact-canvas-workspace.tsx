"use client";

import Image from "next/image";
import { Citation } from "@astryxdesign/core/Citation";
import { Copy, Ellipsis, History, Library } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type RefObject } from "react";

import type { Artifact, ArtifactHistoryDetail, PlotApiClient } from "@plot/api-client";
import { ArtifactDocumentSurface } from "@/features/artifacts/artifact-document-surface";
import { ArtifactHistoryPanel } from "@/features/citations/artifact-history-panel";
import { ExportDialog } from "@/features/citations/export-dialog";
import type { SaveArtifactInput } from "@/features/citations/cited-draft-editor";

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
  const [saveRequestToken, setSaveRequestToken] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);
  const [drawer, setDrawer] = useState<Drawer>(null);
  const actionsRef = useRef<HTMLDivElement>(null);
  const overflowTriggerRef = useRef<HTMLButtonElement>(null);
  const shownArtifact = historical?.artifact ?? currentArtifact;
  const readOnly = Boolean(historical);
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
      <header className="flex h-14 shrink-0 items-center justify-end border-b border-black/[0.08] bg-[#f8fafc] px-5 dark:border-white/10 dark:bg-[#111113]">
        <div ref={actionsRef} className="relative flex h-9 items-center gap-2">
          <span role="status" aria-live="polite" className="mr-1 hidden text-xs text-black/45 dark:text-white/48 sm:inline">
            {saveStateLabel(saveState, readOnly)}
          </span>
          <button
            type="button"
            disabled={readOnly || saveState === "saving"}
            onClick={() => setSaveRequestToken((value) => value + 1)}
            className="inline-flex h-9 min-w-[104px] items-center justify-center gap-2 rounded-[8px] bg-[#c84236] px-3 text-[13px] font-medium text-white transition hover:bg-[#b73a31] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#c84236]/35 focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-45"
          >
            <Image src="/icons/product-publish.svg" alt="" width={16} height={16} className="size-4" />
            {readOnly ? "Snapshot" : saveState === "saving" ? "Saving…" : "Save draft"}
          </button>
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
            <div role="menu" aria-label="Artifact actions" className="absolute right-0 top-[54px] z-40 w-[196px] rounded-[8px] border border-black/10 bg-white p-2 text-[13px] text-[#18181b] shadow-[0_8px_20px_rgba(0,0,0,0.1)] dark:border-white/10 dark:bg-[#242529] dark:text-white">
              <MenuButton icon={History} onClick={() => openDrawer("history")}>History</MenuButton>
              <MenuButton icon={Library} onClick={() => openDrawer("sources")}>Sources</MenuButton>
              <ExportDialog pack={shownArtifact} client={client} presentation="menu" />
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
          saveRequestToken={saveRequestToken}
          saveState={saveState}
          onSaveStateChange={setSaveState}
          onSaveArtifact={onSaveArtifact}
          onPackChange={(next) => {
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
        subtitle="Attached sources"
        triggerRef={overflowTriggerRef}
        onClose={closeDrawer}
      >
        <ArtifactSources sources={shownArtifact.variant.sources} />
      </ArtifactDrawer>
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

function ArtifactSources({ sources }: { sources: Artifact["variant"]["sources"] }) {
  const uniqueSources = sources.filter((source, index, all) => all.findIndex((candidate) => candidate.originalUrl === source.originalUrl) === index);
  if (!uniqueSources.length) {
    return <p className="rounded-[8px] border border-dashed border-black/10 px-3.5 py-4 text-sm leading-6 text-black/52 dark:border-white/12 dark:text-white/55">No current sources are available for this artifact.</p>;
  }

  return (
    <ol className="space-y-2.5" aria-label="Current sources">
      {uniqueSources.map((source, index) => (
        <li key={source.evidenceId} className="rounded-[8px] border border-black/10 px-3.5 py-3 dark:border-white/10">
          <Citation source={{ title: source.sourceLabel, url: source.originalUrl }} number={index + 1} variant="label" className="max-w-full text-[15px] font-semibold" />
          <p className="mt-1 truncate text-xs text-black/45 dark:text-white/45">{sourceHostname(source.originalUrl)}</p>
        </li>
      ))}
    </ol>
  );
}

function sourceHostname(value: string) {
  try {
    return new URL(value).hostname;
  } catch {
    return value;
  }
}

function saveStateLabel(state: "saved" | "saving" | "dirty" | "error", readOnly: boolean) {
  if (readOnly) return "Saved snapshot";
  if (state === "saving") return "Saving…";
  if (state === "dirty") return "Unsaved changes";
  if (state === "error") return "Save needs attention";
  return "Saved";
}

function focusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex="-1"])')).filter((element) => element.getAttribute("aria-hidden") !== "true");
}
