// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { $createParagraphNode, $createTextNode, $getRoot, createEditor } from "lexical";

import {
  CitedDraftEditor,
  initializeStatementIdentityMap,
  projectStatementBlocks,
} from "./cited-draft-editor";
import type { Artifact } from "@plot/api-client";

const pack: Artifact = {
  id: "pack-1",
  status: "NEEDS_REVIEW",
  title: "July changelog",
  variant: {
    id: "variant-1",
    status: "NEEDS_REVIEW",
    revisionId: "artifact-revision-1",
    revisionNumber: 1,
    lexicalContent: lexicalContent(
      "Sign-in recovery now explains the next step.",
      "The release is delightful.",
    ),
    sentences: [
      {
        id: "sentence-1",
        revisionId: "sentence-revision-1",
        revisionNumber: 1,
        orderIndex: 0,
        body: "Sign-in recovery now explains the next step.",
        origin: "GENERATED",
        citations: [],
      },
      {
        id: "sentence-2",
        revisionId: "sentence-revision-2",
        revisionNumber: 1,
        orderIndex: 1,
        body: "The release is delightful.",
        origin: "GENERATED",
        citations: [],
      },
    ],
    sources: [
      {
        evidenceId: "evidence-1",
        provider: "GITHUB",
        sourceLabel: "PR #184",
        originalUrl: "https://github.com/acme/plot/pull/184",
        statementIds: ["sentence-1"],
      },
      {
        evidenceId: "evidence-2",
        provider: "GITHUB",
        sourceLabel: "PR #184",
        originalUrl: "https://github.com/acme/plot/pull/184",
        statementIds: ["sentence-2"],
      },
    ],
  },
};

