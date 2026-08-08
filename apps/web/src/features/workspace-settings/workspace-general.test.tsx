// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getWorkspace: vi.fn(),
  updateWorkspace: vi.fn(),
}));

vi.mock("@/lib/api-client", () => ({
  getSelectedWorkspaceId: () => "workspace-1",
  plotApiClient: mocks,
}));

import { WorkspaceGeneral } from "./workspace-general";

describe("WorkspaceGeneral", () => {
  beforeEach(() => {
    mocks.getWorkspace.mockReset().mockResolvedValue({
      id: "workspace-1",
      name: "Personal",
      slug: "personal",
      status: "ACTIVE",
      logoUrl: null,
      role: "OWNER",
    });
    mocks.updateWorkspace.mockReset().mockResolvedValue({
      id: "workspace-1",
      name: "Product",
      slug: "personal",
      status: "ACTIVE",
      logoUrl: null,
      role: "OWNER",
    });
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
    }));
    expect(await screen.findByRole("status")).toHaveTextContent("Workspace settings saved.");
  });

  it("reloads the profile when the selected workspace changes", async () => {
    render(<WorkspaceGeneral />);

    await screen.findByRole("textbox", { name: "Workspace name" });
    window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: "workspace-2" } }));

    await waitFor(() => expect(mocks.getWorkspace).toHaveBeenCalledTimes(2));
  });
});
