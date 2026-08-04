// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ExportDialog } from "./export-dialog";
import { CitedDraftEditor } from "./cited-draft-editor";
import { PlotApiError, type Artifact, type PlotApiClient } from "@plot/api-client";

const pack: Artifact = {
  id: "pack-1",
  generationRunId: "run-1",
  status: "NEEDS_REVIEW",
  title: "July changelog",
  variant: {
    id: "variant-1",
    status: "NEEDS_REVIEW",
    revisionId: "artifact-revision-1",
    revisionNumber: 3,
    lexicalContent: lexicalContent("A claim."),
    sentences: [
      { id: "sentence-7", revisionId: "rev-7", revisionNumber: 2, orderIndex: 0, body: "A claim.", origin: "USER_MODIFIED", citations: [] },
    ],
    sources: [],
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

describe("ExportDialog", () => {
  it("uses the same revision-bound endpoint for download and can explicitly include sources", async () => {
    const exportArtifactVariant = vi.fn().mockResolvedValue({ exportId: "export-2", disposition: "DOWNLOAD", filename: "changelog.md", mediaType: "text/markdown", text: "Ready.", unresolvedCount: 0, warningAcknowledged: false, includeSources: true });
    const createObjectURL = vi.fn().mockReturnValue("blob:export");
    const revokeObjectURL = vi.fn();
    Object.defineProperties(URL, { createObjectURL: { configurable: true, value: createObjectURL }, revokeObjectURL: { configurable: true, value: revokeObjectURL } });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    render(<ExportDialog pack={pack} client={{ exportArtifactVariant } as unknown as PlotApiClient} />);
    fireEvent.click(screen.getByRole("checkbox", { name: /include sources/i }));
    fireEvent.click(screen.getByRole("button", { name: /download changelog/i }));
    await waitFor(() => expect(click).toHaveBeenCalled());
    expect(exportArtifactVariant).toHaveBeenCalledWith("variant-1", {
      expectedRevisionNumber: 3,
      includeSources: true,
      acknowledgeUnresolved: false,
      acknowledgedWarningKeys: [],
      disposition: "DOWNLOAD",
    });
    click.mockRestore();
  });

  it("renders human-readable warnings without UUIDs and acknowledges the exact warning keys", async () => {
    const exportArtifactVariant = vi
      .fn()
      .mockRejectedValueOnce(new PlotApiError(409, "EXPORT_CONFIRMATION_REQUIRED", "Confirm", {
        warnings: [{ key: "warning-key-1", sentenceNumber: 1, excerpt: "A claim." }],
      }))
      .mockResolvedValueOnce({ exportId: "export-1", disposition: "COPY", filename: "changelog.md", mediaType: "text/markdown", text: "A claim.", unresolvedCount: 1, warningAcknowledged: true });
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    render(
      <>
        <div data-statement-id="sentence-7" tabIndex={-1}>Draft sentence</div>
        <ExportDialog pack={pack} client={{ exportArtifactVariant } as unknown as PlotApiClient} />
      </>,
    );

    fireEvent.click(screen.getByRole("button", { name: /copy changelog/i }));
    const affected = await screen.findByRole("button", { name: /Statement 1 — “A claim\.”/ });
    expect(screen.queryByText(/sentence-7|rev-7/)).not.toBeInTheDocument();
    expect(writeText).not.toHaveBeenCalled();
    fireEvent.click(affected);
    expect(screen.getByText("Draft sentence")).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: /confirm and copy/i }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith("A claim."));
    expect(exportArtifactVariant).toHaveBeenNthCalledWith(1, "variant-1", expect.objectContaining({ expectedRevisionNumber: 3, includeSources: false, acknowledgedWarningKeys: [] }));
    expect(exportArtifactVariant).toHaveBeenNthCalledWith(2, "variant-1", expect.objectContaining({ expectedRevisionNumber: 3, acknowledgedWarningKeys: ["warning-key-1"] }));
  });

  it("focuses and highlights the real Lexical statement block from an export warning", async () => {
    const exportArtifactVariant = vi.fn().mockRejectedValueOnce(new PlotApiError(409, "EXPORT_CONFIRMATION_REQUIRED", "Confirm", {
      warnings: [{ key: "warning-key-1", sentenceNumber: 1, excerpt: "A claim." }],
    }));
    render(
      <>
        <CitedDraftEditor pack={pack} onSaveArtifact={vi.fn()} />
        <ExportDialog pack={pack} client={{ exportArtifactVariant } as unknown as PlotApiClient} />
      </>,
    );
    fireEvent.click(screen.getByRole("button", { name: /copy changelog/i }));
    const affected = await screen.findByRole("button", { name: /Statement 1 — “A claim\.”/ });
    const statement = await waitFor(() => document.querySelector<HTMLElement>('[data-statement-id="sentence-7"]'));
    expect(statement).toHaveAttribute("tabindex", "-1");
    fireEvent.click(affected);
    expect(statement).toHaveFocus();
    expect(statement).toHaveAttribute("data-statement-highlight", "true");
  });
});
