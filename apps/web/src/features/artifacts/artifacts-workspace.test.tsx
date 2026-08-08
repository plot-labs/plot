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
          generationRunId: "run-1",
          status: "READY",
          title: "Local preview artifact · Session workspace",
          updatedAt: "2026-08-08T10:00:00Z",
        },
        {
          id: "artifact-2",
          generationRunId: "run-2",
          status: "NEEDS_REVIEW",
          title: "v1.1.0 changelog",
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
    expect(screen.queryByText("Select an artifact to inspect its draft and citations.")).not.toBeInTheDocument();

    fireEvent.click(firstArtifact);
    expect(mocks.push).toHaveBeenCalledWith("/artifacts?artifact=artifact-1");
  });

  it("shows a dedicated failure state when the artifact library cannot load", async () => {
    mocks.listArtifacts.mockRejectedValue(new Error("offline"));

    render(<ArtifactsWorkspace />);

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Artifacts could not be loaded"));
  });
});