function lexicalContent(...bodies: string[]) {
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

describe("CitedDraftEditor", () => {
  it("renders historical snapshots read-only without a delivery edit control", () => {
    render(<CitedDraftEditor pack={pack} readOnly onSaveArtifact={vi.fn()} />);

    expect(screen.getByRole("textbox", { name: "Historical artifact content" })).toHaveAttribute("contenteditable", "false");
    expect(screen.queryByRole("button", { name: "Save draft" })).not.toBeInTheDocument();
  });

  it("uses Lexical for the whole artifact and keeps citation data out of editor content", async () => {
    const onSaveArtifact = vi.fn().mockResolvedValue(pack);
    render(<CitedDraftEditor pack={pack} onSaveArtifact={onSaveArtifact} />);

    expect(screen.getByRole("textbox", { name: "Draft content" })).toBeVisible();
    expect(screen.getByText("Sign-in recovery now explains the next step.")).toBeVisible();
    expect(screen.getByText("The release is delightful.")).toBeVisible();
    expect(screen.queryByText("[1]")).not.toBeInTheDocument();
    expect(screen.queryByText(/verified|unverified|verdict/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Sources/ }));
    expect(screen.getByRole("dialog", { name: "Sources" })).toBeVisible();
    expect(screen.getAllByRole("doc-noteref", { name: /PR #184/ })).toHaveLength(1);
    expect(screen.queryByText(/snapshot|excerpt/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(onSaveArtifact).toHaveBeenCalledWith(expect.objectContaining({
      expectedRevisionNumber: 1,
      statements: [
        { id: "sentence-1", orderIndex: 0, body: "Sign-in recovery now explains the next step." },
        { id: "sentence-2", orderIndex: 1, body: "The release is delightful." },
      ],
    })));
    expect(JSON.stringify(onSaveArtifact.mock.calls[0]?.[0].lexicalContent)).not.toContain('"key"');
  });

  it("restores an unsaved draft when an artifact is selected again", async () => {
    const draft = lexicalContent("Unsaved recovery guidance.", "The release is delightful.");
    const onSaveArtifact = vi.fn().mockResolvedValue(pack);
    render(
      <CitedDraftEditor
        pack={pack}
        initialDraft={{
          lexicalContent: draft,
          statements: [
            { id: "sentence-1", orderIndex: 0, body: "Unsaved recovery guidance." },
            { id: "sentence-2", orderIndex: 1, body: "The release is delightful." },
          ],
        }}
        onSaveArtifact={onSaveArtifact}
      />,
    );

    expect(await screen.findByText("Unsaved recovery guidance.")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(onSaveArtifact).toHaveBeenCalledWith(expect.objectContaining({
      statements: [
        { id: "sentence-1", orderIndex: 0, body: "Unsaved recovery guidance." },
        { id: "sentence-2", orderIndex: 1, body: "The release is delightful." },
      ],
    })));
  });

  it("keeps source citations as original URL links without statement navigation", async () => {
    render(<CitedDraftEditor pack={pack} onSaveArtifact={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /Sources/ }));
    const source = screen.getByRole("doc-noteref", { name: /PR #184/ });
    expect(source).toHaveAttribute("href", "https://github.com/acme/plot/pull/184");
    expect(source).toHaveAttribute("target", "_blank");
    fireEvent.click(source);
    const statement = document.querySelector<HTMLElement>('[data-statement-id="sentence-1"]');
    expect(statement).not.toHaveFocus();
    expect(statement).not.toHaveAttribute("data-statement-highlight");
  });

  it("keeps duplicate-body insertion tied to the new Lexical node", () => {
    const mapping = initializeStatementIdentityMap(["key-a", "key-b"], ["sentence-a", "sentence-b"]);
    expect(projectStatementBlocks(mapping, [
      { key: "key-a", body: "Duplicate" },
      { key: "key-new", body: "Duplicate" },
      { key: "key-b", body: "Other" },
    ], () => "sentence-new")).toEqual({
      mapping: new Map([
        ["key-a", "sentence-a"],
        ["key-new", "sentence-new"],
        ["key-b", "sentence-b"],
      ]),
      blocks: [
        { id: "sentence-a", body: "Duplicate" },
        { id: "sentence-new", body: "Duplicate" },
        { id: "sentence-b", body: "Other" },
      ],
    });
  });

  it("drops only the deleted duplicate-body Lexical node", () => {
    const mapping = initializeStatementIdentityMap(
      ["key-a", "key-b", "key-c"],
      ["sentence-a", "sentence-b", "sentence-c"],
    );
    expect(projectStatementBlocks(mapping, [
      { key: "key-a", body: "Duplicate" },
      { key: "key-c", body: "Tail" },
    ], () => "unused").blocks).toEqual([
      { id: "sentence-a", body: "Duplicate" },
      { id: "sentence-c", body: "Tail" },
    ]);
  });

  it("keeps IDs across reorder plus edit without matching bodies", () => {
    const mapping = initializeStatementIdentityMap(["key-a", "key-b", "key-c"], ["sentence-a", "sentence-b", "sentence-c"]);
    const projected = projectStatementBlocks(mapping, [
      { key: "key-c", body: "C edited" },
      { key: "key-a", body: "A" },
      { key: "key-b", body: "B" },
    ], () => "unused");
    expect(projected.blocks).toEqual([
      { id: "sentence-c", body: "C edited" },
      { id: "sentence-a", body: "A" },
      { id: "sentence-b", body: "B" },
    ]);
  });

  it("tracks real Lexical blocks through duplicate insertion, deletion, and mixed reorder plus edit", () => {
    const editor = createEditor({ namespace: "statement-identity-test" });
    editor.update(() => {
      const root = $getRoot();
      root.clear();
      ["Duplicate", "Duplicate", "Tail"].forEach((body) => {
        const paragraph = $createParagraphNode();
        paragraph.append($createTextNode(body));
        root.append(paragraph);
      });
    }, { discrete: true });

    function readNodes() {
      const nodes: { key: string; body: string }[] = [];
      editor.getEditorState().read(() => {
        $getRoot().getChildren().forEach((node) => nodes.push({ key: node.getKey(), body: node.getTextContent() }));
      });
      return nodes;
    }

    const initialNodes = readNodes();
    const mapping = initializeStatementIdentityMap(
      initialNodes.map((node) => node.key),
      ["sentence-a", "sentence-b", "sentence-c"],
    );
    editor.update(() => {
      const root = $getRoot();
      const [first, second, third] = root.getChildren();
      if (!first || !second || !third) return;
      (third as ReturnType<typeof $createParagraphNode>).getFirstChild()?.replace($createTextNode("Tail edited"));
      const inserted = $createParagraphNode();
      inserted.append($createTextNode("Duplicate"));
      root.clear();
      root.append(third, first, inserted);
    }, { discrete: true });

    const projected = projectStatementBlocks(mapping, readNodes(), () => "sentence-new");
    expect(projected.blocks).toEqual([
      { id: "sentence-c", body: "Tail edited" },
      { id: "sentence-a", body: "Duplicate" },
      { id: "sentence-new", body: "Duplicate" },
    ]);
    expect(projected.blocks.every((block) => !Object.hasOwn(block, "key"))).toBe(true);
  });

  it("allocates one browser UUID and retains it for a new node", () => {
    const generatedId = "00000000-0000-4000-8000-000000000001";
    const randomUUID = vi.fn().mockReturnValue(generatedId);
    vi.stubGlobal("crypto", { randomUUID });
    try {
      const first = projectStatementBlocks(new Map(), [{ key: "key-new", body: "A" }]);
      const second = projectStatementBlocks(first.mapping, [{ key: "key-new", body: "A changed" }]);
      expect(first.blocks).toEqual([{ id: generatedId, body: "A" }]);
      expect(second.blocks).toEqual([{ id: generatedId, body: "A changed" }]);
      expect(randomUUID).toHaveBeenCalledTimes(1);
      expect(first.blocks[0]).not.toHaveProperty("key");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("restores a changed artifact revision without replacing stable statement IDs", async () => {
    const updated: Artifact = {
      ...pack,
      variant: {
        ...pack.variant,
        revisionId: "artifact-revision-2",
        revisionNumber: 2,
        lexicalContent: lexicalContent("Recovery guidance now explains the next step.", "The release is delightful."),
        sentences: pack.variant.sentences.map((sentence) => sentence.id === "sentence-1"
          ? { ...sentence, revisionId: "sentence-revision-3", revisionNumber: 2, body: "Recovery guidance now explains the next step." }
          : sentence),
      },
    };
    const onSaveArtifact = vi.fn().mockResolvedValue(updated);
    const { rerender } = render(<CitedDraftEditor pack={pack} onSaveArtifact={onSaveArtifact} />);
    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(onSaveArtifact).toHaveBeenCalled());
    rerender(<CitedDraftEditor pack={updated} onSaveArtifact={onSaveArtifact} />);
    expect(await screen.findByText("Recovery guidance now explains the next step.")).toBeVisible();
    expect(document.querySelector('[data-statement-id="sentence-1"]')).toBeInTheDocument();
  });
});
