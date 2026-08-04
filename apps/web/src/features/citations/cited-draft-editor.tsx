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
import { useEffect, useMemo, useRef, useState, type MutableRefObject } from "react";

import type {
  Artifact,
  ContentStatementInput,
} from "@plot/api-client";
import { SourcesPopover } from "./sources-popover";

export type SaveArtifactInput = {
  expectedRevisionNumber: number;
  lexicalContent: Record<string, unknown>;
  statements: ContentStatementInput[];
};

type CitedDraftEditorProps = {
  pack: Artifact;
  onSaveArtifact: (input: SaveArtifactInput) => Promise<Artifact>;
  onPackChange?: (pack: Artifact) => void;
  readOnly?: boolean;
  embedded?: boolean;
  onSaveStateChange?: (state: "saved" | "saving" | "dirty" | "error") => void;
  initialDraft?: Omit<SaveArtifactInput, "expectedRevisionNumber">;
  onDraftChange?: (draft: Omit<SaveArtifactInput, "expectedRevisionNumber">) => void;
};

export function CitedDraftEditor(props: CitedDraftEditorProps) {
  const revisionKey = `${props.pack.variant.revisionId}:${props.pack.variant.revisionNumber}:${props.readOnly ? "read-only" : "editable"}`;
  return <ArtifactEditor key={revisionKey} {...props} />;
}

function ArtifactEditor({ pack, onSaveArtifact, onPackChange, readOnly = false, embedded = false, onSaveStateChange, initialDraft, onDraftChange }: CitedDraftEditorProps) {
  const sentences = useMemo(
    () => [...pack.variant.sentences].sort((a, b) => a.orderIndex - b.orderIndex),
    [pack.variant.sentences],
  );
  const revisionNumber = pack.variant.revisionNumber;
  const revisionKey = `${pack.variant.revisionId}:${revisionNumber}`;
  const lexicalContent = useMemo(() => pack.variant.lexicalContent, [pack.variant.lexicalContent]);
  const initialStatementBlocks = useMemo(
    () => initialDraft ? statementBlocksForInputs(initialDraft.statements) : statementBlocksFor(sentences),
    [initialDraft, sentences],
  );
  const statementIds = useMemo(() => initialStatementBlocks.map((statement) => statement.id), [initialStatementBlocks]);
  const statementIdentityMapRef = useRef<Map<string, string>>(new Map());
  const identityInitializedRef = useRef(false);
  const [draftState, setDraftState] = useState<Record<string, unknown>>(initialDraft?.lexicalContent ?? lexicalContent);
  const [draftStatements, setDraftStatements] = useState<ContentStatementInput[]>(() => initialDraft?.statements ?? statementInputs(initialStatementBlocks));
  const [statementBlocks, setStatementBlocks] = useState<StatementBlock[]>(initialStatementBlocks);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => {
    onSaveStateChange?.("saved");
  }, [onSaveStateChange, revisionKey]);

  async function save() {
    if (saving || readOnly) return;
    setSaving(true);
    setMessage("");
    onSaveStateChange?.("saving");
    try {
      const updated = await onSaveArtifact({
        expectedRevisionNumber: revisionNumber,
        lexicalContent: draftState,
        statements: draftStatements,
      });
      onPackChange?.(updated);
      setMessage(`Saved ${new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" }).format(new Date())}.`);
      onSaveStateChange?.("saved");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "The draft could not be saved.");
      onSaveStateChange?.("error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <section aria-label={readOnly ? "Historical artifact preview" : "Artifact editor"} className={embedded ? "min-w-0" : "rounded-xl border border-black/10 bg-white dark:border-white/10 dark:bg-white/[0.04]"}>
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-black/[0.07] px-4 py-4 dark:border-white/10 sm:px-6">
        <div>
          <h2 className="text-sm font-semibold text-black/82 dark:text-white/88">{readOnly ? "Historical preview" : "Artifact document"}</h2>
          <p className="mt-1 text-xs text-black/50 dark:text-white/52">
            {readOnly
              ? "This snapshot is read-only. Editing and delivery are disabled."
              : "Edit the whole artifact. Sources stay outside the document and stay attached to it."}
          </p>
        </div>
        <SourcesPopover sources={pack.variant.sources} />
      </div>

      <LexicalComposer
        key={revisionKey}
        initialConfig={{
          namespace: `plot-artifact-${pack.variant.id}`,
          editable: !readOnly,
          editorState: JSON.stringify(draftState),
          onError: (error) => {
            throw error;
          },
        }}
      >
        <div className="relative px-4 py-5 sm:px-6">
          <RichTextPlugin
            contentEditable={<ContentEditable aria-label={readOnly ? "Historical artifact content" : "Draft content"} aria-readonly={readOnly} className={`min-h-[260px] whitespace-pre-wrap rounded-lg border border-black/10 px-4 py-4 text-[15px] leading-7 text-black/84 outline-none focus-within:border-black/35 dark:border-white/12 dark:text-white/86 dark:focus-within:border-white/35 ${readOnly ? "bg-black/[0.025] dark:bg-white/[0.025]" : "bg-white dark:bg-[#18181b]"}`} />}
            placeholder={<div className="pointer-events-none absolute left-8 top-9 text-sm text-black/35 dark:left-10 dark:text-white/35">Write the source-backed artifact…</div>}
            ErrorBoundary={LexicalErrorBoundary}
          />
          <HistoryPlugin />
          <StatementIdentityPlugin
            statementIds={statementIds}
            identityMapRef={statementIdentityMapRef}
            initializedRef={identityInitializedRef}
          />
          <StatementDomPlugin statementIds={statementBlocks.map((statement) => statement.id)} />
          <OnChangePlugin
            onChange={(nextState) => {
              setDraftState(nextState.toJSON() as unknown as Record<string, unknown>);
              const nextNodes = extractStatementNodes(nextState);
              if (!identityInitializedRef.current) {
                statementIdentityMapRef.current = initializeStatementIdentityMap(
                  nextNodes.map((node) => node.key),
                  statementIds,
                );
                identityInitializedRef.current = true;
              }
              const projected = projectStatementBlocks(statementIdentityMapRef.current, nextNodes);
              statementIdentityMapRef.current = projected.mapping;
              setStatementBlocks(projected.blocks);
              setDraftStatements(statementInputs(projected.blocks));
              if (!readOnly) {
                onDraftChange?.({ lexicalContent: nextState.toJSON() as unknown as Record<string, unknown>, statements: statementInputs(projected.blocks) });
                onSaveStateChange?.("dirty");
              }
            }}
          />
        </div>
      </LexicalComposer>

      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-black/[0.07] px-4 py-3 dark:border-white/10 sm:px-6">
        <p className="text-xs text-black/48 dark:text-white/50">{readOnly ? "Saved snapshot" : saving ? "Saving…" : message || "Saved"}</p>
        {!readOnly ? (
          <button
            type="button"
            disabled={saving}
            onClick={() => void save()}
            className="inline-flex min-h-10 items-center gap-2 rounded-lg bg-black px-3 text-sm font-semibold text-white transition hover:bg-black/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-40 dark:bg-white dark:text-black dark:hover:bg-white/85"
          >
            <Save aria-hidden="true" className="size-4" />
            {saving ? "Saving…" : "Save draft"}
          </button>
        ) : null}
      </div>
      {message ? (
        <p role="status" aria-live="polite" className="border-t border-black/[0.07] px-4 py-3 text-xs text-black/58 dark:border-white/10 dark:text-white/58 sm:px-6">
          {message}
        </p>
      ) : null}
    </section>
  );
}

