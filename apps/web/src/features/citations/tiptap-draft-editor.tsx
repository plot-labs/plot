"use client";

import { useEditor, EditorContent } from "@tiptap/react";
import { Extension, Node as TiptapNode, mergeAttributes, type Editor } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import Placeholder from "@tiptap/extension-placeholder";
import { Markdown } from "tiptap-markdown";
import { Save } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import type {
  Artifact,
  ContentStatementInput,
} from "@plot/api-client";
import { SourcesPopover } from "./sources-popover";
import { TiptapCitationExtension } from "./tiptap-citation-extension";
import {
  convertArtifactDocumentToTiptap,
  defaultStatementsFor as adapterDefaultStatementsFor,
  extractArtifactStatements,
  tiptapToArtifactDocument,
} from "./artifact-document-adapter";

const ArtifactIdentityExtension = Extension.create({
  name: "artifactIdentity",

  addGlobalAttributes() {
    return [
      {
        types: ["paragraph", "heading", "bulletList", "orderedList", "listItem"],
        attributes: {
          nodeId: { default: null },
          // Rendered to the DOM so export/publish warning jumps can locate
          // and focus the affected statement block. V1 paragraphs carry
          // `sourceStatementId` instead of `statementId`; both resolve here.
          statementId: {
            default: null,
            parseHTML: (element) => element.getAttribute("data-statement-id"),
            renderHTML: (attributes) => {
              const id = typeof attributes.statementId === "string" && attributes.statementId
                ? attributes.statementId
                : typeof attributes.sourceStatementId === "string" && attributes.sourceStatementId
                  ? attributes.sourceStatementId
                  : null;
              return id ? { "data-statement-id": id, tabindex: "-1" } : {};
            },
          },
          sourceStatementId: { default: null },
          lineage: { default: [] },
        },
      },
    ];
  },
});

const TiptapCtaExtension = TiptapNode.create({
  name: "cta",
  group: "block",
  content: "inline*",
  defining: true,
  atom: true,
  isolating: true,

  addAttributes() {
    return {
      nodeId: { default: null },
      statementId: { default: null },
      sourceStatementId: { default: null },
      destinationId: { default: null },
      destinationLabel: { default: null },
      destinationUrl: { default: null },
    };
  },

  parseHTML() {
    return [{ tag: "a[data-cta-node]" }];
  },

  renderHTML({ HTMLAttributes }) {
    const url = typeof HTMLAttributes.destinationUrl === "string" && isSafeCtaUrl(HTMLAttributes.destinationUrl)
      ? HTMLAttributes.destinationUrl
      : undefined;
    return ["a", mergeAttributes(HTMLAttributes, {
      "data-cta-node": "",
      href: url,
      rel: url ? "noreferrer" : undefined,
      target: url ? "_blank" : undefined,
    }), 0];
  },
});

function isSafeCtaUrl(value: string): boolean {
  try {
    const url = new URL(value.trim());
    return url.protocol === "https:" && Boolean(url.hostname) && !url.username && !url.password && (url.port === "" || url.port === "443");
  } catch {
    return false;
  }
}

export type SaveArtifactInput = {
  expectedRevisionNumber: number;
  lexicalContent: Record<string, unknown>;
  statements: ContentStatementInput[];
};

type TiptapDraftEditorProps = {
  pack: Artifact;
  onSaveArtifact: (input: SaveArtifactInput) => Promise<Artifact>;
  onPackChange?: (pack: Artifact) => void;
  readOnly?: boolean;
  embedded?: boolean;
  onSaveStateChange?: (state: "saved" | "saving" | "dirty" | "error") => void;
  initialDraft?: Omit<SaveArtifactInput, "expectedRevisionNumber">;
  onDraftChange?: (draft: Omit<SaveArtifactInput, "expectedRevisionNumber">) => void;
  presentation?: "panel" | "document";
  saveRequestToken?: number;
};

export function TiptapDraftEditor(props: TiptapDraftEditorProps) {
  const revisionKey = `${props.pack.variant.revisionId}:${props.pack.variant.revisionNumber}:${props.readOnly ? "read-only" : "editable"}`;
  return <TiptapArtifactEditor key={revisionKey} {...props} />;
}

