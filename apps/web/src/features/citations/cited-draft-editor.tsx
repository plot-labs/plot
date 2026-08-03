"use client";

import { ContentEditable } from "@lexical/react/LexicalContentEditable";
import { HistoryPlugin } from "@lexical/react/LexicalHistoryPlugin";
import { LexicalComposer } from "@lexical/react/LexicalComposer";
import { LexicalErrorBoundary } from "@lexical/react/LexicalErrorBoundary";
import { OnChangePlugin } from "@lexical/react/LexicalOnChangePlugin";
import { RichTextPlugin } from "@lexical/react/LexicalRichTextPlugin";
import { useLexicalComposerContext } from "@lexical/react/LexicalComposerContext";
import type { EditorState, LexicalEditor } from "lexical";
import { $getRoot } from "lexical";
import { Save } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

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
  const revisionKey = `${props.pack.variant.revisionId ?? props.pack.variant.id}:${props.pack.variant.revisionNumber ?? 1}`;
  return <ArtifactEditor key={revisionKey} {...props} />;
}

function ArtifactEditor({ pack, onSaveArtifact, onPackChange }: CitedDraftEditorProps) {
  const sentences = useMemo(
    () => [...pack.variant.sentences].sort((a, b) => a.orderIndex - b.orderIndex),
    [pack.variant.sentences],
  );
  const revisionNumber = pack.variant.revisionNumber ?? 1;
  const revisionKey = `${pack.variant.revisionId ?? pack.variant.id}:${revisionNumber}`;
  const lexicalContent = useMemo(
    () => pack.variant.lexicalContent ?? lexicalContentFor(sentences.map((sentence) => sentence.body)),
    [pack.variant.lexicalContent, sentences],
  );
  const [draftState, setDraftState] = useState<Record<string, unknown>>(lexicalContent);
  const [draftStatements, setDraftStatements] = useState<ContentStatementInput[]>(() => statementInputs(sentences));
  const [editor, setEditor] = useState<LexicalEditor | null>(null);
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
      setMessage(`Revision ${updated.variant.revisionNumber ?? revisionNumber + 1} saved.`);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "The draft could not be saved.");
    } finally {
      setSaving(false);
    }
  }

  function focusStatement(statementId: string) {
    const element = editor
      ? Array.from(editor.getRootElement()?.querySelectorAll<HTMLElement>("[data-statement-id]") ?? [])
        .find((candidate) => candidate.dataset.statementId === statementId)
      : null;
    if (!element) return;
    element.tabIndex = -1;
    element.focus({ preventScroll: true });
    element.scrollIntoView?.({ block: "center", behavior: "smooth" });
    element.dataset.statementHighlight = "true";
    window.setTimeout(() => {
      if (element.isConnected) delete element.dataset.statementHighlight;
    }, 2_000);
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
        <SourcesPopover sources={pack.variant.sources ?? []} onFocusStatement={focusStatement} />
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
          <EditorReferencePlugin onEditor={setEditor} />
          <StatementDomPlugin statementIds={sentences.map((sentence) => sentence.id)} />
          <OnChangePlugin
            onChange={(nextState) => {
              setDraftState(nextState.toJSON() as unknown as Record<string, unknown>);
              setDraftStatements(extractStatementInputs(nextState, sentences));
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

function EditorReferencePlugin({ onEditor }: { onEditor: (editor: LexicalEditor | null) => void }) {
  const [editor] = useLexicalComposerContext();
  useEffect(() => {
    onEditor(editor);
    return () => onEditor(null);
  }, [editor, onEditor]);
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
      });
    }
    sync();
    return editor.registerUpdateListener(() => {
      requestAnimationFrame(sync);
    });
  }, [editor, statementIds]);
  return null;
}

function extractStatementInputs(state: EditorState, sentences: ContentPack["variant"]["sentences"]): ContentStatementInput[] {
  const existing = [...sentences].sort((a, b) => a.orderIndex - b.orderIndex);
  const texts: string[] = [];
  state.read(() => {
    $getRoot().getChildren().forEach((node) => {
      texts.push(node.getTextContent());
    });
  });
  return texts.map((body, orderIndex) => ({
    id: existing[orderIndex]?.id ?? null,
    orderIndex,
    body,
  }));
}

function statementInputs(sentences: ContentPack["variant"]["sentences"]): ContentStatementInput[] {
  return [...sentences]
    .sort((a, b) => a.orderIndex - b.orderIndex)
    .map((sentence, orderIndex) => ({ id: sentence.id, orderIndex, body: sentence.body }));
}

function lexicalContentFor(bodies: string[]): Record<string, unknown> {
  return {
    root: {
      children: bodies.map((body) => ({
        children: [{ detail: 0, format: 0, mode: "normal", style: "", text: body, type: "text", version: 1 }],
        direction: null,
        format: "",
        indent: 0,
        type: "paragraph",
        version: 1,
      })),
      direction: null,
      format: "",
      indent: 0,
      type: "root",
      version: 1,
    },
  };
}
