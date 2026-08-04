// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { CitedDraftEditor, reconcileStatementBlocks } from "./cited-draft-editor";
import type { ContentPack } from "@plot/api-client";

const pack: ContentPack = {
  id: "pack-1",
  generationRunId: "run-1",
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

  it("preserves application IDs across reorder and isolates ambiguous structural edits", () => {
    const previous = [
      { id: "sentence-a", body: "A" },
      { id: "sentence-b", body: "B" },
      { id: "sentence-c", body: "C" },
    ];
    expect(reconcileStatementBlocks(previous, ["C", "A", "B"], () => "new")).toEqual([
      { id: "sentence-c", body: "C" },
      { id: "sentence-a", body: "A" },
      { id: "sentence-b", body: "B" },
    ]);
    expect(reconcileStatementBlocks(previous, ["A", "C"], () => "new")).toEqual([
      { id: "sentence-a", body: "A" },
      { id: "sentence-c", body: "C" },
    ]);
    expect(reconcileStatementBlocks(previous, ["Inserted", "A", "B", "C"], () => "new")).toEqual([
      { id: "new", body: "Inserted" },
      { id: "sentence-a", body: "A" },
      { id: "sentence-b", body: "B" },
      { id: "sentence-c", body: "C" },
    ]);
  });

  it("restores a changed artifact revision without replacing stable statement IDs", async () => {
    const updated: ContentPack = {
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
