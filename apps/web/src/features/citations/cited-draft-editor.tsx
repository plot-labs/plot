"use client";

import { ContentEditable } from "@lexical/react/LexicalContentEditable";
import { HistoryPlugin } from "@lexical/react/LexicalHistoryPlugin";
import { LexicalComposer } from "@lexical/react/LexicalComposer";
import { LexicalErrorBoundary } from "@lexical/react/LexicalErrorBoundary";
import { OnChangePlugin } from "@lexical/react/LexicalOnChangePlugin";
import { RichTextPlugin } from "@lexical/react/LexicalRichTextPlugin";
import { useLexicalComposerContext } from "@lexical/react/LexicalComposerContext";
import type { EditorState } from "lexical";
import { $getRoot } from "lexical";
import { Save } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";

import type {
  ContentPack,
  ContentStatementInput,
} from "@plot/api-client";
import { SourcesPopover } from "./sources-popover";

type SaveArtifactInput = {
  expectedRevisionNumber: number;
  lexicalContent: Record<string, unknown>;
  statements: ContentStatementInput[];
};

type CitedDraftEditorProps = {
  pack: ContentPack;
  onSaveArtifact: (input: SaveArtifactInput) => Promise<ContentPack>;
  onPackChange?: (pack: ContentPack) => void;
};

export function CitedDraftEditor(props: CitedDraftEditorProps) {
  const revisionKey = `${props.pack.variant.revisionId}:${props.pack.variant.revisionNumber}`;
  return <ArtifactEditor key={revisionKey} {...props} />;
}

