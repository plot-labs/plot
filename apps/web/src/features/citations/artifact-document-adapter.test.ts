import { describe, expect, it } from "vitest";

import type { ContentSentence } from "@plot/api-client";

import {
	convertArtifactDocumentToTiptap,
	extractArtifactStatements,
	tiptapToArtifactDocument,
} from "./artifact-document-adapter";

const sentences: ContentSentence[] = [
	{ id: "statement-heading", revisionId: "revision-heading", revisionNumber: 1, orderIndex: 0, body: "Release notes", origin: "GENERATED", citations: [] },
	{ id: "statement-item", revisionId: "revision-item", revisionNumber: 1, orderIndex: 1, body: "Search is faster.", origin: "GENERATED", citations: [] },
	{ id: "statement-cta", revisionId: "revision-cta", revisionNumber: 1, orderIndex: 2, body: "Join the beta", origin: "GENERATED", citations: [] },
];

describe("artifact document adapter", () => {
	it("round-trips V2 heading, list item, CTA and statement identity", () => {
		const document = {
			documentVersion: 2,
			root: {
				type: "root",
				version: 1,
				children: [
					block("heading", "node-heading", "statement-heading", "Release notes", { tag: "h1" }),
					{
						type: "list",
						nodeId: "node-list",
						listType: "bullet",
						start: 1,
						children: [block("listItem", "node-item", "statement-item", "Search is faster.")],
					},
					block("cta", "node-cta", "statement-cta", "Join the beta", {
						destinationId: "destination-1",
						destinationLabel: "Join the beta",
						destinationUrl: "https://plot.test/beta",
					}),
				],
			},
		};

		const tiptap = convertArtifactDocumentToTiptap(document, sentences);
		const statements = extractArtifactStatements(tiptap, sentences);
		const roundTripped = tiptapToArtifactDocument(tiptap, statements);

		expect(statements.map((statement) => statement.id)).toEqual([
			"statement-heading",
			"statement-item",
			"statement-cta",
		]);
		expect(roundTripped.documentVersion).toBe(2);
		expect((roundTripped.root as { children: Array<{ type: string }> }).children.map((node) => node.type)).toEqual([
			"heading",
			"list",
			"cta",
		]);
		expect((roundTripped.root as { children: Array<{ children?: Array<{ statementId?: string }> }> }).children[1].children?.[0].statementId).toBe("statement-item");
	});

	it("preserves the ordered-list start value across the editor adapter", () => {
		const document = {
			documentVersion: 2,
			root: {
				type: "root",
				version: 1,
				children: [{
					type: "list",
					nodeId: "node-list",
					listType: "ordered",
					start: 3,
					children: [block("listItem", "node-item", "statement-item", "Search is faster.")],
				}],
			},
		};

		const tiptap = convertArtifactDocumentToTiptap(document, sentences);
		expect(tiptap.content?.[0]?.attrs?.start).toBe(3);
		const statements = extractArtifactStatements(tiptap, sentences);
		const roundTripped = tiptapToArtifactDocument(tiptap, statements);
		expect((roundTripped.root as { children: Array<{ start: number }> }).children[0].start).toBe(3);
	});

	it("keeps an all-paragraph document on the V1 wire shape", () => {
		const tiptap = {
			type: "doc",
			content: [{ type: "paragraph", content: [{ type: "text", text: "A supported sentence." }] }],
		};

		expect(tiptapToArtifactDocument(tiptap).documentVersion).toBeUndefined();
		expect((tiptapToArtifactDocument(tiptap).root as { children: Array<{ type: string }> }).children[0].type).toBe("paragraph");
	});

	it("keeps V1 statement identity when a paragraph becomes a heading and gives new blocks fresh identity", () => {
		const document = {
			root: {
				type: "root",
				version: 1,
				children: [
					{
						type: "paragraph",
						children: [{ detail: 0, format: 0, mode: "normal", style: "", text: "Release notes", type: "text", version: 1 }],
					},
				],
			},
		};

		const tiptap = convertArtifactDocumentToTiptap(document, [sentences[0]]);
		tiptap.content![0] = {
			...tiptap.content![0],
			type: "heading",
			attrs: { ...(tiptap.content![0].attrs as object), level: 1 },
		};
		const statements = extractArtifactStatements(tiptap, sentences);
		const withNewCta = {
			...tiptap,
			content: [
				...tiptap.content!,
				{ type: "cta", attrs: { destinationId: "destination-1", destinationLabel: "Join", destinationUrl: "https://plot.test/join" }, content: [{ type: "text", text: "Join" }] },
			],
		};
		const allStatements = extractArtifactStatements(withNewCta, sentences);

		expect(statements[0].id).toBe("statement-heading");
		expect(allStatements.map((statement) => statement.id)).toEqual(["statement-heading", expect.any(String)]);
		expect(allStatements[1].id).not.toBe("statement-item");
	});

	it("keeps V1 statement identity when a paragraph becomes a list item", () => {
		const document = {
			root: {
				type: "root",
				version: 1,
				children: [{
					type: "paragraph",
					children: [{ detail: 0, format: 0, mode: "normal", style: "", text: "Search is faster.", type: "text", version: 1 }],
				}],
			},
		};

		const tiptap = convertArtifactDocumentToTiptap(document, [sentences[1]]);
		const paragraph = tiptap.content![0];
		const listItem = {
			type: "listItem",
			content: [paragraph],
		};
		const list = {
			type: "bulletList",
			content: [listItem],
		};
		const statements = extractArtifactStatements({ type: "doc", content: [list] }, [sentences[1]]);

		expect(statements).toEqual([
			expect.objectContaining({ id: "statement-item", body: "Search is faster." }),
		]);
	});

	it("normalizes copied statement and node attributes instead of reusing identities", () => {
		const copied = {
			type: "doc",
			content: [
				{
					type: "paragraph",
					attrs: { nodeId: "node-original", statementId: "statement-heading" },
					content: [{ type: "text", text: "Release notes" }],
				},
				{
					type: "paragraph",
					attrs: { nodeId: "node-original", statementId: "statement-heading" },
					content: [{ type: "text", text: "Release notes (copy)" }],
				},
			],
		};

		const statements = extractArtifactStatements(copied, sentences);
		const serialized = tiptapToArtifactDocument(copied, statements);
		const blocks = (serialized.root as { children: Array<{ nodeId: string; statementId: string }> }).children;

		expect(statements[1].id).not.toBe("statement-heading");
		expect(statements[0].lineage).toEqual(["statement-heading"]);
		expect(statements[1].lineage).toEqual(["statement-heading"]);
		expect(blocks.map((block) => block.statementId)).toEqual(statements.map((statement) => statement.id));
		expect(blocks[0].nodeId).not.toBe(blocks[1].nodeId);
	});
});

function block(type: string, nodeId: string, statementId: string, text: string, extra: Record<string, string> = {}) {
	return {
		type,
		nodeId,
		statementId,
		...extra,
		children: [{ detail: 0, format: 0, mode: "normal", style: "", text, type: "text", version: 1 }],
	};
}
