// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  search: "",
  replace: vi.fn(),
  getWorkspace: vi.fn(),
  listConnections: vi.fn(),
  listRepositories: vi.fn(),
  connectRepository: vi.fn(),
  importRepository: vi.fn(),
  createInstallationRequest: vi.fn(),
  syncGitHubInstallation: vi.fn(),
  disconnectRepository: vi.fn(),
  removeConnection: vi.fn(),
  listAvailableInstallations: vi.fn(),
  connectInstallation: vi.fn(),
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
      listGitHubRepositories: mocks.listRepositories,
      connectGitHubRepository: mocks.connectRepository,
      importGitHubRepository: mocks.importRepository,
      createGitHubInstallationRequest: mocks.createInstallationRequest,
      syncGitHubInstallation: mocks.syncGitHubInstallation,
      disconnectGitHubRepository: mocks.disconnectRepository,
      removeGitHubConnection: mocks.removeConnection,
      listGitHubAvailableInstallations: mocks.listAvailableInstallations,
      connectGitHubInstallation: mocks.connectInstallation,
    },
  };
});

import { PlotApiError } from "@/lib/api-client";
import { GitHubIntegrationWorkspace } from "./github-integration-workspace";

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

describe("GitHubIntegrationWorkspace", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  beforeEach(() => {
    mocks.search = "";
    mocks.replace.mockReset();
    mocks.getWorkspace.mockReset().mockResolvedValue({ id: "workspace-1", role: "OWNER" });
    mocks.listConnections.mockReset().mockResolvedValue([]);
    mocks.listRepositories.mockReset().mockResolvedValue([]);
    mocks.connectRepository.mockReset().mockResolvedValue(repository);
    mocks.importRepository.mockReset().mockResolvedValue({ id: "import-1", sourceScopeId: "scope-1", status: "COMPLETED" });
    mocks.createInstallationRequest.mockReset();
    mocks.syncGitHubInstallation.mockReset().mockRejectedValue(
      new PlotApiError(404, "GITHUB_INSTALLATION_NOT_FOUND", "No Plot GitHub App installation was found for your account"),
    );
    mocks.disconnectRepository.mockReset().mockResolvedValue(undefined);
    mocks.removeConnection.mockReset().mockResolvedValue(undefined);
    mocks.listAvailableInstallations.mockReset().mockResolvedValue([]);
    mocks.connectInstallation.mockReset();
  });

  it("connects another GitHub account where the app is installed", async () => {
    const acmeOrg = { ...connection, id: "connection-2", installationId: 102, accountLogin: "acme-org" };
    mocks.listConnections
      .mockResolvedValueOnce([{ ...connection, accountLogin: "acme", repositories: [repository] }])
      .mockResolvedValue([{ ...connection, accountLogin: "acme", repositories: [repository] }, acmeOrg]);
    mocks.listAvailableInstallations.mockResolvedValue([
      { installationId: 77, accountLogin: "acme", accountType: "User", connected: true, connectable: true },
      { installationId: 102, accountLogin: "acme-org", accountType: "Organization", connected: false, connectable: true },
      { installationId: 103, accountLogin: "someone-else", accountType: "User", connected: false, connectable: false },
    ]);
    mocks.connectInstallation.mockResolvedValue({ connectionId: "connection-2", installationId: 102, repositories: [] });

    render(<GitHubIntegrationWorkspace />);
    await openAddAccountDialog();

    const dialog = await screen.findByRole("dialog", { name: "Add GitHub account" });
    expect(await within(dialog).findByText("Only someone-else can connect this personal account.")).toBeVisible();
    expect(within(dialog).queryByRole("button", { name: "Connect acme" })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Connect someone-else" })).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Connect acme-org" }));

    await waitFor(() => expect(mocks.connectInstallation).toHaveBeenCalledWith(102, expect.objectContaining({ signal: expect.any(AbortSignal) })));
    expect(await screen.findByText("Connected acme-org.")).toBeVisible();
    expect(await screen.findByRole("article", { name: "acme-org GitHub account" })).toBeVisible();
    expect(await screen.findByRole("dialog", { name: "Add repositories" })).toBeVisible();
  });

  it("explains why an organization could not be connected", async () => {
    mocks.listConnections.mockResolvedValue([{ ...connection, accountLogin: "acme" }]);
    mocks.listAvailableInstallations.mockResolvedValue([
      { installationId: 102, accountLogin: "acme-org", accountType: "Organization", connected: false, connectable: true },
    ]);
    mocks.connectInstallation.mockRejectedValue(new PlotApiError(401, "GITHUB_REAUTH_REQUIRED", "refresh"));

    render(<GitHubIntegrationWorkspace />);
    await openAddAccountDialog();
    fireEvent.click(await screen.findByRole("button", { name: "Connect acme-org" }));

    const dialog = screen.getByRole("dialog", { name: "Add GitHub account" });
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Plot could not confirm you are an owner of acme-org.");
  });

  it("asks to reconnect the GitHub account when none is linked", async () => {
    mocks.listConnections.mockResolvedValue([{ ...connection, accountLogin: "acme" }]);
    mocks.listAvailableInstallations.mockRejectedValue(new PlotApiError(400, "GITHUB_ACCOUNT_NOT_LINKED", "not linked"));

    render(<GitHubIntegrationWorkspace />);
    await openAddAccountDialog();

    expect(await screen.findByRole("button", { name: "Reconnect GitHub account" })).toBeVisible();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("links back to the Connections catalog", async () => {
    render(<GitHubIntegrationWorkspace />);

    expect(screen.getByRole("heading", { name: "GitHub", level: 1 })).toBeVisible();
    expect(screen.getByRole("link", { name: "Connections" })).toHaveAttribute("href", "/settings/integrations");
    expect(await screen.findByText("No GitHub account connected")).toBeVisible();
  });

  it("opens the install dialog when the catalog asks to connect", async () => {
    mocks.search = "connect=1";

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByRole("dialog", { name: "Connect GitHub" })).toBeVisible();
    expect(mocks.replace).toHaveBeenCalledWith("/settings/integrations/github");
  });

  it("syncs an existing GitHub App installation before redirecting to GitHub", async () => {
    mocks.syncGitHubInstallation.mockResolvedValue({
      connectionId: "connection-1",
      installationId: 77,
      repositories: [],
    });
    mocks.listConnections
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([connection]);

    render(<GitHubIntegrationWorkspace />);
    await startGitHubInstall();

    await waitFor(() => expect(mocks.syncGitHubInstallation).toHaveBeenCalledTimes(1));
    expect(mocks.createInstallationRequest).not.toHaveBeenCalled();
    expect(await screen.findByText("Connected")).toBeVisible();
    expect(await screen.findByText("GitHub App connected.")).toBeVisible();
    expect(await screen.findByRole("dialog", { name: "Add repositories" })).toBeVisible();
  });

  it("explains read-only access before sending the owner to GitHub", async () => {
    render(<GitHubIntegrationWorkspace />);
    fireEvent.click((await screen.findAllByRole("button", { name: "Connect GitHub" }))[0]);

    const dialog = screen.getByRole("dialog", { name: "Connect GitHub" });
    expect(dialog).toHaveTextContent("Read-only permissions");
    expect(dialog).toHaveTextContent("Releases and tags");
    expect(mocks.syncGitHubInstallation).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("starts GitHub App installation from the empty owner state", async () => {
    mocks.createInstallationRequest.mockReturnValue(new Promise(() => undefined));

    render(<GitHubIntegrationWorkspace />);
    const install = await startGitHubInstall();

    await waitFor(() => expect(mocks.syncGitHubInstallation).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(mocks.createInstallationRequest).toHaveBeenCalledTimes(1));
    expect(install).toBeDisabled();
    expect(install).toHaveTextContent("Redirecting…");
  });

  it("shows reconnect button when sync returns ACCESS_DENIED", async () => {
    mocks.syncGitHubInstallation.mockRejectedValue(
      new PlotApiError(403, "GITHUB_ACCESS_DENIED", "GitHub denied access (request A6F0:1234)")
    );

    render(<GitHubIntegrationWorkspace />);
    await startGitHubInstall();

    await waitFor(() => expect(mocks.syncGitHubInstallation).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole("alert")).toHaveTextContent("GitHub access was revoked. Reconnect GitHub, then retry.");
    expect(await screen.findByRole("button", { name: "Reconnect GitHub account" })).toBeVisible();
    expect(mocks.createInstallationRequest).not.toHaveBeenCalled();
  });

  it("shows reconnect button when sync returns GITHUB_REAUTH_REQUIRED", async () => {
    mocks.syncGitHubInstallation.mockRejectedValue(
      new PlotApiError(401, "GITHUB_REAUTH_REQUIRED", "GitHub re-authentication is required (request B7G1:5678)")
    );

    render(<GitHubIntegrationWorkspace />);
    await startGitHubInstall();

    await waitFor(() => expect(mocks.syncGitHubInstallation).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole("alert")).toHaveTextContent("GitHub authorization must be refreshed. Connect GitHub again to grant organization access.");
    expect(await screen.findByRole("button", { name: "Reconnect GitHub account" })).toBeVisible();
    expect(mocks.createInstallationRequest).not.toHaveBeenCalled();
  });

  it("shows reconnect button when sync returns GITHUB_ACCOUNT_NOT_LINKED", async () => {
    mocks.syncGitHubInstallation.mockRejectedValue(
      new PlotApiError(404, "GITHUB_ACCOUNT_NOT_LINKED", "No linked GitHub account found")
    );

    render(<GitHubIntegrationWorkspace />);
    await startGitHubInstall();

    await waitFor(() => expect(mocks.syncGitHubInstallation).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole("alert")).toHaveTextContent("No linked GitHub account found. Connect a GitHub account first, then retry.");
    expect(await screen.findByRole("button", { name: "Reconnect GitHub account" })).toBeVisible();
    expect(mocks.createInstallationRequest).not.toHaveBeenCalled();
  });

  it("reloads the connection state when the selected workspace changes", async () => {
    mocks.listConnections.mockResolvedValueOnce([]).mockResolvedValueOnce([connection]);

    render(<GitHubIntegrationWorkspace />);
    await screen.findAllByRole("button", { name: "Connect GitHub" });
    window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: "workspace-2" } }));

    expect(await screen.findByText("Connected")).toBeVisible();
    expect(mocks.listConnections).toHaveBeenCalledTimes(2);
  });

  it("keeps the connected state compact after the GitHub installation loads", async () => {
    mocks.listConnections.mockResolvedValue([connection]);

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByText("Connected")).toBeVisible();
    expect(screen.queryByText("Repository access")).not.toBeInTheDocument();
    expect(screen.queryByText("Connect repository")).not.toBeInTheDocument();
    expect(screen.queryByText("Refresh")).not.toBeInTheDocument();
    expect(screen.getByText("Connected")).toBeVisible();
    expect(screen.queryByText("Source activity")).not.toBeInTheDocument();
  });

  it("opens repository selection after returning from the GitHub installation", async () => {
    mocks.search = "githubConnection=connection-1";
    mocks.listConnections.mockResolvedValue([connection]);

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByText("Connected")).toBeVisible();
    expect(mocks.replace).toHaveBeenCalledWith("/settings/integrations/github");
    expect(await screen.findByRole("dialog", { name: "Add repositories" })).toBeVisible();
    await waitFor(() => expect(mocks.listRepositories).toHaveBeenCalledWith("connection-1", expect.objectContaining({ signal: expect.any(AbortSignal) })));
    expect(await screen.findByText("No repositories are granted to the Plot GitHub App yet.")).toBeVisible();
  });

  it("does not reopen repository selection once a repository is followed", async () => {
    mocks.search = "githubConnection=connection-1";
    mocks.listConnections.mockResolvedValue([{ ...connection, repositories: [repository] }]);

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByRole("link", { name: "acme/plot" })).toHaveAttribute("href", "https://github.com/acme/plot");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("adds and imports several granted repositories at once", async () => {
    const docs = { ...repository, id: null, status: null, externalRepositoryId: 43, name: "docs", displayName: "acme/docs", visibility: "PRIVATE" };
    mocks.listConnections.mockResolvedValue([connection]);
    mocks.listRepositories.mockResolvedValue([{ ...repository, id: null, status: null }, docs]);
    mocks.connectRepository
      .mockResolvedValueOnce(repository)
      .mockResolvedValueOnce({ ...docs, id: "scope-2", status: "ACTIVE" });

    render(<GitHubIntegrationWorkspace />);
    expect(await screen.findByText("Add a repository so Plot can follow its releases and changes.")).toBeVisible();
    fireEvent.click(screen.getAllByRole("button", { name: "Add repositories" })[0]);

    const dialog = await screen.findByRole("dialog", { name: "Add repositories" });
    fireEvent.click(await screen.findByRole("checkbox", { name: "acme/plot" }));
    fireEvent.click(screen.getByRole("checkbox", { name: /acme\/docs/ }));
    expect(dialog).toHaveTextContent("Private repository");
    fireEvent.click(screen.getByRole("button", { name: "Add 2 repositories" }));

    await waitFor(() => expect(mocks.importRepository).toHaveBeenCalledTimes(2));
    expect(mocks.connectRepository).toHaveBeenNthCalledWith(1, "connection-1", 42, expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(mocks.connectRepository).toHaveBeenNthCalledWith(2, "connection-1", 43, expect.objectContaining({ signal: expect.any(AbortSignal) }));
    const [, window, options] = mocks.importRepository.mock.calls[0] as [string, { from: string; to: string }, { signal: AbortSignal }];
    expect(new Date(window.to).getTime() - new Date(window.from).getTime()).toBe(30 * 24 * 60 * 60 * 1_000);
    expect(options.signal).toBeInstanceOf(AbortSignal);
    expect(await screen.findByText("2 repositories added. Plot is importing the last 30 days of activity.")).toBeVisible();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Disconnect acme/plot" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Disconnect acme/docs" })).toBeVisible();
  });

  it("moves to reconnect when GitHub no longer recognizes the installation", async () => {
    mocks.listConnections.mockResolvedValue([connection]);
    mocks.listRepositories.mockRejectedValue(new PlotApiError(502, "GITHUB_NOT_FOUND", "GitHub resource was not found"));
    mocks.createInstallationRequest.mockReturnValue(new Promise(() => undefined));

    render(<GitHubIntegrationWorkspace />);
    fireEvent.click((await screen.findAllByRole("button", { name: "Add repositories" }))[0]);

    expect(await screen.findByRole("alert")).toHaveTextContent("The previous GitHub installation was replaced or removed.");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByText("Needs attention")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Reconnect GitHub" }));
    await waitFor(() => expect(mocks.syncGitHubInstallation).toHaveBeenCalledTimes(1));
  });

  it("names the GitHub account even before any repository is followed", async () => {
    mocks.listConnections.mockResolvedValue([{ ...connection, accountLogin: "acme" }]);

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByText("acme")).toBeVisible();
    expect(screen.queryByText("Installation 77")).not.toBeInTheDocument();
    const avatar = screen.getByRole("article", { name: "acme GitHub account" }).querySelector("img");
    expect(avatar?.getAttribute("src")).toBe("https://github.com/acme.png?size=72");
  });

  it("falls back to an initial when the GitHub avatar cannot load", async () => {
    mocks.listConnections.mockResolvedValue([{ ...connection, accountLogin: "acme" }]);

    render(<GitHubIntegrationWorkspace />);

    const card = await screen.findByRole("article", { name: "acme GitHub account" });
    fireEvent.error(card.querySelector("img")!);
    expect(card.querySelector("img")).toBeNull();
    expect(within(card).getByText("A")).toBeInTheDocument();
  });

  it("uses an initial when the installation account is unknown", async () => {
    mocks.listConnections.mockResolvedValue([connection]);

    render(<GitHubIntegrationWorkspace />);

    const card = await screen.findByRole("article", { name: "Installation 77 GitHub account" });
    expect(card.querySelector("img")).toBeNull();
    expect(within(card).getByText("I")).toBeInTheDocument();
  });

  it("removes an installation GitHub no longer accepts", async () => {
    mocks.listConnections.mockResolvedValue([
      { ...connection, id: "connection-2", installationId: 88, accountLogin: "acme", repositories: [repository] },
      { ...connection, status: "NEEDS_REAUTH", statusReason: "AUTH_EXPIRED", accountLogin: "acme" },
    ]);

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByText("Needs attention")).toBeVisible();
    expect(screen.getByText("GitHub no longer accepts this installation")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Remove acme connection" }));

    await waitFor(() => expect(mocks.removeConnection).toHaveBeenCalledWith(
      "connection-1",
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ));
    expect(await screen.findByText("Removed the unavailable acme connection.")).toBeVisible();
    expect(screen.getByText("Connected")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Reconnect GitHub" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Disconnect acme/plot" })).toBeVisible();
  });

  it("keeps an active connection without a remove action", async () => {
    mocks.listConnections.mockResolvedValue([{ ...connection, accountLogin: "acme" }]);

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByText("acme")).toBeVisible();
    expect(screen.queryByRole("button", { name: /^Remove/ })).not.toBeInTheDocument();
  });

  it("filters granted repositories and keeps followed ones locked", async () => {
    mocks.listConnections.mockResolvedValue([{ ...connection, repositories: [repository] }]);
    mocks.listRepositories.mockResolvedValue([
      repository,
      { ...repository, id: null, status: null, externalRepositoryId: 43, name: "docs", displayName: "acme/docs" },
    ]);

    render(<GitHubIntegrationWorkspace />);
    fireEvent.click((await screen.findAllByRole("button", { name: "Add repositories" }))[0]);

    const followed = await screen.findByRole("checkbox", { name: /acme\/plot/ });
    expect(followed).toBeChecked();
    expect(followed).toBeDisabled();
    const dialog = screen.getByRole("dialog", { name: "Add repositories" });
    expect(within(dialog).getByRole("button", { name: "Add repositories" })).toBeDisabled();

    fireEvent.change(screen.getByRole("searchbox", { name: "Search repositories" }), { target: { value: "docs" } });
    expect(screen.queryByRole("checkbox", { name: /acme\/plot/ })).not.toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: /acme\/docs/ })).toBeVisible();
  });

  it("disconnects one followed GitHub repository", async () => {
    mocks.listConnections.mockResolvedValue([{ ...connection, repositories: [repository] }]);

    render(<GitHubIntegrationWorkspace />);
    fireEvent.click(await screen.findByRole("button", { name: "Disconnect acme/plot" }));

    await waitFor(() => expect(mocks.disconnectRepository).toHaveBeenCalledWith(
      "scope-1",
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ));
    expect(await screen.findByText("acme/plot disconnected. Plot no longer follows its activity.")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Disconnect acme/plot" })).not.toBeInTheDocument();
    expect(screen.getByText("Add a repository so Plot can follow its releases and changes.")).toBeVisible();
  });

  it("explains repositories GitHub no longer grants", async () => {
    mocks.listConnections.mockResolvedValue([{
      ...connection,
      repositories: [{ ...repository, status: "DISABLED", statusReason: "GRANT_REMOVED" }],
    }]);

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByText("Access removed on GitHub")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Disconnect acme/plot" })).not.toBeInTheDocument();
  });

  it("offers a retry when loading GitHub connections fails", async () => {
    mocks.listConnections
      .mockRejectedValueOnce(new PlotApiError(429, "GITHUB_RATE_LIMITED", "limited (request provider-id)"))
      .mockResolvedValueOnce([connection]);

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByRole("alert")).toHaveTextContent("GitHub rate limit reached");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect((await screen.findAllByText("Connected")).length).toBeGreaterThan(0);
    expect(mocks.listConnections).toHaveBeenCalledTimes(2);
  });

  it("keeps the connect action when GitHub connections cannot load", async () => {
    mocks.listConnections.mockRejectedValue(new PlotApiError(503, "GITHUB_NOT_CONFIGURED", "GitHub is disabled"));

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByRole("alert")).toHaveTextContent("GitHub is not configured for this environment.");
    expect(screen.getAllByRole("button", { name: "Connect GitHub" })[0]).toBeVisible();
  });

  it("offers reconnect for an inactive GitHub installation", async () => {
    mocks.listConnections.mockResolvedValue([{
      ...connection,
      status: "ERROR",
      statusReason: "AUTH_EXPIRED",
      repositories: [repository],
    }]);
    mocks.createInstallationRequest.mockReturnValue(new Promise(() => undefined));

    render(<GitHubIntegrationWorkspace />);
    const reconnect = await screen.findByRole("button", { name: "Reconnect GitHub" });
    fireEvent.click(reconnect);

    await waitFor(() => expect(mocks.createInstallationRequest).toHaveBeenCalledTimes(1));
    expect(reconnect).toBeDisabled();
    expect(screen.getByText("Needs attention")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Disconnect acme/plot" })).not.toBeInTheDocument();
  });

  it("prioritizes reconnect when the installation needs reauthorization", async () => {
    mocks.listConnections.mockResolvedValue([{
      ...connection,
      status: "NEEDS_REAUTH",
      statusReason: "AUTH_EXPIRED",
      repositories: [{ ...repository, status: "DISABLED", statusReason: "USER_DISCONNECTED" }],
    }]);

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByRole("button", { name: "Reconnect GitHub" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Connect GitHub" })).not.toBeInTheDocument();
    expect(screen.queryByText("Disconnected")).not.toBeInTheDocument();
  });

  it("offers to install again after the GitHub App was uninstalled", async () => {
    mocks.listConnections.mockResolvedValue([{
      ...connection,
      status: "DISABLED",
      statusReason: "INSTALLATION_UNINSTALLED",
    }]);

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByText("GitHub was disconnected")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Reconnect GitHub" })).not.toBeInTheDocument();
    expect(screen.queryByRole("article", { name: /GitHub account/ })).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Connect GitHub" })).toHaveLength(2);
  });

  it("shows connection state without owner controls for a non-owner", async () => {
    mocks.getWorkspace.mockResolvedValue({ id: "workspace-1", role: "MEMBER" });
    mocks.listConnections.mockResolvedValue([connection]);

    render(<GitHubIntegrationWorkspace />);

    expect(await screen.findByText("Only the workspace owner can add or disconnect repositories.")).toBeVisible();
    expect(screen.getByText("GitHub is connected for this workspace")).toBeVisible();
    expect(screen.queryByRole("button", { name: /Connect GitHub|Disconnect|Add repositories/ })).not.toBeInTheDocument();
  });
});

async function startGitHubInstall() {
  fireEvent.click((await screen.findAllByRole("button", { name: "Connect GitHub" }))[0]);
  const install = screen.getByRole("button", { name: "Install on GitHub" });
  fireEvent.click(install);
  return install;
}

async function openAddAccountDialog() {
  fireEvent.click((await screen.findAllByRole("button", { name: "Add repositories" }))[0]);
  const dialog = await screen.findByRole("dialog", { name: "Add repositories" });
  fireEvent.click(within(dialog).getByRole("button", { name: "GitHub account" }));
  fireEvent.click(within(dialog).getByRole("menuitem", { name: "Add GitHub account" }));
}
