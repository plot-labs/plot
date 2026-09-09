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

const pushMock = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

function client() {
  return {
    listArtifactHistory: vi.fn().mockResolvedValue([{ position: 0, createdAt: "2026-08-07T05:32:00Z", cause: "Current draft" }]),
    getArtifactHistoryAt: vi.fn(),
    exportArtifactVariant: vi.fn(),
    publishArtifactVariant: vi.fn(),
    replicateArtifact: vi.fn().mockResolvedValue({
      id: "agent-run-new",
      chatId: "chat-new",
      status: "QUEUED",
      contentType: "LAUNCH_ANNOUNCEMENT",
    }),
  } as unknown as PlotApiClient;
}

describe("ArtifactCanvasWorkspace", () => {
  it("renders a linked artifact breadcrumb", () => {
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

  it("shows publish and export actions in the header", () => {
    render(<ArtifactCanvasWorkspace artifact={artifact} client={client()} onSaveArtifact={vi.fn()} />);

    expect(screen.getByRole("button", { name: "Save draft" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Copy changelog" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Publish changelog" })).toBeVisible();
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

  it("hides save when a historical snapshot is selected", async () => {
    const clientWithHistory = {
      ...client(),
      getArtifactHistoryAt: vi.fn().mockResolvedValue({
        cause: "Manual save",
        artifact: {
          ...artifact,
          title: "Historical snapshot",
          variant: {
            ...artifact.variant,
            sentences: [{
              ...artifact.variant.sentences[0]!,
              body: "Historical body.",
            }],
          },
        },
      }),
    };

    render(<ArtifactCanvasWorkspace artifact={artifact} client={clientWithHistory} onSaveArtifact={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Artifact actions" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "History" }));
    fireEvent.click(await screen.findByRole("button", { name: /Current draft|Manual save/i }));

    await waitFor(() => expect(screen.queryByRole("button", { name: "Save draft" })).not.toBeInTheDocument());
    expect(screen.getByText("Saved snapshot")).toBeVisible();
  });

  it("opens CreateRelatedContentDialog and replicates artifact with new content type and instruction", async () => {
    const testClient = client();
    render(<ArtifactCanvasWorkspace artifact={artifact} client={testClient} onSaveArtifact={vi.fn()} />);

    const createButton = screen.getByRole("button", { name: "Create related content" });
    expect(createButton).toBeVisible();
    fireEvent.click(createButton);

    expect(await screen.findByRole("dialog", { name: "Create related content" })).toBeVisible();
    expect(screen.getByText(/Consumes 1 generation credit/i)).toBeVisible();

    const instructionInput = screen.getByLabelText("Additional instructions (optional)");
    fireEvent.change(instructionInput, { target: { value: "Please highlight key benefits." } });

    expect(screen.queryByRole("button", { name: "Cancel" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Generate content" }));

    await waitFor(() => expect(testClient.replicateArtifact).toHaveBeenCalledWith(
      "artifact-1",
      expect.objectContaining({
        contentType: "LAUNCH_ANNOUNCEMENT",
        instruction: "Please highlight key benefits.",
      }),
      expect.stringMatching(/^replicate-artifact-1-LAUNCH_ANNOUNCEMENT-/),
    ));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/chat?chat=chat-new&agent=agent-run-new"));
  });

  it("renders related artifacts in the Sources drawer", async () => {
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

    fireEvent.click(screen.getByRole("button", { name: "Artifact actions" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Sources" }));

    expect(await screen.findByRole("dialog", { name: "Sources" })).toBeVisible();
    expect(screen.getByRole("list", { name: "Related artifacts" })).toBeVisible();
    expect(screen.getByText("Related Launch Announcement")).toBeVisible();
    expect(screen.getByRole("link", { name: /Related Launch Announcement/i })).toHaveAttribute(
      "href",
      "/artifacts/related-artifact-99",
    );
  });
});