function TiptapArtifactEditor({
  pack,
  onSaveArtifact,
  onPackChange,
  readOnly = false,
  embedded = false,
  onSaveStateChange,
  initialDraft,
  onDraftChange,
  presentation = "panel",
  saveRequestToken,
}: TiptapDraftEditorProps) {
  const sentences = useMemo(
    () => [...pack.variant.sentences].sort((a, b) => a.orderIndex - b.orderIndex),
    [pack.variant.sentences],
  );
  const revisionNumber = pack.variant.revisionNumber;
  const revisionKey = `${pack.variant.revisionId}:${revisionNumber}`;
  const initialContent = useMemo(
    () => initialDraft?.lexicalContent ?? pack.variant.lexicalContent,
    [initialDraft?.lexicalContent, pack.variant.lexicalContent],
  );

  const initialTiptapDoc = useMemo(
    () => convertArtifactDocumentToTiptap(initialContent, sentences),
    [initialContent, sentences],
  );

  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const previousSaveRequestRef = useRef(saveRequestToken);
  const draftStateRef = useRef<Record<string, unknown>>(initialContent);
  const draftStatementsRef = useRef<ContentStatementInput[]>(
    initialDraft?.statements ?? adapterDefaultStatementsFor(sentences),
  );
  const generatedIdsRef = useRef<Map<string, string>>(new Map());

  const editor = useEditor({
    immediatelyRender: false,
    editable: !readOnly,
    content: initialTiptapDoc,
    extensions: [
      StarterKit.configure({
        heading: { levels: [1, 2, 3] },
      }),
      ArtifactIdentityExtension,
      TiptapCtaExtension,
      Placeholder.configure({
        placeholder: "Write the source-backed artifact…",
      }),
      Markdown.configure({
        // Model output and API JSON reach this editor unreviewed; raw HTML in
        // that stream must not become live markup. ProseMirror's schema plus
        // the link URI guard cover the markdown itself.
        html: false,
        tightLists: true,
      }),
      TiptapCitationExtension,
    ],
    editorProps: {
      attributes: {
        role: "textbox",
        "aria-label": readOnly ? "Historical artifact content" : "Draft content",
        "aria-readonly": readOnly ? "true" : "false",
        class: presentation === "document"
          ? "min-h-[720px] focus:outline-none text-[15px] leading-6 text-black/88 dark:text-white/88 prose prose-none max-w-none [&_h1]:mb-[22px] [&_h1]:font-display [&_h1]:text-[30px] [&_h1]:leading-[38px] [&_h2]:mb-[22px] [&_h2]:text-[19px] [&_h2]:font-semibold [&_h2]:leading-[26px] [&_li]:mb-1.5 [&_ol]:list-decimal [&_ol]:pl-5 [&_p]:mb-[22px] [&_ul]:list-disc [&_ul]:pl-5"
          : `min-h-[260px] rounded-lg border border-black/10 px-4 py-4 text-[15px] leading-7 text-black/84 focus:outline-none focus-within:border-black/35 dark:border-white/12 dark:text-white/86 dark:focus-within:border-white/35 prose prose-none max-w-none ${
              readOnly ? "bg-black/[0.025] dark:bg-white/[0.025]" : "bg-white dark:bg-[#18181b]"
            }`,
      },
    },
    onUpdate: ({ editor: currentEditor }) => {
      const json = currentEditor.getJSON();
      const statements = extractArtifactStatements(json, sentences, generatedIdsRef.current);
      const lexicalJson = tiptapToArtifactDocument(json, statements, generatedIdsRef.current);
      draftStateRef.current = lexicalJson;
      draftStatementsRef.current = statements;

      if (!readOnly) {
        onDraftChange?.({ lexicalContent: lexicalJson, statements });
        onSaveStateChange?.("dirty");
      }
    },
  });

  useEffect(() => {
    onSaveStateChange?.("saved");
  }, [onSaveStateChange, revisionKey]);

  const save = useCallback(async () => {
    if (saving || readOnly) return;
    setSaving(true);
    setMessage("");
    onSaveStateChange?.("saving");
    try {
      const updated = await onSaveArtifact({
        expectedRevisionNumber: revisionNumber,
        lexicalContent: draftStateRef.current,
        statements: draftStatementsRef.current,
      });
      onPackChange?.(updated);
      setMessage(
        `Saved ${new Intl.DateTimeFormat(undefined, {
          hour: "numeric",
          minute: "2-digit",
        }).format(new Date())}.`,
      );
      onSaveStateChange?.("saved");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "The draft could not be saved.");
      onSaveStateChange?.("error");
    } finally {
      setSaving(false);
    }
  }, [onPackChange, onSaveArtifact, onSaveStateChange, readOnly, revisionNumber, saving]);

  useEffect(() => {
    if (saveRequestToken === undefined || previousSaveRequestRef.current === saveRequestToken) return;
    previousSaveRequestRef.current = saveRequestToken;
    void save();
  }, [save, saveRequestToken]);

  const documentPresentation = presentation === "document";

  return (
    <section
      aria-label={readOnly ? "Historical artifact preview" : "Artifact editor"}
      className={embedded ? "min-w-0" : "rounded-xl border border-black/10 bg-white dark:border-white/10 dark:bg-white/[0.04]"}
    >
      {!documentPresentation ? (
        <div className="flex flex-wrap items-start justify-between gap-3 border-b border-black/[0.07] px-4 py-4 dark:border-white/10 sm:px-6">
          <div>
            <h2 className="text-sm font-semibold text-black/82 dark:text-white/88">
              {readOnly ? "Historical preview" : "Artifact document"}
            </h2>
            <p className="mt-1 text-xs text-black/50 dark:text-white/52">
              {readOnly
                ? "This snapshot is read-only. Editing and delivery are disabled."
                : "Edit the whole artifact. Sources stay outside the document and stay attached to it."}
            </p>
          </div>
          <SourcesPopover sources={pack.variant.sources} />
        </div>
      ) : null}

      <div className={documentPresentation ? "relative mt-[22px]" : "relative px-4 py-5 sm:px-6"}>
        {!readOnly ? <DocumentFormattingToolbar editor={editor} destinations={pack.variant.destinations ?? []} /> : null}
        <EditorContent editor={editor} />
      </div>

      {!documentPresentation ? (
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-black/[0.07] px-4 py-3 dark:border-white/10 sm:px-6">
          <p className="text-xs text-black/48 dark:text-white/50">
            {readOnly ? "Saved snapshot" : saving ? "Saving…" : message || "Saved"}
          </p>
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
      ) : null}

      {message ? (
        <p
          role="status"
          aria-live="polite"
          className={
            documentPresentation
              ? "sr-only"
              : "border-t border-black/[0.07] px-4 py-3 text-xs text-black/58 dark:border-white/10 dark:text-white/58 sm:px-6"
          }
        >
          {message}
        </p>
      ) : null}
    </section>
  );
}

