import type { JSONContent } from "@tiptap/react";

import type { ContentSentence, ContentStatementInput } from "@plot/api-client";

export type ArtifactDocumentVersion = 1 | 2;

export const V2_DOCUMENT_VERSION = 2 as const;

type JsonRecord = Record<string, unknown>;

export function artifactDocumentVersion(content: JsonRecord | undefined): ArtifactDocumentVersion {
  return content?.documentVersion === V2_DOCUMENT_VERSION ? V2_DOCUMENT_VERSION : 1;
}

export function convertArtifactDocumentToTiptap(
  content: JsonRecord | undefined,
  sentences: ContentSentence[],
): JSONContent {
  if (!content) return emptyDocument();
  if (content.type === "doc" && Array.isArray(content.content)) return content as JSONContent;

  const root = isRecord(content.root) ? content.root : content;
  const children = arrayOfRecords(root.children);
  if (!children.length) return emptyDocument();
  const version = artifactDocumentVersion(content);
  const orderedSentences = [...sentences].sort((a, b) => a.orderIndex - b.orderIndex);
  const sentenceById = new Map(orderedSentences.map((sentence) => [sentence.id, sentence]));
  const sentenceByIndex = orderedSentences;
  let statementIndex = 0;

  const convertNode = (node: JsonRecord, path: string): JSONContent => {
    const type = typeof node.type === "string" ? node.type : "paragraph";
    if (type === "list") {
      const listType = node.listType === "ordered" ? "orderedList" : "bulletList";
		return {
			type: listType,
			attrs: {
				nodeId: stringValue(node.nodeId),
				...(listType === "orderedList" && typeof node.start === "number" ? { start: node.start } : {}),
			},
			content: arrayOfRecords(node.children).map((item, index) => convertNode(item, `${path}.${index}`)),
		};
    }

    const statementId = stringValue(node.statementId);
    const sentence = (statementId ? sentenceById.get(statementId) : undefined) ?? sentenceByIndex[statementIndex];
    const blockIndex = statementIndex;
    statementIndex += 1;
    const inline = lexicalChildrenToTiptap(node.children);
    appendCitation(inline, sentence, blockIndex);

    if (type === "heading") {
      const tag = node.tag === "h1" ? 1 : node.tag === "h3" ? 3 : 2;
      return {
        type: "heading",
        attrs: { level: tag, nodeId: stringValue(node.nodeId), statementId },
        content: inline.length ? inline : undefined,
      };
    }
    if (type === "cta") {
      return {
        type: "cta",
        attrs: {
          nodeId: stringValue(node.nodeId),
          statementId,
          destinationId: stringValue(node.destinationId),
          destinationLabel: stringValue(node.destinationLabel),
          destinationUrl: stringValue(node.destinationUrl),
        },
        content: inline.length ? inline : undefined,
      };
    }
    if (type === "listItem") {
      return {
        type: "listItem",
        attrs: { nodeId: stringValue(node.nodeId), statementId },
        content: [{ type: "paragraph", content: inline.length ? inline : undefined }],
      };
    }
    return {
      type: "paragraph",
      attrs: version === 2
        ? { nodeId: stringValue(node.nodeId), statementId }
        : sentence?.id
          ? { sourceStatementId: sentence.id }
          : undefined,
      content: inline.length ? inline : undefined,
    };
  };

  return {
    type: "doc",
    content: children.map((node, index) => convertNode(node, String(index))),
  };
}

