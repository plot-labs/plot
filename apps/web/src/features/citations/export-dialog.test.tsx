// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ExportDialog } from "./export-dialog";
import { PlotApiError, type ContentPack, type PlotApiClient } from "@plot/api-client";

const pack: ContentPack = {
  id: "pack-1",
  generationRunId: "run-1",
  status: "NEEDS_REVIEW",
  title: "July changelog",
  variant: {
    id: "variant-1",
    status: "NEEDS_REVIEW",
    sentences: [
      { id: "sentence-7", revisionId: "rev-7", revisionNumber: 1, orderIndex: 0, body: "A claim.", origin: "GENERATED", verdict: "NEEDS_SUPPORT", reason: "No source", citations: [] },
    ],
  },
};

describe("ExportDialog", () => {
  it("downloads through the same export endpoint without confirmation when the API allows it", async () => {
    const exportVariant = vi.fn().mockResolvedValue({ exportId: "export-2", disposition: "DOWNLOAD", filename: "changelog.md", mediaType: "text/markdown", text: "Ready.", unresolvedCount: 0, warningAcknowledged: false });
    const createObjectURL = vi.fn().mockReturnValue("blob:export");
    const revokeObjectURL = vi.fn();
    Object.defineProperties(URL, { createObjectURL: { configurable: true, value: createObjectURL }, revokeObjectURL: { configurable: true, value: revokeObjectURL } });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    render(<ExportDialog pack={pack} client={{ exportVariant } as unknown as PlotApiClient} />);
    fireEvent.click(screen.getByRole("button", { name: /download changelog/i }));
    await waitFor(() => expect(click).toHaveBeenCalled());
    expect(exportVariant).toHaveBeenCalledWith("variant-1", { acknowledgeUnresolved: false, acknowledgedRevisionIds: [], disposition: "DOWNLOAD" });
    click.mockRestore();
  });

  it("warns with affected sentence IDs and exports only after explicit confirmation", async () => {
    const exportVariant = vi
      .fn()
      .mockRejectedValueOnce(new PlotApiError(409, "EXPORT_CONFIRMATION_REQUIRED", "Confirm", { sentenceIds: ["sentence-7"], revisionIds: ["rev-7"] }))
      .mockResolvedValueOnce({ exportId: "export-1", disposition: "COPY", filename: "changelog.md", mediaType: "text/markdown", text: "A claim.", unresolvedCount: 1, warningAcknowledged: true });
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    render(<ExportDialog pack={pack} client={{ exportVariant } as unknown as PlotApiClient} />);

    fireEvent.click(screen.getByRole("button", { name: /copy changelog/i }));
    expect(await screen.findByText(/sentence-7/)).toBeVisible();
    expect(writeText).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: /confirm and copy/i }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith("A claim."));
    expect(exportVariant).toHaveBeenNthCalledWith(1, "variant-1", { acknowledgeUnresolved: false, acknowledgedRevisionIds: [], disposition: "COPY" });
    expect(exportVariant).toHaveBeenNthCalledWith(2, "variant-1", { acknowledgeUnresolved: true, acknowledgedRevisionIds: ["rev-7"], disposition: "COPY" });
  });
});
