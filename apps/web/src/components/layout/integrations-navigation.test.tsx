// @vitest-environment jsdom

import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const sidebarMocks = vi.hoisted(() => ({
  listSessions: vi.fn(),
  pathname: "/settings/integrations",
}));

vi.mock("next/navigation", () => ({
  usePathname: () => sidebarMocks.pathname,
}));

vi.mock("@/lib/api-client", () => ({
  getProductShellData: () => ({
    workspace: { id: "workspace-1", name: "Personal" },
  }),
  plotApiClient: { listSessions: sidebarMocks.listSessions },
}));

import { ProductSidebar } from "./product-sidebar";

describe("Workspace settings navigation", () => {
  beforeEach(() => {
    sidebarMocks.pathname = "/settings/integrations";
    window.localStorage.clear();
    sidebarMocks.listSessions.mockReset();
    sidebarMocks.listSessions.mockResolvedValue([]);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({
      user: { id: "user-1", email: "owner@example.com", displayName: "Owner" },
      workspaces: [{ id: "workspace-1", name: "Personal", slug: "personal", role: "OWNER" }],
      defaultWorkspaceId: "workspace-1",
    })));
  });

  it("restores product navigation outside workspace settings", async () => {
    sidebarMocks.pathname = "/artifacts";
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    const productNavigation = screen.getByRole("navigation", { name: "Product sidebar navigation" });
    expect(within(productNavigation).getByRole("link", { name: "Artifacts" })).toHaveAttribute("aria-current", "page");
    expect(screen.queryByRole("navigation", { name: "Workspace settings navigation" })).not.toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /Personal/ })).toBeVisible();
  });

  it("uses the existing sidebar for workspace settings navigation", async () => {
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    expect(screen.getByRole("link", { name: "Back to app" })).toHaveAttribute("href", "/artifacts");
    expect(screen.getByRole("searchbox", { name: "Search workspace settings" })).toHaveAttribute("placeholder", "Search...");
    expect(screen.queryByRole("link", { name: "Plot home" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Personal/ })).not.toBeInTheDocument();

    const settingsNavigation = screen.getByRole("navigation", { name: "Workspace settings navigation" });
    expect(within(settingsNavigation).getByRole("link", { name: "Integrations" })).toHaveAttribute("href", "/settings/integrations");
    expect(within(settingsNavigation).getByRole("link", { name: "Integrations" })).toHaveAttribute("aria-current", "page");
    expect(within(settingsNavigation).queryByRole("link", { name: "Artifacts" })).not.toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Product sidebar navigation" })).not.toBeInTheDocument();

    expect(screen.queryByRole("link", { name: "Sources" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "New session" })).not.toBeInTheDocument();
    expect(screen.queryByText("Sessions", { selector: "div" })).not.toBeInTheDocument();
  });

  it("filters workspace settings navigation", () => {
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    const search = screen.getByRole("searchbox", { name: "Search workspace settings" });
    fireEvent.change(search, { target: { value: "billing" } });
    expect(screen.queryByRole("link", { name: "Integrations" })).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("No settings found");

    fireEvent.change(search, { target: { value: "integ" } });
    expect(screen.getByRole("link", { name: "Integrations" })).toBeVisible();
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
    sidebarMocks.pathname = "/artifacts";
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    const trigger = await screen.findByRole("button", { name: /Personal/ });
    expect(trigger).toHaveClass("h-9");
    fireEvent.click(trigger);

    const option = screen.getByRole("menuitemradio", { name: "Personal workspace" });
    expect(option).toHaveClass("h-9");
    expect(option).toHaveAttribute("aria-checked", "true");
    expect(screen.queryByText("OWNER")).not.toBeInTheDocument();
  });

  it("keeps workspace switching when multiple workspaces are available", async () => {
    sidebarMocks.pathname = "/artifacts";
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
