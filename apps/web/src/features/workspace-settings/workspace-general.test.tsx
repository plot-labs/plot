// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getWorkspace: vi.fn(),
  updateWorkspace: vi.fn(),
  createSubscriptionCheckout: vi.fn(),
}));

vi.mock("@/lib/api-client", () => ({
  getSelectedWorkspaceId: () => "workspace-1",
  plotApiClient: mocks,
}));

import { WorkspaceGeneral } from "./workspace-general";
import { publicChangelogUrl } from "@/lib/public-changelog-url";

describe("WorkspaceGeneral", () => {
  beforeEach(() => {
    mocks.getWorkspace.mockReset().mockResolvedValue({
      id: "workspace-1",
      name: "Personal",
      slug: "personal",
      status: "ACTIVE",
      logoUrl: null,
      publicCitationsEnabled: true,
      role: "OWNER",
    });
    mocks.updateWorkspace.mockReset().mockResolvedValue({
      id: "workspace-1",
      name: "Product",
      slug: "personal",
      status: "ACTIVE",
      logoUrl: null,
      publicCitationsEnabled: true,
      role: "OWNER",
    });
    mocks.createSubscriptionCheckout.mockReset();
  });

  it("shows the public changelog URL with copy and view actions", async () => {
    render(<WorkspaceGeneral />);

    expect(await screen.findByText(publicChangelogUrl("personal"))).toBeVisible();
    expect(screen.getByText("personal", { selector: ".font-sans" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Copy link" })).toBeVisible();
    expect(screen.getByRole("link", { name: "View live" })).toHaveAttribute("href", publicChangelogUrl("personal"));
  });

  it("loads the workspace profile and saves a renamed workspace", async () => {
    render(<WorkspaceGeneral />);

    const name = await screen.findByRole("textbox", { name: "Workspace name" });
    expect(name).toHaveValue("Personal");
    fireEvent.change(name, { target: { value: "Product" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(mocks.updateWorkspace).toHaveBeenCalledWith("workspace-1", {
      name: "Product",
      logoUrl: "",
      publicCitationsEnabled: true,
    }));
    expect(await screen.findByRole("status")).toHaveTextContent("Workspace settings saved.");
  });

  it("saves the public citation visibility setting", async () => {
    render(<WorkspaceGeneral />);

    const toggle = await screen.findByRole("switch", { name: "Public citations" });
    expect(toggle).toHaveAttribute("aria-checked", "true");
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-checked", "false");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(mocks.updateWorkspace).toHaveBeenCalledWith("workspace-1", {
      name: "Personal",
      logoUrl: "",
      publicCitationsEnabled: false,
    }));
  });

  it("reloads the profile when the selected workspace changes", async () => {
    render(<WorkspaceGeneral />);

    await screen.findByRole("textbox", { name: "Workspace name" });
    window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: "workspace-2" } }));

    await waitFor(() => expect(mocks.getWorkspace).toHaveBeenCalledTimes(2));
  });

  it("offers a subscription checkout to a trial workspace owner", async () => {
    mocks.getWorkspace.mockResolvedValueOnce({
      id: "workspace-1",
      name: "Personal",
      slug: "personal",
      status: "ACTIVE",
      logoUrl: null,
      publicCitationsEnabled: true,
      role: "OWNER",
      plan: "trial",
      entitlementStatus: "trialing",
      accessMode: "full",
      capabilities: { configure: true, write: true, export: true, publish: true, unpublish: true },
      trialEndsAt: "2026-10-01T00:00:00Z",
    });

    render(<WorkspaceGeneral />);

    expect(await screen.findByRole("button", { name: "Subscribe to Founding" })).toBeVisible();
  });
});
