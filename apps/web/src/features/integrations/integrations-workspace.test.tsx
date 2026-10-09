// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  search: "",
  replace: vi.fn(),
  listConnections: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mocks.replace }),
  useSearchParams: () => new URLSearchParams(mocks.search),
}));

vi.mock("@/lib/api-client", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api-client")>("@/lib/api-client");
  return {
    ...actual,
    getSelectedWorkspaceId: () => "workspace-1",
    plotApiClient: {
      listGitHubConnections: mocks.listConnections,
    },
  };
});

import { PlotApiError } from "@/lib/api-client";
import { IntegrationsWorkspace } from "./integrations-workspace";

const repository = {
  id: "scope-1",
  externalRepositoryId: 42,
  owner: "acme",
  name: "plot",
  displayName: "acme/plot",
  url: "https://github.com/acme/plot",
  status: "ACTIVE",
  monitoring: null,
};

function githubCard() {
  return screen.getByRole("link", { name: "GitHub" }).closest("article") as HTMLElement;
}

describe("IntegrationsWorkspace", () => {
  beforeEach(() => {
    mocks.search = "";
    mocks.replace.mockReset();
    mocks.listConnections.mockReset().mockResolvedValue([]);
  });

  it("presents a searchable catalog of equal provider cards", async () => {
    render(<IntegrationsWorkspace />);

    for (const name of ["GitHub", "Linear", "Slack", "Notion", "Figma"]) {
      expect(screen.getByRole("img", { name })).toBeVisible();
    }
    await waitFor(() => expect(mocks.listConnections).toHaveBeenCalled());

    fireEvent.change(screen.getByRole("searchbox", { name: "Search connections" }), { target: { value: "release" } });

    expect(screen.getByRole("img", { name: "GitHub" })).toBeVisible();
    expect(screen.queryByRole("img", { name: "Slack" })).not.toBeInTheDocument();
  });

  it("does not expose unavailable connections as connectable", () => {
    render(<IntegrationsWorkspace />);

    expect(screen.getAllByText("Coming soon")).toHaveLength(4);
    expect(screen.queryByRole("link", { name: /Linear|Slack|Notion|Figma/ })).not.toBeInTheDocument();
  });

  it("sends a new workspace to connect GitHub", async () => {
    render(<IntegrationsWorkspace />);

    const card = githubCard();
    expect(await within(card).findByRole("link", { name: "Connect" })).toHaveAttribute("href", "/settings/integrations/github?connect=1");
    expect(within(card).getByRole("link", { name: "GitHub" })).toHaveAttribute("href", "/settings/integrations/github");
  });

  it("summarizes followed repositories for a connected workspace", async () => {
    mocks.listConnections.mockResolvedValue([{ id: "connection-1", installationId: 77, status: "ACTIVE", repositories: [repository] }]);

    render(<IntegrationsWorkspace />);

    const card = githubCard();
    expect(await within(card).findByRole("link", { name: "Manage" })).toHaveAttribute("href", "/settings/integrations/github");
    expect(within(card).getByLabelText("1 repository followed")).toHaveTextContent("1");
  });

  it("flags a connection that needs attention", async () => {
    mocks.listConnections.mockResolvedValue([{ id: "connection-1", installationId: 77, status: "NEEDS_REAUTH", repositories: [] }]);

    render(<IntegrationsWorkspace />);

    expect(await within(githubCard()).findByText("Needs attention")).toBeVisible();
  });

  it("still offers to connect when GitHub status cannot load", async () => {
    mocks.listConnections.mockRejectedValue(new PlotApiError(503, "GITHUB_NOT_CONFIGURED", "GitHub is disabled"));

    render(<IntegrationsWorkspace />);

    expect(await within(githubCard()).findByRole("link", { name: "Connect" })).toBeVisible();
  });

  it("forwards GitHub callbacks to the GitHub page", () => {
    mocks.search = "githubConnection=connection-1";

    render(<IntegrationsWorkspace />);

    expect(mocks.replace).toHaveBeenCalledWith("/settings/integrations/github?githubConnection=connection-1");
    expect(mocks.listConnections).not.toHaveBeenCalled();
  });
});