function ArtifactEditor({ pack, onSaveArtifact, onPackChange }: CitedDraftEditorProps) {
  const sentences = useMemo(
    () => [...pack.variant.sentences].sort((a, b) => a.orderIndex - b.orderIndex),
    [pack.variant.sentences],
  );
  const revisionNumber = pack.variant.revisionNumber;
  const revisionKey = `${pack.variant.revisionId}:${revisionNumber}`;
  const lexicalContent = useMemo(() => pack.variant.lexicalContent, [pack.variant.lexicalContent]);
  const initialStatementBlocks = useMemo(() => statementBlocksFor(sentences), [sentences]);
  const statementBlocksRef = useRef(initialStatementBlocks);
  const [draftState, setDraftState] = useState<Record<string, unknown>>(lexicalContent);
  const [draftStatements, setDraftStatements] = useState<ContentStatementInput[]>(() => statementInputs(initialStatementBlocks));
  const [statementBlocks, setStatementBlocks] = useState<StatementBlock[]>(initialStatementBlocks);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");

  async function save() {
    if (saving) return;
    setSaving(true);
    setMessage("");
    try {
      const updated = await onSaveArtifact({
        expectedRevisionNumber: revisionNumber,
        lexicalContent: draftState,
        statements: draftStatements,
      });
      onPackChange?.(updated);
      setMessage(`Revision ${updated.variant.revisionNumber} saved.`);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "The draft could not be saved.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <section aria-label="Draft editor" className="rounded-xl border border-black/10 bg-white dark:border-white/10 dark:bg-white/[0.04]">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-black/[0.07] px-4 py-4 dark:border-white/10 sm:px-6">
        <div>
          <h2 className="text-sm font-semibold text-black/82 dark:text-white/88">Draft</h2>
          <p className="mt-1 text-xs text-black/50 dark:text-white/52">
            Edit the whole artifact. Sources stay outside the document and are bound to this revision.
          </p>
        </div>
        <SourcesPopover sources={pack.variant.sources} />
      </div>

      <LexicalComposer
        key={revisionKey}
        initialConfig={{
          namespace: `plot-artifact-${pack.variant.id}`,
          editable: true,
          editorState: JSON.stringify(lexicalContent),
          onError: (error) => {
            throw error;
          },
        }}
      >
        <div className="relative px-4 py-5 sm:px-6">
          <RichTextPlugin
            contentEditable={<ContentEditable aria-label="Draft content" className="min-h-[260px] whitespace-pre-wrap rounded-lg border border-black/10 bg-white px-4 py-4 text-[15px] leading-7 text-black/84 outline-none focus-within:border-black/35 dark:border-white/12 dark:bg-[#18181b] dark:text-white/86 dark:focus-within:border-white/35" />}
            placeholder={<div className="pointer-events-none absolute left-8 top-9 text-sm text-black/35 dark:left-10 dark:text-white/35">Write the source-backed artifact…</div>}
            ErrorBoundary={LexicalErrorBoundary}
          />
          <HistoryPlugin />
          <StatementDomPlugin statementIds={statementBlocks.map((statement) => statement.id)} />
          <OnChangePlugin
            onChange={(nextState) => {
              setDraftState(nextState.toJSON() as unknown as Record<string, unknown>);
              const nextBodies = extractBlockBodies(nextState);
              const nextBlocks = reconcileStatementBlocks(statementBlocksRef.current, nextBodies);
              statementBlocksRef.current = nextBlocks;
              setStatementBlocks(nextBlocks);
              setDraftStatements(statementInputs(nextBlocks));
            }}
          />
        </div>
      </LexicalComposer>

      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-black/[0.07] px-4 py-3 dark:border-white/10 sm:px-6">
        <p className="text-xs text-black/48 dark:text-white/50">Revision {revisionNumber}</p>
        <button
          type="button"
          disabled={saving}
          onClick={() => void save()}
          className="inline-flex min-h-10 items-center gap-2 rounded-lg bg-black px-3 text-sm font-semibold text-white transition hover:bg-black/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-40 dark:bg-white dark:text-black dark:hover:bg-white/85"
        >
          <Save aria-hidden="true" className="size-4" />
          {saving ? "Saving…" : "Save draft"}
        </button>
      </div>
      {message ? (
        <p role="status" aria-live="polite" className="border-t border-black/[0.07] px-4 py-3 text-xs text-black/58 dark:border-white/10 dark:text-white/58 sm:px-6">
          {message}
        </p>
      ) : null}
    </section>
  );
}

function StatementDomPlugin({ statementIds }: { statementIds: string[] }) {
  const [editor] = useLexicalComposerContext();
  useEffect(() => {
    function sync() {
      const root = editor.getRootElement();
      if (!root) return;
      const blocks = Array.from(root.children).filter((element): element is HTMLElement => element instanceof HTMLElement);
      blocks.forEach((element, index) => {
        const statementId = statementIds[index];
        if (statementId) element.dataset.statementId = statementId;
        else delete element.dataset.statementId;
        element.dataset.statementOrder = String(index + 1);
        element.tabIndex = statementId ? -1 : 0;
        element.dataset.statementBlock = statementId ? "true" : "false";
      });
    }
    sync();
    return editor.registerUpdateListener(() => {
      requestAnimationFrame(sync);
    });
  }, [editor, statementIds]);
  return null;
}

function extractBlockBodies(state: EditorState): string[] {
  const texts: string[] = [];
  state.read(() => {
    $getRoot().getChildren().forEach((node) => {
      texts.push(node.getTextContent());
    });
  });
  return texts;
}

export type StatementBlock = { id: string; body: string };

function statementBlocksFor(sentences: ContentPack["variant"]["sentences"]): StatementBlock[] {
  return [...sentences]
    .sort((a, b) => a.orderIndex - b.orderIndex)
    .map((sentence) => ({ id: sentence.id, body: sentence.body }));
}

function statementInputs(blocks: StatementBlock[]): ContentStatementInput[] {
  return blocks.map((block, orderIndex) => ({ id: block.id, orderIndex, body: block.body }));
}

/**
 * Reconciles Lexical's top-level blocks with application-owned statement IDs.
 * Lexical node keys are intentionally not read or persisted here. Exact body
 * matches preserve IDs across reorder; an equal-sized unmatched region is
 * treated as edits; ambiguous insert/delete regions receive fresh IDs rather
 * than moving evidence to a neighboring statement.
 */
export function reconcileStatementBlocks(
  previous: StatementBlock[],
  nextBodies: string[],
  createId: () => string = newStatementId,
): StatementBlock[] {
  const assignments: Array<StatementBlock | null> = nextBodies.map(() => null);
  const usedPrevious = new Set<number>();
  const anchors: Array<{ nextIndex: number; previousIndex: number }> = [];

  nextBodies.forEach((body, nextIndex) => {
    const candidates = previous
      .map((block, previousIndex) => ({ block, previousIndex }))
      .filter(({ block, previousIndex }) => block.body === body && !usedPrevious.has(previousIndex))
      .sort((left, right) => {
        const distance = Math.abs(left.previousIndex - nextIndex) - Math.abs(right.previousIndex - nextIndex);
        return distance || left.previousIndex - right.previousIndex;
      });
    const match = candidates[0];
    if (!match) return;
    usedPrevious.add(match.previousIndex);
    assignments[nextIndex] = match.block;
    anchors.push({ nextIndex, previousIndex: match.previousIndex });
  });

  anchors.sort((left, right) => left.nextIndex - right.nextIndex);
  let previousCursor = -1;
  let nextCursor = -1;
  const fillRegion = (previousEnd: number, nextEnd: number) => {
    const oldRegion = previous.slice(previousCursor + 1, previousEnd).filter((_, index) => !usedPrevious.has(previousCursor + 1 + index));
    const newIndexes = Array.from({ length: nextEnd - nextCursor - 1 }, (_, index) => nextCursor + 1 + index)
      .filter((index) => assignments[index] === null);
    if (oldRegion.length === newIndexes.length) {
      newIndexes.forEach((index, offset) => {
        assignments[index] = { id: oldRegion[offset].id, body: nextBodies[index] };
      });
    } else {
      newIndexes.forEach((index) => {
        assignments[index] = { id: createId(), body: nextBodies[index] };
      });
    }
  };

  anchors.forEach((anchor) => {
    fillRegion(anchor.previousIndex, anchor.nextIndex);
    previousCursor = anchor.previousIndex;
    nextCursor = anchor.nextIndex;
  });
  fillRegion(previous.length, nextBodies.length);
  return assignments.map((block, index) => block ?? { id: createId(), body: nextBodies[index] });
}

function newStatementId(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  bytes.forEach((_, index) => { bytes[index] = Math.floor(Math.random() * 256); });
  globalThis.crypto?.getRandomValues?.(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
