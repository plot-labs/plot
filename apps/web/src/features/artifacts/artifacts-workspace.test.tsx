// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  search: "",
  push: vi.fn(),
  listArtifacts: vi.fn(),
  getArtifact: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mocks.push }),
  useSearchParams: () => new URLSearchParams(mocks.search),
}));

vi.mock("@/lib/api-client", () => ({
  plotApiClient: {
    listArtifacts: mocks.listArtifacts,
    getArtifact: mocks.getArtifact,
    saveArtifactVariant: vi.fn(),
  },
}));

vi.mock("@/features/artifacts/artifact-canvas-workspace", () => ({
  ArtifactCanvasWorkspace: () => <div>Artifact canvas</div>,
}));

import { ArtifactsWorkspace } from "./artifacts-workspace";

describe("ArtifactsWorkspace", () => {
  beforeEach(() => {
    mocks.search = "";
    mocks.push.mockReset();
    mocks.getArtifact.mockReset();
    mocks.listArtifacts.mockReset().mockResolvedValue({
      items: [
        {
          id: "artifact-1",
          status: "READY",
          published: false,
          title: "Local preview artifact · Chat workspace",
          contentType: "ARTIFACT",
          updatedAt: "2026-08-08T10:00:00Z",
        },
        {
          id: "artifact-2",
          status: "NEEDS_REVIEW",
          published: true,
          title: "v1.1.0 changelog",
          contentType: "CHANGELOG",
          updatedAt: "2026-08-06T12:00:00Z",
        },
      ],
      page: 0,
      size: 100,
      totalItems: 2,
      totalPages: 1,
    });
    vi.spyOn(Date, "now").mockReturnValue(Date.parse("2026-08-08T12:00:00Z"));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("uses the unselected route as a full artifact library with relative update times", async () => {
    render(<ArtifactsWorkspace />);

    const firstArtifact = await screen.findByRole("option", { name: /Local preview artifact/ });
    expect(screen.getByText("Updated 2 hours ago")).toBeVisible();
    expect(screen.getByText("Updated 2 days ago")).toBeVisible();
    expect(screen.getByText("Artifact")).toBeVisible();
    expect(screen.queryByText("Select an artifact to inspect its draft and citations.")).not.toBeInTheDocument();

    fireEvent.click(firstArtifact);
    expect(mocks.push).toHaveBeenCalledWith("/contents?artifact=artifact-1");
  });

  it.each([
    ["draft", "Local preview artifact", "v1.1.0 changelog"],
    ["published", "v1.1.0 changelog", "Local preview artifact"],
  ])("filters %s by live publication, not generation readiness", async (view, included, excluded) => {
    mocks.search = `view=${view}`;
    render(<ArtifactsWorkspace />);
    expect(await screen.findByRole("option", { name: new RegExp(included) })).toBeVisible();
    expect(screen.queryByRole("option", { name: new RegExp(excluded) })).not.toBeInTheDocument();
  });

  it("does not misclassify unknown publication metadata as a draft", async () => {
    mocks.search = "view=draft";
    mocks.listArtifacts.mockResolvedValue({ items: [{ id: "old", status: "READY", title: "Unknown", contentType: "CHANGELOG", updatedAt: "2026-08-08T10:00:00Z" }], totalItems: 1 });
    render(<ArtifactsWorkspace />);
    expect(await screen.findByText("No unpublished drafts in this view.")).toBeVisible();
    expect(screen.queryByRole("option")).not.toBeInTheDocument();
  });

  it("shows a dedicated failure state when the artifact library cannot load", async () => {
    mocks.listArtifacts.mockRejectedValue(new Error("offline"));

    render(<ArtifactsWorkspace />);

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Artifacts could not be loaded"));
  });
});