export function extractArtifactStatements(
  doc: JSONContent,
  originalSentences: ContentSentence[],
  generatedIds: Map<string, string> = new Map(),
): ContentStatementInput[] {
  const statements: ContentStatementInput[] = [];
  const orderedOriginal = [...originalSentences].sort((a, b) => a.orderIndex - b.orderIndex);
  const structural = (doc.content ?? []).some((node) => isStructuralNode(node));
  const usedIds = new Set<string>();
  const firstCandidateIndex = new Map<string, number>();
  let statementIndex = 0;

  const visit = (node: JSONContent, path: string) => {
    if (node.type === "bulletList" || node.type === "orderedList") {
      (node.content ?? []).forEach((child, index) => visit(child, `${path}.${index}`));
      return;
    }
    if (node.type === "listItem") {
      const paragraph = node.content?.find((child) => child.type === "paragraph");
      if (paragraph) {
        const paragraphAttrs = (paragraph.attrs ?? {}) as JsonRecord;
        const listItemAttrs = (node.attrs ?? {}) as JsonRecord;
        addStatement(
          paragraph,
          { ...paragraph, attrs: { ...paragraphAttrs, ...listItemAttrs } },
          path,
          structural,
        );
      }
      return;
    }
    if (!["paragraph", "heading", "cta"].includes(node.type ?? "")) return;
    addStatement(node, node, path, structural);
  };

  const addStatement = (node: JSONContent, identityNode: JSONContent, path: string, isStructural: boolean) => {
    const body = extractTextFromBlock(node).trim();
    if (!body) return;
    const original = orderedOriginal[statementIndex];
    const attrs = (identityNode.attrs ?? {}) as JsonRecord;
    // Preserve the server statement identity when a V1 paragraph is promoted
    // to a supported structural node (heading/list/CTA). A node without an
    // explicit identity is still matched to the original preorder statement;
    // only genuinely new content receives a generated identity.
    const candidate = stringValue(attrs.statementId)
      ?? stringValue(attrs.sourceStatementId)
      ?? (!isStructural ? original?.id : undefined);
    const reusedCandidate = Boolean(candidate && usedIds.has(candidate));
    const id = candidate && !reusedCandidate
      ? candidate
      : generatedId(generatedIds, `${path}:statement`);
    usedIds.add(id);
    const inheritedLineage = Array.isArray(attrs.lineage)
      ? attrs.lineage.filter((value): value is string => typeof value === "string")
      : [];
    // ProseMirror copies node attributes when Enter splits a block and when a
    // block is pasted. A repeated source identity therefore represents a new
    // statement. Keep its ancestry explicit so the server can carry evidence
    // forward as STALE instead of accidentally treating the clone as ACTIVE.
    const lineage = reusedCandidate && candidate && !inheritedLineage.includes(candidate)
      ? [candidate, ...inheritedLineage]
      : inheritedLineage;
    statements.push({ id, orderIndex: statementIndex, body, ...(lineage.length ? { lineage } : {}) });
    if (candidate) {
      const firstIndex = firstCandidateIndex.get(candidate);
      if (firstIndex === undefined) {
        firstCandidateIndex.set(candidate, statements.length - 1);
      } else {
        const first = statements[firstIndex];
        const firstLineage = first.lineage ?? [];
        if (!firstLineage.includes(candidate)) {
          statements[firstIndex] = { ...first, lineage: [candidate, ...firstLineage] };
        }
      }
    }
    statementIndex += 1;
  };

  (doc.content ?? []).forEach((node, index) => visit(node, String(index)));
  return statements;
}

export function defaultStatementsFor(sentences: ContentSentence[]): ContentStatementInput[] {
  return [...sentences]
    .sort((a, b) => a.orderIndex - b.orderIndex)
    .map((sentence) => ({ id: sentence.id, orderIndex: sentence.orderIndex, body: sentence.body }));
}

export function tiptapToArtifactDocument(
  doc: JSONContent,
  statements: ContentStatementInput[] = [],
  generatedIds: Map<string, string> = new Map(),
): Record<string, unknown> {
  const blocks = doc.content ?? [];
  const structural = blocks.some((node) => isStructuralNode(node)) || blocks.some((node) => hasIdentity(node));
  if (!structural) return v1Document(blocks);

  let statementIndex = 0;
  const usedNodeIds = new Set<string>();
  const toNode = (node: JSONContent, path: string): JsonRecord | null => {
    if (node.type === "bulletList" || node.type === "orderedList") {
      const nodeId = uniqueNodeId(nodeIdFor(node, generatedIds, `${path}:node`), usedNodeIds, generatedIds, `${path}:node:unique`);
      const children = (node.content ?? []).map((child, index) => toNode(child, `${path}.${index}`)).filter(isRecord);
      if (!children.length) return null;
      return {
        children,
        nodeId,
        listType: node.type === "orderedList" ? "ordered" : "bullet",
        start: typeof node.attrs?.start === "number" ? node.attrs.start : 1,
        type: "list",
        version: 1,
      };
    }
    if (node.type === "listItem") {
      const paragraph = node.content?.find((child) => child.type === "paragraph");
      if (!paragraph) return null;
      return statementNode(paragraph, node, "listItem", path, generatedIds, statements, () => statementIndex++, usedNodeIds);
    }
    if (!["paragraph", "heading", "cta"].includes(node.type ?? "")) return null;
    const type = node.type === "heading" ? "heading" : node.type === "cta" ? "cta" : "paragraph";
    return statementNode(node, node, type, path, generatedIds, statements, () => statementIndex++, usedNodeIds);
  };

  const children = blocks.map((node, index) => toNode(node, String(index))).filter(isRecord);
  return {
    documentVersion: V2_DOCUMENT_VERSION,
    root: {
      children,
      direction: null,
      format: "",
      indent: 0,
      type: "root",
      version: 1,
    },
  };
}

