// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { CitedDraftEditor } from "./cited-draft-editor";
import type { ContentPack } from "@plot/api-client";

const pack: ContentPack = {
  id: "pack-1",
  generationRunId: "run-1",
  status: "NEEDS_REVIEW",
  title: "July changelog",
  variant: {
    id: "variant-1",
    status: "NEEDS_REVIEW",
    sentences: [
      {
        id: "sentence-1",
        revisionId: "revision-1",
        revisionNumber: 1,
        orderIndex: 0,
        body: "Sign-in recovery now explains the next step.",
        origin: "GENERATED",
        verdict: "SUPPORTED",
        reason: null,
        citations: [
          {
            evidenceId: "evidence-1",
            provider: "GITHUB",
            sourceLabel: "PR #184",
            originalUrl: "https://github.com/acme/plot/pull/184",
            snapshotExcerpt: "Clarify recovery copy after failed sign-in.",
          },
        ],
      },
      {
        id: "sentence-2",
        revisionId: "revision-2",
        revisionNumber: 1,
        orderIndex: 1,
        body: "The release is delightful.",
        origin: "GENERATED",
        verdict: "NOT_REQUIRED",
        reason: null,
        citations: [],
      },
      {
        id: "sentence-3",
        revisionId: "revision-3",
        revisionNumber: 1,
        orderIndex: 2,
        body: "Updates are now fully automatic.",
        origin: "GENERATED",
        verdict: "NEEDS_SUPPORT",
        reason: "No source supports the automation claim.",
        citations: [],
      },
    ],
  },
};

describe("CitedDraftEditor", () => {
  it("renders multiple label citations without numeric editor markers and rejects unsafe links", () => {
    const multiple: ContentPack = {
      ...pack,
      variant: {
        ...pack.variant,
        sentences: [{
          ...pack.variant.sentences[0]!,
          citations: [
            ...pack.variant.sentences[0]!.citations,
            { evidenceId: "evidence-2", provider: "LINEAR", sourceLabel: "PLOT-77", originalUrl: "javascript:alert(1)", snapshotExcerpt: "A product wording decision is open." },
          ],
        }],
      },
    };
    render(<CitedDraftEditor pack={multiple} onEditSentence={vi.fn()} />);
    expect(screen.getByRole("button", { name: "GitHub · PR #184" })).toBeVisible();
    const second = screen.getByRole("button", { name: "Linear · PLOT-77" });
    expect(screen.queryByText("[1]")).not.toBeInTheDocument();
    fireEvent.click(second);
    expect(screen.getByText("A product wording decision is open.")).toBeVisible();
    expect(screen.getByText("Original link unavailable")).toBeVisible();
  });

  it("opens a compact provider-label citation by click and Escape returns focus", () => {
    render(<CitedDraftEditor pack={pack} onEditSentence={vi.fn()} />);

    expect(screen.queryByText(/citation not required/i)).not.toBeInTheDocument();
    const citation = screen.getByRole("button", { name: /GitHub · PR #184/i });
    fireEvent.click(citation);
    expect(screen.getByText("Clarify recovery copy after failed sign-in.")).toBeVisible();
    expect(screen.getByRole("link", { name: /open original/i })).toHaveAttribute(
      "href",
      "https://github.com/acme/plot/pull/184",
    );

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByText("Clarify recovery copy after failed sign-in.")).not.toBeInTheDocument();
    expect(citation).toHaveFocus();
  });

  it("keeps review failure local and explicitly saves only the edited sentence", async () => {
    const updated: ContentPack = {
      ...pack,
      variant: {
        ...pack.variant,
        sentences: pack.variant.sentences.map((sentence) =>
          sentence.id === "sentence-1"
            ? {
                ...sentence,
                body: "Recovery guidance now explains the next step.",
                revisionNumber: 2,
                origin: "USER_MODIFIED" as const,
                verdict: "USER_MODIFIED" as const,
                citations: [],
              }
            : sentence,
        ),
      },
    };
    const onEditSentence = vi.fn().mockResolvedValue(updated);
    render(<CitedDraftEditor pack={pack} onEditSentence={onEditSentence} />);

    expect(screen.getByText("No source supports the automation claim.")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: /edit sentence 1/i }));
    const input = screen.getByRole("textbox", { name: /sentence 1 text/i });
    fireEvent.change(input, { target: { value: "Recovery guidance now explains the next step." } });
    fireEvent.click(screen.getByRole("button", { name: /^save sentence 1$/i }));

    await waitFor(() => expect(onEditSentence).toHaveBeenCalledWith(pack.variant.sentences[0], "Recovery guidance now explains the next step."));
    expect(await screen.findByText("Edited · unverified")).toBeVisible();
    expect(screen.queryByRole("button", { name: /GitHub · PR #184/i })).not.toBeInTheDocument();
    expect(screen.getByText("No source supports the automation claim.")).toBeVisible();
  });
});
