"use client";

import { useEditor, useEditorState, EditorContent } from "@tiptap/react";
import { Extension, Node as TiptapNode, isNodeSelection, mergeAttributes, type Editor } from "@tiptap/core";
import { BubbleMenu } from "@tiptap/react/menus";
import StarterKit from "@tiptap/starter-kit";
import Placeholder from "@tiptap/extension-placeholder";
import { Markdown } from "tiptap-markdown";
import { ArrowDown, ArrowUp, Bold, Code2, Heading2, Italic, List, ListOrdered, MoreHorizontal, Redo2, Save, Strikethrough, Undo2, type LucideIcon } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import type {
  Artifact,
  ContentStatementInput,
} from "@plot/api-client";
import { SourcesPopover } from "./sources-popover";
import { ArtifactBlockControls, moveBlockAtIndex, topLevelBlockIndex } from "./artifact-block-controls";
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
  title?: string;
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
  const editorWrapperRef = useRef<HTMLDivElement>(null);

  const editor = useEditor({
    immediatelyRender: false,
    editable: !readOnly,
    content: initialTiptapDoc,
    extensions: [
      StarterKit.configure({
        heading: { levels: [1, 2, 3] },
        blockquote: false,
        codeBlock: false,
        horizontalRule: false,
        link: false,
        underline: false,
      }),
      ArtifactIdentityExtension,
      TiptapCtaExtension,
      Placeholder.configure({
        placeholder: "Type / for blocks and formatting…",
        showOnlyCurrent: true,
        includeChildren: true,
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
        "aria-label": readOnly ? "Historical content" : "Draft content",
        "aria-readonly": readOnly ? "true" : "false",
        ...(!readOnly ? { "aria-keyshortcuts": "Alt+F10" } : {}),
        class: presentation === "document"
          ? "artifact-rich-editor focus:outline-none text-[17px] leading-7 text-black/88 dark:text-white/88 prose prose-none max-w-none [&_h1]:mb-[22px] [&_h1]:text-[30px] [&_h1]:font-semibold [&_h1]:leading-[38px] [&_h2]:mb-[22px] [&_h2]:text-[22px] [&_h2]:font-semibold [&_h2]:leading-[30px] [&_h3]:mb-[18px] [&_h3]:text-[19px] [&_h3]:font-semibold [&_li]:mb-1.5 [&_ol]:list-decimal [&_ol]:pl-5 [&_p]:mb-[22px] [&_ul]:list-disc [&_ul]:pl-5"
          : `artifact-rich-editor min-h-[260px] rounded-lg border border-black/10 px-4 py-4 text-[15px] leading-7 text-black/84 focus:outline-none focus-within:border-black/35 dark:border-white/12 dark:text-white/86 dark:focus-within:border-white/35 prose prose-none max-w-none ${
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
    onSaveStateChange?.(initialDraft ? "dirty" : "saved");
  }, [initialDraft, onSaveStateChange, revisionKey]);

  const save = useCallback(async () => {
    if (saving || readOnly) return;
    setSaving(true);
    setMessage("");
    onSaveStateChange?.("saving");
    try {
      const updated = await onSaveArtifact({
        expectedRevisionNumber: revisionNumber,
        title: pack.title ?? "Generated content",
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
  }, [onPackChange, onSaveArtifact, onSaveStateChange, pack.title, readOnly, revisionNumber, saving]);

  useEffect(() => {
    if (saveRequestToken === undefined || previousSaveRequestRef.current === saveRequestToken) return;
    previousSaveRequestRef.current = saveRequestToken;
    void save();
  }, [save, saveRequestToken]);

  const documentPresentation = presentation === "document";

  return (
    <section
      aria-label={readOnly ? "Historical content preview" : "Content editor"}
      className={`glass-card ${embedded ? "min-w-0" : "rounded-xl border border-black/10 dark:border-white/10"}`}
    >
      {!documentPresentation ? (
        <div className="flex flex-wrap items-start justify-between gap-3 border-b border-black/[0.07] px-4 py-4 dark:border-white/10 sm:px-6">
          <div>
            <h2 className="text-sm font-semibold text-black/82 dark:text-white/88">
              {readOnly ? "Historical preview" : "Content"}
            </h2>
            <p className="mt-1 text-xs text-black/50 dark:text-white/52">
              {readOnly
                ? "This snapshot is read-only. Editing and delivery are disabled."
                : "Edit this content. Sources stay outside the document and stay attached to it."}
            </p>
          </div>
          <SourcesPopover sources={pack.variant.sources} />
        </div>
      ) : null}

      <div ref={editorWrapperRef} className={documentPresentation ? "group/editor relative mt-[22px]" : "relative px-4 py-5 sm:px-6"}>
        {!readOnly ? <DocumentFormattingToolbar editor={editor} destinations={pack.variant.destinations ?? []} showCaretActions={!documentPresentation} /> : null}
        {!readOnly && editor ? <ArtifactBlockControls editor={editor} wrapperRef={editorWrapperRef} showGutter={documentPresentation} /> : null}
        <EditorContent editor={editor} />
      </div>

      {!documentPresentation ? (
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-black/[0.07] px-4 py-3 dark:border-white/10 sm:px-6">
          <p className="text-xs text-black/48 dark:text-white/50">
            {readOnly ? "Saved snapshot" : saving ? "Saving…" : message || "Saved"}
          </p>
          {!readOnly ? (
            <button aria-busy={Boolean(saving)}
              type="button"
              disabled={saving}
              onClick={() => void save()}
              className="glass-button glass-primary inline-flex items-center gap-2"
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
  showCaretActions,
}: {
  editor: Editor | null;
  destinations: Array<{ id: string; label: string; url: string }>;
  showCaretActions: boolean;
}) {
  if (!editor) return null;
  return <ContextualFormattingToolbar editor={editor} destinations={destinations} showCaretActions={showCaretActions} />;
}

function ContextualFormattingToolbar({
  editor,
  destinations,
  showCaretActions,
}: {
  editor: Editor;
  destinations: Array<{ id: string; label: string; url: string }>;
  showCaretActions: boolean;
}) {
  const menuRef = useRef<HTMLDivElement>(null);
  const dismissedRef = useRef<Editor["state"]["selection"] | null>(null);
  const [expanded, setExpanded] = useState(false);
  const state = useEditorState({
    editor,
    selector: ({ editor: currentEditor }) => ({
      empty: currentEditor.state.selection.empty,
      heading: currentEditor.isActive("heading"),
      bold: currentEditor.isActive("bold"),
      italic: currentEditor.isActive("italic"),
      strike: currentEditor.isActive("strike"),
      code: currentEditor.isActive("code"),
      bulletList: currentEditor.isActive("bulletList"),
      orderedList: currentEditor.isActive("orderedList"),
      canUndo: currentEditor.can().undo(),
      canRedo: currentEditor.can().redo(),
      blockIndex: topLevelBlockIndex(currentEditor),
      blockCount: currentEditor.state.doc.childCount,
    }),
  });

  useEffect(() => {
    const reset = () => {
      dismissedRef.current = null;
      setExpanded(false);
    };
    const hide = () => {
      if (!editor.isDestroyed) {
        editor.view.dispatch(editor.state.tr.setMeta("documentFormatting", "hide"));
      }
      setExpanded(false);
    };
    const outside = (event: Event) => {
      const target = event.target;
      if (target instanceof Node && !editor.view.dom.contains(target) && !menuRef.current?.contains(target)) hide();
    };
    const reposition = () => {
      if (!editor.isDestroyed && menuRef.current?.isConnected) {
        editor.view.dispatch(editor.state.tr.setMeta("documentFormatting", "updatePosition"));
      }
    };
    const keyboard = (event: KeyboardEvent) => {
      if (event.key === "Escape" && menuRef.current?.isConnected) {
        event.preventDefault();
        event.stopPropagation();
        dismissedRef.current = editor.state.selection;
        editor.commands.focus();
        hide();
        return;
      }
      if (event.altKey && event.key === "F10") {
        event.preventDefault();
        dismissedRef.current = null;
        setExpanded(true);
        editor.view.dispatch(editor.state.tr.setMeta("documentFormatting", "show"));
        menuRef.current?.querySelector<HTMLButtonElement>("button:not(:disabled)")?.focus();
      }
    };
    const root = editor.view.dom;
    const menu = menuRef.current;
    menu?.addEventListener("keydown", keyboard);
    editor.on("selectionUpdate", reset);
    root.addEventListener("keydown", keyboard);
    document.addEventListener("pointerdown", outside, true);
    document.addEventListener("focusin", outside);
    // Both the Chat panel and Contents page have their own scroll containers.
    document.addEventListener("scroll", reposition, true);
    return () => {
      editor.off("selectionUpdate", reset);
      root.removeEventListener("keydown", keyboard);
      menu?.removeEventListener("keydown", keyboard);
      document.removeEventListener("pointerdown", outside, true);
      document.removeEventListener("focusin", outside);
      document.removeEventListener("scroll", reposition, true);
    };
  }, [editor]);

  useEffect(() => {
    editor.view.dispatch(editor.state.tr.setMeta("documentFormatting", "updatePosition"));
  }, [editor, expanded, state.empty]);

  return (
    <BubbleMenu
      editor={editor}
      pluginKey="documentFormatting"
      ref={menuRef}
      updateDelay={0}
      options={{
        placement: state.empty && !expanded ? "left-start" : "top-start",
        offset: 8,
        shift: { padding: 8 },
        flip: { padding: 8 },
        hide: true,
      }}
      getReferencedVirtualElement={() => {
        if (!editor.state.selection.empty) return null;
        const { $from } = editor.state.selection;
        const block = editor.view.nodeDOM($from.depth ? $from.before(1) : $from.pos);
        return block instanceof HTMLElement ? {
          contextElement: block,
          getBoundingClientRect: () => {
            const rect = block.getBoundingClientRect();
            return new DOMRect(rect.left, rect.top, rect.width, Math.min(rect.height, 24));
          },
        } : null;
      }}
      shouldShow={({ editor: currentEditor, element }) =>
        !dismissedRef.current?.eq(currentEditor.state.selection) && currentEditor.isEditable &&
        !(isNodeSelection(currentEditor.state.selection) && currentEditor.state.selection.node.type.name === "citation") &&
        !currentEditor.view.dom.querySelector('[role="dialog"][aria-label="Citation details"]') &&
        (showCaretActions || !currentEditor.state.selection.empty || expanded) &&
        (currentEditor.isFocused || element.contains(document.activeElement))
      }
      role="group"
      aria-label="Document formatting"
      className="glass-layer z-30 max-w-[calc(100vw-16px)] rounded-lg border border-black/10 p-1 dark:border-white/15"
    >
      <div className="flex items-center gap-0.5">
        {!state.empty || expanded ? (
          <>
            <ToolbarButton label="Bold" icon={Bold} active={state.bold}
              onClick={() => editor.chain().focus().toggleBold().run()} />
            <ToolbarButton label="Italic" icon={Italic} active={state.italic}
              onClick={() => editor.chain().focus().toggleItalic().run()} />
            <ToolbarButton label="Strikethrough" icon={Strikethrough} active={state.strike}
              onClick={() => editor.chain().focus().toggleStrike().run()} />
            <ToolbarButton label="Inline code" icon={Code2} active={state.code}
              onClick={() => editor.chain().focus().toggleCode().run()} />
            <span aria-hidden="true" className="mx-0.5 h-5 w-px bg-black/10 dark:bg-white/10" />
            <ToolbarButton label="Heading" icon={Heading2} active={state.heading}
              onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()} />
            <ToolbarButton label="Bulleted list" icon={List} active={state.bulletList}
              onClick={() => editor.chain().focus().toggleBulletList().run()} />
            <ToolbarButton label="Numbered list" icon={ListOrdered} active={state.orderedList}
              onClick={() => editor.chain().focus().toggleOrderedList().run()} />
          </>
        ) : null}
        <button
          type="button"
          aria-label="Block actions"
          aria-expanded={expanded}
          aria-keyshortcuts="Alt+F10"
          title="Block actions (Alt+F10)"
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => setExpanded((value) => !value)}
          className="glass-button glass-icon inline-flex size-8 items-center justify-center"
        >
          <MoreHorizontal aria-hidden="true" className="size-4" />
        </button>
      </div>
      {expanded ? (
        <div className="mt-1 flex min-w-44 flex-col gap-0.5 border-t border-black/10 pt-1 dark:border-white/10">
          <ToolbarButton label="Move block up" icon={ArrowUp} showLabel
            disabled={state.blockIndex <= 0} onClick={() => moveBlockAtIndex(editor, topLevelBlockIndex(editor), -1)} />
          <ToolbarButton label="Move block down" icon={ArrowDown} showLabel
            disabled={state.blockIndex >= state.blockCount - 1} onClick={() => moveBlockAtIndex(editor, topLevelBlockIndex(editor), 1)} />
          <ToolbarButton label="Undo" icon={Undo2} showLabel disabled={!state.canUndo}
            onClick={() => editor.chain().focus().undo().run()} />
          <ToolbarButton label="Redo" icon={Redo2} showLabel disabled={!state.canRedo}
            onClick={() => editor.chain().focus().redo().run()} />
          {destinations.map((destination) => (
            <ToolbarButton key={destination.id} label={`Insert CTA: ${destination.label}`} showLabel
              onClick={() => insertCta(editor, destination)} />
          ))}
        </div>
      ) : null}
    </BubbleMenu>
  );
}

function ToolbarButton({
  label,
  icon: Icon,
  showLabel = false,
  active,
  disabled = false,
  onClick,
}: {
  label: string;
  icon?: LucideIcon;
  showLabel?: boolean;
  active?: boolean;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      aria-pressed={active}
      disabled={disabled}
      onMouseDown={(event) => event.preventDefault()}
      onClick={onClick}
      className={`glass-control inline-flex min-h-8 items-center gap-2 rounded-md text-xs font-medium ${showLabel ? "px-2 text-left" : "size-8 justify-center"}`}
    >
      {Icon ? <Icon aria-hidden="true" className="size-4 shrink-0" /> : null}
      {showLabel ? label : null}
    </button>
  );
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
