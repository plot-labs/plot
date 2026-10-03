// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { Artifact, PlotApiClient } from "@plot/api-client";
import { ArtifactCanvasWorkspace } from "./artifact-canvas-workspace";

const artifact: Artifact = {
  id: "artifact-1",
  status: "READY",
  title: "OpenRouter summary provider",
  contentType: "CHANGELOG",
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
    exportArtifactVariant: vi.fn(),
    publishArtifactVariant: vi.fn(),
  } as unknown as PlotApiClient;
}

describe("ArtifactCanvasWorkspace", () => {
  it("renders a linked document breadcrumb", () => {
    render(<ArtifactCanvasWorkspace artifact={artifact} client={client()} onSaveArtifact={vi.fn()} />);

    const breadcrumb = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(breadcrumb).toContainElement(screen.getByRole("link", { name: "Contents" }));
    expect(screen.getByRole("link", { name: "Contents" })).toHaveAttribute("href", "/contents");
    expect(breadcrumb).toHaveTextContent("Contents/OpenRouter summary provider");
    expect(screen.getByText("OpenRouter summary provider", { selector: '[aria-current="page"]' })).toBeVisible();
  });

  it("saves from the contextual toolbar", async () => {
    const onSaveArtifact = vi.fn().mockResolvedValue(artifact);
    render(<ArtifactCanvasWorkspace artifact={artifact} client={client()} onSaveArtifact={onSaveArtifact} />);

    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(onSaveArtifact).toHaveBeenCalledWith(expect.objectContaining({
      expectedRevisionNumber: 1,
      statements: [{ id: "sentence-1", orderIndex: 0, body: "OpenRouter is selectable as a summary provider." }],
    })));
  });

  it("groups copy and download under document actions", () => {
    render(<ArtifactCanvasWorkspace artifact={artifact} client={client()} onSaveArtifact={vi.fn()} />);

    expect(screen.getByRole("button", { name: "Save draft" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Copy changelog" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Export options" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Content actions" }));
    expect(screen.getByRole("menuitem", { name: "Copy Markdown" })).toBeVisible();
    expect(screen.getByRole("menuitem", { name: "Download Markdown" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Publish changelog" })).toBeVisible();
  });

  it("blocks publish and export until an edited title is saved", () => {
    render(<ArtifactCanvasWorkspace artifact={artifact} client={client()} onSaveArtifact={vi.fn()} />);

    fireEvent.change(screen.getByPlaceholderText("Untitled content"), { target: { value: "Renamed changelog" } });

    expect(screen.getByRole("button", { name: "Publish changelog" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Content actions" }));
    expect(screen.getByRole("menuitem", { name: "Copy Markdown" })).toBeDisabled();
  });

  it("moves through content actions with the keyboard", () => {
    render(<ArtifactCanvasWorkspace artifact={artifact} client={client()} onSaveArtifact={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Content actions" }));
    const items = Array.from(screen.getByRole("menu").querySelectorAll<HTMLElement>("[role^=menuitem]"));
    expect(items[0]).toHaveFocus();
    fireEvent.keyDown(items[0], { key: "ArrowDown" });
    expect(items[1]).toHaveFocus();
    fireEvent.keyDown(items[1], { key: "ArrowUp" });
    expect(items[0]).toHaveFocus();
    fireEvent.keyDown(items[0], { key: "ArrowUp" });
    expect(items[items.length - 1]).toHaveFocus();
  });

  it("renders related documents in the Sources drawer", async () => {
    const artifactWithRelated: Artifact = {
      ...artifact,
      relatedArtifacts: [
        {
          id: "related-artifact-99",
          title: "Related Launch Announcement",
          contentType: "LAUNCH_ANNOUNCEMENT",
          status: "READY",
          updatedAt: "2026-09-08T00:00:00Z",
        },
      ],
    };

    render(<ArtifactCanvasWorkspace artifact={artifactWithRelated} client={client()} onSaveArtifact={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Content actions" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Sources" }));

    expect(await screen.findByRole("dialog", { name: "Sources" })).toBeVisible();
    expect(screen.getByRole("list", { name: "Related content" })).toBeVisible();
    expect(screen.getByText("Related Launch Announcement")).toBeVisible();
    expect(screen.getByRole("link", { name: /Related Launch Announcement/i })).toHaveAttribute(
      "href",
      "/contents?artifact=related-artifact-99",
    );
  });
});
