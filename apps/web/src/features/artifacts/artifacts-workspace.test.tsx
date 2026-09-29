// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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

    const firstArtifact = await screen.findByRole("link", { name: /Local preview artifact/ });
    expect(screen.getByText("Updated 2 hours ago")).toBeVisible();
    expect(screen.getByText("Updated 2 days ago")).toBeVisible();
    expect(screen.getByText("Name")).toBeVisible();
    expect(screen.getByText("Status")).toBeVisible();
    expect(screen.getByText("Updated")).toBeVisible();
    expect(screen.queryByText("Select an artifact to inspect its draft and citations.")).not.toBeInTheDocument();

    expect(firstArtifact).toHaveAttribute("href", "/contents?artifact=artifact-1");
  });

  it.each(["draft", "published"])("keeps the full library for legacy view=%s links", async (view) => {
    mocks.search = `view=${view}`;
    render(<ArtifactsWorkspace />);
    const draft = await screen.findByRole("link", { name: /Local preview artifact/ });
    const published = screen.getByRole("link", { name: /v1.1.0 changelog/ });
    expect(within(draft).getByText("Draft", { exact: true })).toBeVisible();
    expect(within(published).getByText("Published", { exact: true })).toBeVisible();
  });

  it("labels missing publication metadata as unknown", async () => {
    mocks.listArtifacts.mockResolvedValue({ items: [{ id: "old", status: "READY", title: "Legacy content", contentType: "CHANGELOG", updatedAt: "2026-08-08T10:00:00Z" }], totalItems: 1 });
    render(<ArtifactsWorkspace />);
    const row = await screen.findByRole("link", { name: /Legacy content/ });
    expect(within(row).getByText("Unknown", { exact: true })).toBeVisible();
    expect(within(row).queryByText("Draft", { exact: true })).not.toBeInTheDocument();
  });

  it("shows a dedicated failure state when the artifact library cannot load", async () => {
    mocks.listArtifacts.mockRejectedValue(new Error("offline"));

    render(<ArtifactsWorkspace />);

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Contents could not be loaded"));
    mocks.listArtifacts.mockResolvedValue({ items: [], totalItems: 0 });
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("No contents yet")).toBeVisible();
  });
});
