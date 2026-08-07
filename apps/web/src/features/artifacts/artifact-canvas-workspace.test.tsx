// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { Artifact, PlotApiClient } from "@plot/api-client";
import { ArtifactCanvasWorkspace } from "./artifact-canvas-workspace";

const artifact: Artifact = {
  id: "artifact-1",
  generationRunId: "run-1",
  status: "READY",
  title: "OpenRouter summary provider",
  variant: {
    id: "variant-1",
    status: "READY",
    revisionId: "revision-1",
    revisionNumber: 1,
    lexicalContent: {
      root: {
        children: [{
          children: [{ detail: 0, format: 0, mode: "normal", style: "", text: "OpenRouter is selectable as a summary provider.", type: "text", version: 1 }],
          direction: null,
          format: "",
          indent: 0,
          type: "paragraph",
          version: 1,
        }],
        direction: null,
        format: "",
        indent: 0,
        type: "root",
        version: 1,
      },
    },
    sentences: [{
      id: "sentence-1",
      revisionId: "sentence-revision-1",
      revisionNumber: 1,
      orderIndex: 0,
      body: "OpenRouter is selectable as a summary provider.",
      origin: "GENERATED",
      citations: [],
    }],
    sources: [{
      evidenceId: "evidence-1",
      provider: "GITHUB",
      sourceLabel: "OpenRouter documentation",
      originalUrl: "https://openrouter.ai/docs",
      statementIds: ["sentence-1"],
    }],
  },
};

function client() {
  return {
    listArtifactHistory: vi.fn().mockResolvedValue([{ position: 0, createdAt: "2026-08-07T05:32:00Z", cause: "Current draft" }]),
    getArtifactHistoryAt: vi.fn(),
    exportArtifactVariant: vi.fn(),
  } as unknown as PlotApiClient;
}

describe("ArtifactCanvasWorkspace", () => {
  it("saves from the contextual toolbar", async () => {
    const onSaveArtifact = vi.fn().mockResolvedValue(artifact);
    render(<ArtifactCanvasWorkspace artifact={artifact} client={client()} onSaveArtifact={onSaveArtifact} />);

    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(onSaveArtifact).toHaveBeenCalledWith(expect.objectContaining({
      expectedRevisionNumber: 1,
      statements: [{ id: "sentence-1", orderIndex: 0, body: "OpenRouter is selectable as a summary provider." }],
    })));
  });

  it("opens the Figma-aligned History drawer and restores focus", async () => {
    render(<ArtifactCanvasWorkspace artifact={artifact} client={client()} onSaveArtifact={vi.fn()} />);

    const trigger = screen.getByRole("button", { name: "Artifact actions" });
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole("menuitem", { name: "History" }));

    expect(await screen.findByRole("dialog", { name: "History" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it("shows attached source links in the Sources drawer", () => {
    render(<ArtifactCanvasWorkspace artifact={artifact} client={client()} onSaveArtifact={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Artifact actions" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Sources" }));

    expect(screen.getByRole("dialog", { name: "Sources" })).toBeVisible();
    expect(screen.getByRole("doc-noteref", { name: /OpenRouter documentation/ })).toHaveAttribute("href", "https://openrouter.ai/docs");
  });
});
