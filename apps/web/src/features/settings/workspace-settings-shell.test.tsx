// @vitest-environment jsdom

import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  usePathname: () => "/settings/integrations",
}));

import { WorkspaceSettingsShell } from "./workspace-settings-shell";

describe("WorkspaceSettingsShell", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem("plot.workspaceId", "workspace-2");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({
      workspaces: [
        { id: "workspace-1", name: "Personal" },
        { id: "workspace-2", name: "Product" },
      ],
      defaultWorkspaceId: "workspace-1",
    })));
  });

  it("labels settings with the selected workspace and marks Integrations as current", async () => {
    render(<WorkspaceSettingsShell><div>Settings content</div></WorkspaceSettingsShell>);

    expect(await screen.findByText("Product")).toBeVisible();
    expect(screen.getByRole("navigation", { name: "Workspace settings" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Integrations" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("region", { name: "Product settings" })).toHaveTextContent("Settings content");
  });
});
