"use client";

import { useEditor, EditorContent, type JSONContent } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import Placeholder from "@tiptap/extension-placeholder";
import { Markdown } from "tiptap-markdown";
import { Save } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import type {
  Artifact,
  ContentSentence,
  ContentStatementInput,
} from "@plot/api-client";
import { SourcesPopover } from "./sources-popover";
import { TiptapCitationExtension, type CitationSourceItem } from "./tiptap-citation-extension";

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
    () => convertToTiptapDoc(initialContent, sentences),
    [initialContent, sentences],
  );

  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const previousSaveRequestRef = useRef(saveRequestToken);
  const draftStateRef = useRef<Record<string, unknown>>(initialContent);
  const draftStatementsRef = useRef<ContentStatementInput[]>(
    initialDraft?.statements ?? defaultStatementsFor(sentences),
  );

  const editor = useEditor({
    immediatelyRender: false,
    editable: !readOnly,
    content: initialTiptapDoc,
    extensions: [
      StarterKit.configure({
        heading: { levels: [1, 2, 3] },
      }),
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
      const lexicalJson = tiptapToLexicalJson(json);
      draftStateRef.current = lexicalJson;

      // Extract statement inputs from block nodes
      const statements = extractStatementsFromTiptap(json, sentences);
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

// Converts Lexical or Tiptap JSON to a Tiptap Document with Inline Citation Nodes
function convertToTiptapDoc(
  content: Record<string, unknown> | undefined,
  sentences: ContentSentence[],
): JSONContent {
  if (!content) {
    return {
      type: "doc",
      content: [{ type: "paragraph" }],
    };
  }

  // If already in Tiptap format
  if (content.type === "doc" && Array.isArray(content.content)) {
    return content as JSONContent;
  }

  // Convert Lexical AST to Tiptap JSON
  const root = (content.root as Record<string, unknown>) || content;
  const children = Array.isArray(root.children) ? (root.children as Record<string, unknown>[]) : [];

  if (!children.length) {
    return {
      type: "doc",
      content: [{ type: "paragraph" }],
    };
  }

  const tiptapContent: JSONContent[] = children.map((lexicalNode, blockIndex) => {
    const nodeType = (lexicalNode.type as string) || "paragraph";
    const lexicalChildren = Array.isArray(lexicalNode.children)
      ? (lexicalNode.children as Record<string, unknown>[])
      : [];

    const paragraphContent: JSONContent[] = [];

    // Extract text nodes
    for (const child of lexicalChildren) {
      if (child.type === "text" && typeof child.text === "string" && child.text) {
        paragraphContent.push({
          type: "text",
          text: child.text,
        });
      } else if (child.type === "linebreak") {
        paragraphContent.push({
          type: "hardBreak",
        });
      }
    }

    // Attach inline citation if this sentence block has citations
    const matchedSentence = sentences[blockIndex] || sentences.find((s) => s.orderIndex === blockIndex);
    if (matchedSentence && matchedSentence.citations && matchedSentence.citations.length > 0) {
      const citationSources: CitationSourceItem[] = matchedSentence.citations.map((c) => ({
        title: c.sourceLabel || "Source",
        url: c.originalUrl || "#",
        provider: c.provider || "GitHub",
      }));

      paragraphContent.push({
        type: "citation",
        attrs: {
          statementId: matchedSentence.id,
          number: blockIndex + 1,
          sources: citationSources,
        },
      });
    }

    if (nodeType === "heading") {
      const tag = (lexicalNode.tag as string) || "h2";
      const level = tag === "h1" ? 1 : tag === "h3" ? 3 : 2;
      return {
        type: "heading",
        attrs: { level },
        content: paragraphContent.length ? paragraphContent : undefined,
      };
    }

    return {
      type: "paragraph",
      content: paragraphContent.length ? paragraphContent : undefined,
    };
  });

  return {
    type: "doc",
    content: tiptapContent.length ? tiptapContent : [{ type: "paragraph" }],
  };
}

function extractStatementsFromTiptap(
  doc: JSONContent,
  originalSentences: ContentSentence[],
): ContentStatementInput[] {
  const content = doc.content || [];
  const statements: ContentStatementInput[] = [];

  content.forEach((block, index) => {
    const text = extractTextFromBlock(block);
    if (!text.trim()) return;

    const matchedSentence = originalSentences[index];
    statements.push({
      id: matchedSentence?.id,
      orderIndex: index,
      body: text.trim(),
    });
  });

  return statements.length
    ? statements
    : [{ id: null, orderIndex: 0, body: "Generated artifact" }];
}

function extractTextFromBlock(node: JSONContent): string {
  if (!node.content) return "";
  return node.content
    .map((child) => {
      if (child.type === "text") return child.text || "";
      if (child.type === "hardBreak") return "\n";
      return "";
    })
    .join("");
}

function defaultStatementsFor(sentences: ContentSentence[]): ContentStatementInput[] {
  return sentences.map((sentence) => ({
    id: sentence.id,
    orderIndex: sentence.orderIndex,
    body: sentence.body,
  }));
}

function tiptapToLexicalJson(doc: JSONContent): Record<string, unknown> {
  const content = doc.content || [];
  return {
    root: {
      children: content.map((block) => {
        const paragraphChildren: Record<string, unknown>[] = [];
        if (block.content) {
          for (const child of block.content) {
            if (child.type === "text" && child.text) {
              paragraphChildren.push({
                detail: 0,
                format: 0,
                mode: "normal",
                style: "",
                text: child.text,
                type: "text",
                version: 1,
              });
            } else if (child.type === "hardBreak") {
              paragraphChildren.push({
                type: "linebreak",
                version: 1,
              });
            }
          }
        }
        if (!paragraphChildren.length) {
          paragraphChildren.push({
            detail: 0,
            format: 0,
            mode: "normal",
            style: "",
            text: "",
            type: "text",
            version: 1,
          });
        }
        return {
          children: paragraphChildren,
          direction: null,
          format: "",
          indent: 0,
          type: "paragraph",
          version: 1,
        };
      }),
      direction: null,
      format: "",
      indent: 0,
      type: "root",
      version: 1,
    },
  };
}
