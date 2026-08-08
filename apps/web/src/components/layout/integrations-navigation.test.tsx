// @vitest-environment jsdom

import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const sidebarMocks = vi.hoisted(() => ({
  listSessions: vi.fn(),
  createWorkspace: vi.fn(),
  pathname: "/settings/integrations",
  search: "",
}));

vi.mock("next/navigation", () => ({
  usePathname: () => sidebarMocks.pathname,
  useSearchParams: () => new URLSearchParams(sidebarMocks.search),
}));

vi.mock("@/lib/api-client", () => ({
  getProductShellData: () => ({
    workspace: { id: "workspace-1", name: "Personal" },
  }),
  plotApiClient: { listSessions: sidebarMocks.listSessions, createWorkspace: sidebarMocks.createWorkspace },
}));

import { ProductSidebar } from "./product-sidebar";

describe("Settings navigation", () => {
  beforeEach(() => {
    sidebarMocks.pathname = "/settings/integrations";
    sidebarMocks.search = "";
    window.localStorage.clear();
    sidebarMocks.listSessions.mockReset();
    sidebarMocks.listSessions.mockResolvedValue([]);
    sidebarMocks.createWorkspace.mockReset();
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
    expect(within(productNavigation).getByRole("link", { name: "Chat" })).toHaveAttribute("href", "/chat");
    expect(within(productNavigation).getByRole("link", { name: "Chat" })).not.toHaveAttribute("aria-current", "page");
    expect(within(productNavigation).getByRole("link", { name: "Artifacts" })).toHaveAttribute("aria-current", "page");
    expect(screen.queryByRole("navigation", { name: "Settings navigation" })).not.toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /Personal/ })).toBeVisible();
  });

  it("keeps shared workspace context above workspace settings navigation", async () => {
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    expect(screen.getByRole("link", { name: "Back to app" })).toHaveAttribute("href", "/chat");
    expect(screen.queryByRole("searchbox")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Plot home" })).toHaveAttribute("href", "/chat");
    expect(screen.queryByRole("link", { name: "Workspace settings" })).not.toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /Personal/ })).toBeVisible();

    const settingsNavigation = screen.getByRole("navigation", { name: "Settings navigation" });
    expect(within(settingsNavigation).getByText("Account", { selector: "div" })).toBeInTheDocument();
    expect(within(settingsNavigation).getByText("Workspace", { selector: "div" })).toBeInTheDocument();
    expect(within(settingsNavigation).getByRole("link", { name: "Account" })).toHaveAttribute("href", "/settings/account");
    expect(within(settingsNavigation).getByRole("link", { name: "General" })).toHaveAttribute("href", "/settings/general");
    expect(within(settingsNavigation).getByRole("link", { name: "Integrations" })).toHaveAttribute("href", "/settings/integrations");
    expect(within(settingsNavigation).getByRole("link", { name: "Integrations" })).toHaveAttribute("aria-current", "page");
    expect(within(settingsNavigation).queryByRole("link", { name: "Artifacts" })).not.toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Product sidebar navigation" })).not.toBeInTheDocument();

    expect(screen.queryByRole("link", { name: "Sources" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "New chat" })).not.toBeInTheDocument();
    expect(screen.queryByText("Chats", { selector: "div" })).not.toBeInTheDocument();

    fireEvent.click(await screen.findByRole("button", { name: /Owner.*owner@example.com/ }));
    expect(screen.getByRole("link", { name: "Settings" })).toHaveAttribute("href", "/settings/account");
  });

  it("does not load chat history in workspace settings", async () => {
    sidebarMocks.listSessions.mockResolvedValue([{
      id: "chat-1", title: "Release notes", status: "OPEN", latestGenerationId: "run-1",
      lastActivityAt: null, createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z",
    }]);
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    await Promise.resolve();
    expect(sidebarMocks.listSessions).not.toHaveBeenCalled();
    expect(screen.queryByRole("link", { name: "Release notes" })).not.toBeInTheDocument();
  });

  it("renders recent chats in the product sidebar history", async () => {
    sidebarMocks.pathname = "/artifacts";
    sidebarMocks.listSessions.mockResolvedValue([{
      id: "chat-1", title: "Release notes", status: "OPEN", latestGenerationId: "run-1",
      lastActivityAt: null, createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z",
    }]);
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    expect(await screen.findByText("History")).toBeVisible();
    expect(screen.getByRole("link", { name: "Release notes" })).toHaveAttribute("href", "/chat?chat=chat-1");
  });

  it("selects the history item instead of the Chat tab", async () => {
    sidebarMocks.pathname = "/chat";
    sidebarMocks.search = "chat=chat-1";
    sidebarMocks.listSessions.mockResolvedValue([{
      id: "chat-1", title: "Release notes", status: "OPEN", latestGenerationId: "run-1",
      lastActivityAt: null, createdAt: "2026-07-01T00:00:00Z", updatedAt: "2026-07-01T00:00:00Z",
    }]);
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    const historyItem = await screen.findByRole("link", { name: "Release notes" });
    expect(historyItem).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Chat" })).not.toHaveAttribute("aria-current", "page");
  });

  it("renders a compact selector when only one workspace is available", async () => {
    sidebarMocks.pathname = "/artifacts";
    render(<ProductSidebar theme="light" onThemeChange={() => undefined} onToggleSidebar={() => undefined} />);

    const trigger = await screen.findByRole("button", { name: /Personal/ });
    expect(trigger).toHaveClass("h-9");
    fireEvent.click(trigger);

    const option = screen.getByRole("menuitemradio", { name: "Personal workspace" });
    expect(screen.getByText("Workspaces")).toBeInTheDocument();
    expect(option).toHaveClass("h-9");
    expect(option).toHaveAttribute("aria-checked", "true");
    const createButton = screen.getByRole("button", { name: "Create workspace" });
    expect(createButton).toBeEnabled();
    fireEvent.click(createButton);
    expect(screen.getByRole("dialog", { name: "Create workspace" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Workspace name" })).toBeInTheDocument();
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
