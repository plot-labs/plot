// @vitest-environment jsdom

import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const sidebarMocks = vi.hoisted(() => ({ listSessions: vi.fn() }));

vi.mock("next/navigation", () => ({
  usePathname: () => "/integrations",
}));

vi.mock("@/lib/api-client", () => ({
  getProductShellData: () => ({
    workspace: { id: "workspace-1", name: "Personal" },
  }),
  plotApiClient: { listSessions: sidebarMocks.listSessions },
}));

import WorkspaceSettingsPage from "@/app/(app)/workspaces/[workspaceId]/settings/page";
import { ProductSidebar } from "./product-sidebar";

describe("Integrations navigation", () => {
  beforeEach(() => {
    window.localStorage.clear();
    sidebarMocks.listSessions.mockReset();
    sidebarMocks.listSessions.mockResolvedValue([]);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({
      user: { id: "user-1", email: "owner@example.com", displayName: "Owner" },
      workspaces: [{ id: "workspace-1", name: "Personal", slug: "personal", role: "OWNER" }],
      defaultWorkspaceId: "workspace-1",
    })));
  });

  it("links the selected sidebar item to Integrations", () => {
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    const link = screen.getByRole("link", { name: "Integrations" });
    expect(link).toHaveAttribute("href", "/integrations");
    expect(link).toHaveAttribute("aria-current", "page");
  });

  it("routes workspace GitHub management to Integrations", () => {
    render(<WorkspaceSettingsPage />);

    expect(screen.getByRole("link", { name: "Manage GitHub in Integrations" })).toHaveAttribute("href", "/integrations");
  });

  it("uses actual sessions and their latest generation in sidebar links", async () => {
    sidebarMocks.listSessions.mockResolvedValue([{
      id: "session-1", title: "Release notes", status: "OPEN", latestGenerationId: "run-1",
      lastActivityAt: null, createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z",
    }]);
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    expect(await screen.findByRole("link", { name: "Release notes" })).toHaveAttribute(
      "href",
      "/sessions?session=session-1&generation=run-1",
    );
  });
});
