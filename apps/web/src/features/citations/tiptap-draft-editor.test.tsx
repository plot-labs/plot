// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { TiptapDraftEditor } from "./tiptap-draft-editor";
import type { Artifact } from "@plot/api-client";

const pack: Artifact = {
  id: "pack-1",
  status: "NEEDS_REVIEW",
  title: "July changelog",
  contentType: "CHANGELOG",
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
        citations: [
          {
            evidenceId: "evidence-1",
            provider: "GITHUB",
            sourceLabel: "PR #184",
            originalUrl: "https://github.com/acme/plot/pull/184",
          },
        ],
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

describe("TiptapDraftEditor", () => {
  it("renders historical snapshots read-only without a delivery edit control", () => {
    render(<TiptapDraftEditor pack={pack} readOnly onSaveArtifact={vi.fn()} />);

    expect(screen.getByRole("textbox", { name: "Historical artifact content" })).toHaveAttribute("contenteditable", "false");
    expect(screen.queryByRole("button", { name: "Save draft" })).not.toBeInTheDocument();
  });

  it("renders document text and inline citations correctly", async () => {
    const onSaveArtifact = vi.fn().mockResolvedValue(pack);
    render(<TiptapDraftEditor pack={pack} onSaveArtifact={onSaveArtifact} />);

    expect(screen.getByRole("textbox", { name: "Draft content" })).toBeInTheDocument();
    expect(screen.getByText("Sign-in recovery now explains the next step.")).toBeInTheDocument();
    expect(screen.getByText("The release is delightful.")).toBeInTheDocument();
  });

  it("opens citations by keyboard and returns focus without nested links", async () => {
    render(<TiptapDraftEditor pack={pack} onSaveArtifact={vi.fn()} />);
    const trigger = await screen.findByRole("button", { name: "Citation 1: PR #184" });
    expect(trigger.querySelector("a, button")).toBeNull();
    fireEvent.keyDown(trigger, { key: "Enter" });
    const dialog = await screen.findByRole("dialog", { name: "Citation details" });
    await waitFor(() => expect(dialog).toHaveFocus());
    expect(screen.getByRole("link", { name: "Open" })).toHaveAttribute("href", "https://github.com/acme/plot/pull/184");
    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(trigger).toHaveFocus());
    expect(screen.queryByRole("dialog", { name: "Citation details" })).not.toBeInTheDocument();
  });

  it.each([pack.title, null])("includes the displayed document title when saving from the chat panel (%s)", async (title) => {
    const currentPack = { ...pack, title };
    const onSaveArtifact = vi.fn().mockResolvedValue(currentPack);
    const { rerender } = render(<TiptapDraftEditor pack={currentPack} onSaveArtifact={onSaveArtifact} saveRequestToken={0} />);
    await screen.findByRole("textbox", { name: "Draft content" });
    rerender(<TiptapDraftEditor pack={currentPack} onSaveArtifact={onSaveArtifact} saveRequestToken={1} />);
    await waitFor(() => expect(onSaveArtifact).toHaveBeenCalledWith(expect.objectContaining({
      title: title ?? "Generated artifact",
      expectedRevisionNumber: pack.variant.revisionNumber,
    })));
  });

  it("marks a restored unsaved draft as dirty instead of saved", async () => {
    const onSaveStateChange = vi.fn();
    render(<TiptapDraftEditor pack={pack} onSaveArtifact={vi.fn()} initialDraft={{
      lexicalContent: pack.variant.lexicalContent, statements: [],
    }} onSaveStateChange={onSaveStateChange} />);
    await waitFor(() => expect(onSaveStateChange).toHaveBeenCalledWith("dirty"));
    expect(onSaveStateChange).not.toHaveBeenCalledWith("saved");
  });

  it("displays save confirmation status", async () => {
    const onSaveArtifact = vi.fn().mockResolvedValue(pack);
    const onSaveStateChange = vi.fn();

    render(
      <TiptapDraftEditor
        pack={pack}
        onSaveArtifact={onSaveArtifact}
        onSaveStateChange={onSaveStateChange}
      />,
    );

    await waitFor(() => {
      expect(onSaveStateChange).toHaveBeenCalledWith("saved");
    });
  });
});