function StatementIdentityPlugin({
  statementIds,
  identityMapRef,
  initializedRef,
}: {
  statementIds: string[];
  identityMapRef: MutableRefObject<Map<string, string>>;
  initializedRef: MutableRefObject<boolean>;
}) {
  const [editor] = useLexicalComposerContext();

  useEffect(() => {
    editor.getEditorState().read(() => {
      const nodes = $getRoot().getChildren();
      identityMapRef.current = initializeStatementIdentityMap(
        nodes.map((node) => node.getKey()),
        statementIds,
      );
      initializedRef.current = true;
    });
  }, [editor, identityMapRef, initializedRef, statementIds]);

  return null;
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

export type EphemeralLexicalStatementNode = { key: string; body: string };

function extractStatementNodes(state: EditorState): EphemeralLexicalStatementNode[] {
  const nodes: EphemeralLexicalStatementNode[] = [];
  state.read(() => {
    $getRoot().getChildren().forEach((node) => {
      nodes.push({ key: node.getKey(), body: node.getTextContent() });
    });
  });
  return nodes;
}

export type StatementBlock = { id: string; body: string };

function statementBlocksFor(sentences: Artifact["variant"]["sentences"]): StatementBlock[] {
  return [...sentences]
    .sort((a, b) => a.orderIndex - b.orderIndex)
    .map((sentence) => ({ id: sentence.id, body: sentence.body }));
}

function statementInputs(blocks: StatementBlock[]): ContentStatementInput[] {
  return blocks.map((block, orderIndex) => ({ id: block.id, orderIndex, body: block.body }));
}

function statementBlocksForInputs(statements: ContentStatementInput[]): StatementBlock[] {
  return statements
    .slice()
    .sort((left, right) => (left.orderIndex ?? Number.MAX_SAFE_INTEGER) - (right.orderIndex ?? Number.MAX_SAFE_INTEGER))
    .flatMap((statement) => statement.id ? [{ id: statement.id, body: statement.body ?? "" }] : []);
}

/**
 * Builds the active-session correlation from the server-aligned revision.
 * Lexical keys are ephemeral and never leave this in-memory map.
 */
export function initializeStatementIdentityMap(
  nodeKeys: string[],
  statementIds: string[],
): Map<string, string> {
  return new Map(
    nodeKeys.flatMap((key, index) => {
      const statementId = statementIds[index];
      return statementId ? [[key, statementId] as const] : [];
    }),
  );
}

/**
 * Projects the current ordered Lexical blocks into application statements.
 * Existing node keys retain their application IDs after edits and moves;
 * only genuinely new keys receive a new UUID. Deleted keys disappear from
 * the next map, so their evidence cannot be transferred to another block.
 */
export function projectStatementBlocks(
  previous: ReadonlyMap<string, string>,
  nodes: EphemeralLexicalStatementNode[],
  createId: () => string = newStatementId,
): { mapping: Map<string, string>; blocks: StatementBlock[] } {
  const mapping = new Map<string, string>();
  const blocks = nodes.map((node) => {
    const id = previous.get(node.key) ?? createId();
    mapping.set(node.key, id);
    return { id, body: node.body };
  });
  return { mapping, blocks };
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