function DocumentFormattingToolbar({
  editor,
  destinations,
}: {
  editor: Editor | null;
  destinations: Array<{ id: string; label: string; url: string }>;
}) {
  if (!editor) return null;
  return (
    <div
      aria-label="Document formatting"
      className="mb-3 flex flex-wrap items-center gap-1.5 border-b border-black/[0.07] pb-2.5 dark:border-white/10"
    >
      <ToolbarButton
        label="Heading"
        active={editor.isActive("heading")}
        onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
      />
      <ToolbarButton
        label="Bulleted list"
        active={editor.isActive("bulletList")}
        onClick={() => editor.chain().focus().toggleBulletList().run()}
      />
      <ToolbarButton
        label="Numbered list"
        active={editor.isActive("orderedList")}
        onClick={() => editor.chain().focus().toggleOrderedList().run()}
      />
      <ToolbarButton
        label="Move block up"
        disabled={topLevelBlockIndex(editor) <= 0}
        onClick={() => moveSelectedBlock(editor, -1)}
      />
      <ToolbarButton
        label="Move block down"
        disabled={topLevelBlockIndex(editor) >= editor.state.doc.childCount - 1}
        onClick={() => moveSelectedBlock(editor, 1)}
      />
      {destinations.map((destination) => (
        <ToolbarButton
          key={destination.id}
          label={`Insert CTA: ${destination.label}`}
          onClick={() => insertCta(editor, destination)}
        />
      ))}
    </div>
  );
}

function ToolbarButton({
  label,
  active = false,
  disabled = false,
  onClick,
}: {
  label: string;
  active?: boolean;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={active}
      disabled={disabled}
      onMouseDown={(event) => event.preventDefault()}
      onClick={onClick}
      className="min-h-8 rounded-md border border-black/10 px-2.5 text-xs font-medium text-black/65 transition hover:bg-black/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 aria-pressed:bg-black/[0.08] dark:border-white/12 dark:text-white/68 dark:hover:bg-white/[0.06] dark:aria-pressed:bg-white/[0.1]"
    >
      {label}
    </button>
  );
}

function topLevelBlockIndex(editor: Editor): number {
  return editor.state.selection.$from.index(0);
}

function moveSelectedBlock(editor: Editor, direction: -1 | 1): void {
  const index = topLevelBlockIndex(editor);
  const targetIndex = index + direction;
  const nodes = editor.state.doc.content.content.slice();
  if (index < 0 || targetIndex < 0 || targetIndex >= nodes.length) return;
  [nodes[index], nodes[targetIndex]] = [nodes[targetIndex], nodes[index]];
  const reordered = editor.state.schema.topNodeType.create(null, nodes);
  editor
    .chain()
    .focus()
    .command(({ tr }) => {
      tr.replaceWith(0, editor.state.doc.content.size, reordered.content);
      return true;
    })
    .run();
}

function insertCta(editor: Editor, destination: { id: string; label: string; url: string }) {
  if (!isSafeCtaUrl(destination.url)) return;
  editor
    .chain()
    .focus()
    .insertContent({
      type: "cta",
      attrs: {
        destinationId: destination.id,
        destinationLabel: destination.label,
        destinationUrl: destination.url,
      },
      content: [{ type: "text", text: destination.label }],
    })
    .run();
}
