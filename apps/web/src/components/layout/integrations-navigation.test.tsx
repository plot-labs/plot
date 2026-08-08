// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
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
    expect(screen.getByRole("link", { name: "Artifacts" })).toHaveAttribute("href", "/artifacts");
    expect(screen.queryByRole("link", { name: "Sources" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "New session" })).not.toBeInTheDocument();
    expect(screen.queryByText("Sessions", { selector: "div" })).not.toBeInTheDocument();
  });

  it("does not load or render session history", async () => {
    sidebarMocks.listSessions.mockResolvedValue([{
      id: "session-1", title: "Release notes", status: "OPEN", latestGenerationId: "run-1",
      lastActivityAt: null, createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z",
    }]);
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    await Promise.resolve();
    expect(sidebarMocks.listSessions).not.toHaveBeenCalled();
    expect(screen.queryByRole("link", { name: "Release notes" })).not.toBeInTheDocument();
  });

  it("renders a compact selector when only one workspace is available", async () => {
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    const trigger = await screen.findByRole("button", { name: /Personal/ });
    fireEvent.click(trigger);

    const option = screen.getByRole("menuitemradio", { name: "Personal workspace" });
    expect(option).toHaveClass("h-9");
    expect(option).toHaveAttribute("aria-checked", "true");
    expect(screen.queryByText("OWNER")).not.toBeInTheDocument();
  });

  it("keeps workspace switching when multiple workspaces are available", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(Response.json({
      user: { id: "user-1", email: "owner@example.com", displayName: "Owner" },
      workspaces: [
        { id: "workspace-1", name: "Personal", slug: "personal", role: "OWNER" },
        { id: "workspace-2", name: "Product", slug: "product", role: "MEMBER" },
      ],
      defaultWorkspaceId: "workspace-1",
    }));
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    const trigger = await screen.findByRole("button", { name: /Personal/ });
    fireEvent.click(trigger);

    expect(screen.getAllByRole("menuitemradio")).toHaveLength(2);
    expect(screen.getByRole("menuitemradio", { name: "Personal workspace" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("menuitemradio", { name: "Product workspace" })).toHaveAttribute("aria-checked", "false");
  });
});