function statementNode(
  node: JSONContent,
  identityNode: JSONContent,
  type: string,
  path: string,
  generatedIds: Map<string, string>,
  statements: ContentStatementInput[],
  nextStatementIndex: () => number,
  usedNodeIds: Set<string>,
): JsonRecord {
  const index = nextStatementIndex();
  const attrs = (identityNode.attrs ?? {}) as JsonRecord;
  // `extractArtifactStatements` is the identity authority for a draft. It
  // has already replaced duplicated IDs from split/paste operations, so use
  // that normalized value before falling back to the node attribute.
  const statementId = statements[index]?.id ?? stringValue(attrs.statementId) ?? generatedId(generatedIds, `${path}:statement`);
  const nodeId = uniqueNodeId(
    stringValue(attrs.nodeId) ?? generatedId(generatedIds, `${path}:node`),
    usedNodeIds,
    generatedIds,
    `${path}:node:unique`,
  );
  const children = tiptapInlineToLexical(node.content);
  const result: JsonRecord = {
    children,
    direction: null,
    format: "",
    indent: 0,
    nodeId,
    statementId,
    type,
    version: 1,
  };
  if (type === "heading") {
    const level = node.attrs?.level === 1 ? "h1" : node.attrs?.level === 3 ? "h3" : "h2";
    result.tag = level;
  }
  if (type === "cta") {
    result.destinationId = stringValue(attrs.destinationId) ?? "";
    result.destinationLabel = stringValue(attrs.destinationLabel) ?? extractTextFromBlock(node).trim();
    result.destinationUrl = stringValue(attrs.destinationUrl) ?? "";
  }
  return result;
}

function v1Document(blocks: JSONContent[]): Record<string, unknown> {
  return {
    root: {
      children: blocks.map((block) => ({
        children: tiptapInlineToLexical(block.content),
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

function lexicalChildrenToTiptap(value: unknown): JSONContent[] {
  return arrayOfRecords(value).flatMap((child) => {
    if (child.type === "text" && typeof child.text === "string") return [{ type: "text", text: child.text }];
    if (child.type === "linebreak") return [{ type: "hardBreak" }];
    return [];
  });
}

function tiptapInlineToLexical(content: JSONContent[] | undefined): JsonRecord[] {
  const children = (content ?? []).flatMap((child) => {
    if (child.type === "text" && child.text) return [lexicalText(child.text)];
    if (child.type === "hardBreak") return [{ type: "linebreak", version: 1 }];
    return [];
  });
  return children.length ? children : [lexicalText("")];
}

function lexicalText(text: string): JsonRecord {
  return { detail: 0, format: 0, mode: "normal", style: "", text, type: "text", version: 1 };
}

function appendCitation(content: JSONContent[], sentence: ContentSentence | undefined, number: number) {
  if (!sentence?.citations?.length) return;
  content.push({
    type: "citation",
    attrs: {
      statementId: sentence.id,
      number: number + 1,
      sources: sentence.citations.map((citation) => ({
        title: citation.sourceLabel || "Source",
        url: citation.provider === "USER_CONFIRMED" ? "" : citation.originalUrl || "",
        provider: citation.provider || "GITHUB",
        status: citation.status,
      })),
    },
  });
}

function extractTextFromBlock(node: JSONContent): string {
  return (node.content ?? []).map((child) => {
    if (child.type === "text") return child.text || "";
    if (child.type === "hardBreak") return "\n";
    return "";
  }).join("");
}

function isStructuralNode(node: JSONContent): boolean {
  return ["heading", "bulletList", "orderedList", "cta", "listItem"].includes(node.type ?? "") || hasIdentity(node);
}

function hasIdentity(node: JSONContent): boolean {
  const attrs = node.attrs as JsonRecord | undefined;
  return typeof attrs?.nodeId === "string" || typeof attrs?.statementId === "string";
}

function nodeIdFor(node: JSONContent, generatedIds: Map<string, string>, key: string): string {
  return stringValue((node.attrs as JsonRecord | undefined)?.nodeId) ?? generatedId(generatedIds, key);
}

function uniqueNodeId(
  candidate: string,
  usedNodeIds: Set<string>,
  generatedIds: Map<string, string>,
  key: string,
): string {
  if (!usedNodeIds.has(candidate)) {
    usedNodeIds.add(candidate);
    return candidate;
  }
  const replacement = generatedId(generatedIds, key);
  if (usedNodeIds.has(replacement)) return uniqueNodeId(replacement, usedNodeIds, generatedIds, `${key}:retry`);
  usedNodeIds.add(replacement);
  return replacement;
}

function generatedId(cache: Map<string, string>, key: string): string {
  const existing = cache.get(key);
  if (existing) return existing;
  const value = typeof globalThis.crypto?.randomUUID === "function"
    ? globalThis.crypto.randomUUID()
    : `00000000-0000-4000-8000-${Math.random().toString(16).slice(2).padStart(12, "0").slice(-12)}`;
  cache.set(key, value);
  return value;
}

function arrayOfRecords(value: unknown): JsonRecord[] {
  return Array.isArray(value) ? value.filter(isRecord) : [];
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stringValue(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value : null;
}

function emptyDocument(): JSONContent {
  return { type: "doc", content: [{ type: "paragraph" }] };
}
