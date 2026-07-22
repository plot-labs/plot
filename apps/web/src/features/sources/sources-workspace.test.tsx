// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  listReferences: vi.fn(),
}));

vi.mock("@/lib/api-client", () => ({
  plotApiClient: {
    listGenerationReferences: mocks.listReferences,
  },
}));

import { SourcesWorkspace } from "./sources-workspace";

describe("SourcesWorkspace", () => {
  beforeEach(() => {
    mocks.listReferences.mockReset();
  });

  it("shows only collected Writing Blocks and their original details", async () => {
    mocks.listReferences.mockResolvedValue([{
      id: "block-1",
      sourceScopeId: "scope-1",
      provider: "GITHUB",
      sourceKind: "PULL_REQUEST",
      sourceLabel: "Clarify recovery",
      repositoryLabel: "acme/plot",
      title: "Clarify recovery",
      body: "Recovery copy and retry behavior.",
      originalUrl: "https://github.com/acme/plot/pull/184",
      sourceCreatedAt: "2026-07-03T00:00:00Z",
    }]);

    render(<SourcesWorkspace />);
    fireEvent.click(await screen.findByRole("option", { name: /Clarify recovery/ }));

    expect(screen.getByRole("heading", { name: "Clarify recovery" })).toBeVisible();
    expect(screen.getByText("Recovery copy and retry behavior.")).toBeVisible();
    expect(screen.getByRole("link", { name: /View on GitHub/ })).toHaveAttribute("href", "https://github.com/acme/plot/pull/184");
    expect(screen.queryByRole("button", { name: /Connect GitHub|Import last 30 days|Refresh/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/Import complete:/)).not.toBeInTheDocument();
  });

  it("links to Integrations without starting an import when no blocks exist", async () => {
    mocks.listReferences.mockResolvedValue([]);

    render(<SourcesWorkspace />);

    const link = await screen.findByRole("link", { name: "Set up GitHub in Integrations" });
    expect(link).toHaveAttribute("href", "/integrations");
    expect(mocks.listReferences).toHaveBeenCalledTimes(1);
  });
});
