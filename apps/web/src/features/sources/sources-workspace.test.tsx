// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  listConnections: vi.fn(),
  listWritingBlocks: vi.fn(),
}));

vi.mock("@/lib/api-client", () => ({
  plotApiClient: {
    listGitHubConnections: mocks.listConnections,
    listWritingBlocks: mocks.listWritingBlocks,
  },
}));

import { SourcesWorkspace } from "./sources-workspace";

describe("SourcesWorkspace", () => {
  beforeEach(() => {
    mocks.listConnections.mockReset();
    mocks.listWritingBlocks.mockReset();
  });

  it("shows only collected Writing Blocks and their original details", async () => {
    mocks.listConnections.mockResolvedValue([{ id: "connection-1", status: "ACTIVE", repositories: [{
      id: "scope-1",
      sourceScopeId: "scope-1",
      externalRepositoryId: 42,
      displayName: "acme/plot",
      status: "ACTIVE",
    }] }]);
    mocks.listWritingBlocks.mockResolvedValue({
      items: [{
        id: "block-1",
        sourceKind: "PULL_REQUEST",
        title: "Clarify recovery",
        body: "Recovery copy and retry behavior.",
        url: "https://github.com/acme/plot/pull/184",
        canonicalUrl: null,
        sourceCreatedAt: "2026-07-03T00:00:00Z",
        status: "ACTIVE",
      }],
      page: 0,
      size: 100,
      totalItems: 1,
      totalPages: 1,
    });

    render(<SourcesWorkspace />);
    fireEvent.click(await screen.findByRole("option", { name: /Clarify recovery/ }));

    expect(screen.getByRole("heading", { name: "Clarify recovery" })).toBeVisible();
    expect(screen.getByText("Recovery copy and retry behavior.")).toBeVisible();
    expect(screen.getByRole("link", { name: /View on GitHub/ })).toHaveAttribute("href", "https://github.com/acme/plot/pull/184");
    expect(screen.queryByRole("button", { name: /Connect GitHub|Import last 30 days|Refresh/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/Import complete:/)).not.toBeInTheDocument();
  });

  it("links to Integrations without starting an import when no blocks exist", async () => {
    mocks.listConnections.mockResolvedValue([]);

    render(<SourcesWorkspace />);

    const link = await screen.findByRole("link", { name: "Set up GitHub in Integrations" });
    expect(link).toHaveAttribute("href", "/integrations");
    expect(mocks.listWritingBlocks).not.toHaveBeenCalled();
  });
});
