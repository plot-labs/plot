// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  search: "",
  replace: vi.fn(),
  getWorkspace: vi.fn(),
  listConnections: vi.fn(),
  createInstallationRequest: vi.fn(),
  disconnectRepository: vi.fn(),
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
      getWorkspace: mocks.getWorkspace,
      listGitHubConnections: mocks.listConnections,
      createGitHubInstallationRequest: mocks.createInstallationRequest,
      disconnectGitHubRepository: mocks.disconnectRepository,
    },
  };
});

import { PlotApiError } from "@/lib/api-client";
import { IntegrationsWorkspace } from "./integrations-workspace";

const connection = {
  id: "connection-1",
  installationId: 77,
  status: "ACTIVE",
  repositories: [],
};

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

describe("IntegrationsWorkspace", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  beforeEach(() => {
    mocks.search = "";
    mocks.replace.mockReset();
    mocks.getWorkspace.mockReset().mockResolvedValue({ id: "workspace-1", role: "OWNER" });
    mocks.listConnections.mockReset().mockResolvedValue([]);
    mocks.createInstallationRequest.mockReset();
    mocks.disconnectRepository.mockReset().mockResolvedValue(undefined);
  });

  it("presents a searchable source catalog with real provider marks", () => {
    render(<IntegrationsWorkspace />);

    expect(screen.getByRole("img", { name: "GitHub" })).toBeVisible();
    expect(screen.getByRole("img", { name: "Linear" })).toBeVisible();
    expect(screen.getByRole("img", { name: "Slack" })).toBeVisible();
    expect(screen.getByRole("img", { name: "Notion" })).toBeVisible();
    expect(screen.getByRole("img", { name: "Figma" })).toBeVisible();

    fireEvent.change(screen.getByRole("searchbox", { name: "Search integrations" }), {
      target: { value: "slack" },
    });

    expect(screen.getByRole("img", { name: "Slack" })).toBeVisible();
    expect(screen.queryByRole("img", { name: "GitHub" })).not.toBeInTheDocument();
    expect(screen.queryByRole("img", { name: "Linear" })).not.toBeInTheDocument();
    expect(screen.queryByRole("img", { name: "Notion" })).not.toBeInTheDocument();
    expect(screen.queryByRole("img", { name: "Figma" })).not.toBeInTheDocument();
  });

  it("does not expose unavailable integrations as connectable", () => {
    render(<IntegrationsWorkspace />);

    expect(screen.getAllByText("Coming soon")).toHaveLength(4);
    expect(screen.queryByRole("button", { name: /connect linear|connect slack|connect notion|connect figma/i }))
      .not.toBeInTheDocument();
  });

  it("keeps all providers in one catalog without category tabs", () => {
    render(<IntegrationsWorkspace />);

    expect(screen.getByRole("img", { name: "Linear" })).toBeVisible();
    expect(screen.getByRole("img", { name: "Slack" })).toBeVisible();
    expect(screen.queryByRole("tablist", { name: "Integration categories" })).not.toBeInTheDocument();
  });

  it("starts GitHub App installation from the empty owner state", async () => {
    mocks.createInstallationRequest.mockReturnValue(new Promise(() => undefined));

    render(<IntegrationsWorkspace />);
    const connect = await screen.findByRole("button", { name: "Connect GitHub" });
    fireEvent.click(connect);

    await waitFor(() => expect(mocks.createInstallationRequest).toHaveBeenCalledTimes(1));
    expect(connect).toBeDisabled();
  });

  it("reloads the connection state when the selected workspace changes", async () => {
    mocks.listConnections.mockResolvedValueOnce([]).mockResolvedValueOnce([connection]);

    render(<IntegrationsWorkspace />);
    await screen.findByRole("button", { name: "Connect GitHub" });
    window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: "workspace-2" } }));

    expect(await screen.findByText("Connected")).toBeVisible();
    expect(mocks.listConnections).toHaveBeenCalledTimes(2);
  });

  it("keeps the connected state compact after the GitHub installation loads", async () => {
    mocks.listConnections.mockResolvedValue([connection]);

    render(<IntegrationsWorkspace />);

    expect(await screen.findByText("Connected")).toBeVisible();
    expect(screen.queryByText("Repository access")).not.toBeInTheDocument();
    expect(screen.queryByText("Connect repository")).not.toBeInTheDocument();
    expect(screen.queryByText("Refresh")).not.toBeInTheDocument();
    expect(screen.getByText("Connected")).toBeVisible();
    expect(screen.queryByText("Source activity")).not.toBeInTheDocument();
  });

  it("removes callback query parameters without showing repository setup", async () => {
    mocks.search = "githubConnection=connection-1";
    mocks.listConnections.mockResolvedValue([connection]);

    render(<IntegrationsWorkspace />);

    expect(await screen.findByText("Connected")).toBeVisible();
    expect(mocks.replace).toHaveBeenCalledWith("/settings/integrations");
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
  });

  it("disconnects the connected GitHub repository", async () => {
    mocks.listConnections.mockResolvedValue([{ ...connection, repositories: [repository] }]);

    render(<IntegrationsWorkspace />);
    fireEvent.click(await screen.findByRole("button", { name: "Disconnect GitHub" }));

    await waitFor(() => expect(mocks.disconnectRepository).toHaveBeenCalledWith("scope-1"));
    expect(screen.getAllByText("Disconnected").length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: "Disconnect GitHub" })).not.toBeInTheDocument();
    expect(screen.queryByText("Repository access")).not.toBeInTheDocument();
  });

  it("offers a retry when loading GitHub connections fails", async () => {
    mocks.listConnections
      .mockRejectedValueOnce(new PlotApiError(429, "GITHUB_RATE_LIMITED", "limited (request provider-id)"))
      .mockResolvedValueOnce([connection]);

    render(<IntegrationsWorkspace />);

    expect(await screen.findByRole("alert")).toHaveTextContent("GitHub rate limit reached");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect((await screen.findAllByText("Connected")).length).toBeGreaterThan(0);
    expect(mocks.listConnections).toHaveBeenCalledTimes(2);
  });

  it("offers reconnect for an inactive GitHub installation", async () => {
    mocks.listConnections.mockResolvedValue([{
      ...connection,
      status: "ERROR",
      statusReason: "AUTH_EXPIRED",
      repositories: [repository],
    }]);
    mocks.createInstallationRequest.mockReturnValue(new Promise(() => undefined));

    render(<IntegrationsWorkspace />);
    const reconnect = await screen.findByRole("button", { name: "Reconnect GitHub" });
    fireEvent.click(reconnect);

    await waitFor(() => expect(mocks.createInstallationRequest).toHaveBeenCalledTimes(1));
    expect(reconnect).toBeDisabled();
    expect(screen.queryByText("Repository access")).not.toBeInTheDocument();
  });

  it("prioritizes reconnect when the installation needs reauthorization", async () => {
    mocks.listConnections.mockResolvedValue([{
      ...connection,
      status: "NEEDS_REAUTH",
      statusReason: "AUTH_EXPIRED",
      repositories: [{ ...repository, status: "DISABLED", statusReason: "USER_DISCONNECTED" }],
    }]);

    render(<IntegrationsWorkspace />);

    expect(await screen.findByRole("button", { name: "Reconnect GitHub" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Connect GitHub" })).not.toBeInTheDocument();
    expect(screen.queryByText("Disconnected")).not.toBeInTheDocument();
  });

  it("keeps a disconnected GitHub connection compact", async () => {
    mocks.listConnections.mockResolvedValue([{
      ...connection,
      status: "DISABLED",
      statusReason: "INSTALLATION_UNINSTALLED",
    }]);

    render(<IntegrationsWorkspace />);

    expect(await screen.findByText("Disconnected")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Reconnect GitHub" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Disconnect GitHub" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Connect GitHub" })).toBeVisible();
    expect(screen.queryByText("Repository access")).not.toBeInTheDocument();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
  });

  it("shows connection state without owner controls for a non-owner", async () => {
    mocks.getWorkspace.mockResolvedValue({ id: "workspace-1", role: "MEMBER" });
    mocks.listConnections.mockResolvedValue([connection]);

    render(<IntegrationsWorkspace />);

    expect(await screen.findByText("Workspace owner must connect GitHub.")).toBeVisible();
    expect(screen.getByText("GitHub is connected for this workspace")).toBeVisible();
    expect(screen.queryByRole("button", { name: /Connect GitHub|Disconnect GitHub|Import/ })).not.toBeInTheDocument();
  });
});
