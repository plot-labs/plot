// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  search: "",
  replace: vi.fn(),
  getWorkspace: vi.fn(),
  listConnections: vi.fn(),
  listRepositories: vi.fn(),
  createInstallationRequest: vi.fn(),
  connectRepository: vi.fn(),
  importRepository: vi.fn(),
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
      createGitHubInstallationRequest: mocks.createInstallationRequest,
      connectGitHubRepository: mocks.connectRepository,
      importGitHubRepository: mocks.importRepository,
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
  id: null,
  externalRepositoryId: 42,
  owner: "acme",
  name: "plot",
  displayName: "acme/plot",
  url: "https://github.com/acme/plot",
  status: null,
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
    mocks.listRepositories.mockReset().mockResolvedValue([]);
    mocks.createInstallationRequest.mockReset();
    mocks.connectRepository.mockReset();
    mocks.importRepository.mockReset();
  });

  it("starts GitHub App installation from the empty owner state", async () => {
    mocks.createInstallationRequest.mockReturnValue(new Promise(() => undefined));

    render(<IntegrationsWorkspace />);
    fireEvent.click(await screen.findByRole("button", { name: "Connect GitHub" }));

    await waitFor(() => expect(mocks.createInstallationRequest).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("button", { name: "Connect GitHub" })).toBeDisabled();
  });

  it("loads callback repositories and immediately removes callback query parameters", async () => {
    mocks.search = "githubConnection=connection-1";
    mocks.listConnections.mockResolvedValue([connection]);
    mocks.listRepositories.mockResolvedValue([repository]);

    render(<IntegrationsWorkspace />);

    expect(await screen.findByRole("radio", { name: /acme\/plot/i })).toBeVisible();
    expect(mocks.listRepositories).toHaveBeenCalledWith("connection-1");
    expect(mocks.replace).toHaveBeenCalledWith("/integrations");
  });

  it("connects the selected repository and imports one exact 30-day UTC window once", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date("2026-07-22T12:34:56.000Z"));
    mocks.listConnections.mockResolvedValue([connection]);
    mocks.listRepositories.mockResolvedValue([repository]);
    let resolveConnection: ((value: {
      id: string;
      externalRepositoryId: number;
      owner: string;
      name: string;
      displayName: string;
      url: string;
      status: string;
    }) => void) | undefined;
    mocks.connectRepository.mockImplementation(() => new Promise((resolve) => { resolveConnection = resolve; }));
    mocks.importRepository.mockResolvedValue({
      id: "import-1",
      sourceScopeId: "scope-1",
      from: "2026-06-22T12:34:56.000Z",
      to: "2026-07-22T12:34:56.000Z",
      status: "COMPLETED",
      eligibleCount: 3,
      blockCreatedCount: 2,
      blockUpdatedCount: 1,
      blockUnchangedCount: 0,
      errorCode: null,
      errorMessage: null,
      startedAt: "2026-07-22T12:34:56.000Z",
      completedAt: "2026-07-22T12:35:00.000Z",
    });

    render(<IntegrationsWorkspace />);
    fireEvent.click(await screen.findByRole("radio", { name: /acme\/plot/i }));
    const submit = screen.getByRole("button", { name: "Connect and import last 30 days" });
    fireEvent.click(submit);
    fireEvent.click(submit);

    expect(mocks.connectRepository).toHaveBeenCalledTimes(1);
    resolveConnection?.({ ...repository, id: "scope-1", status: "ACTIVE" });

    await waitFor(() => expect(mocks.importRepository).toHaveBeenCalledTimes(1));
    const [sourceScopeId, window] = mocks.importRepository.mock.calls[0] ?? [];
    expect(sourceScopeId).toBe("scope-1");
    expect(window.from).toMatch(/^2026-06-22T12:34:56\.\d{3}Z$/);
    expect(window.to).toMatch(/^2026-07-22T12:34:56\.\d{3}Z$/);
    expect(Date.parse(window.to) - Date.parse(window.from)).toBe(30 * 24 * 60 * 60 * 1000);
    expect(await screen.findByRole("status")).toHaveTextContent("2 created, 1 updated");
  });

  it("offers a retry after repository discovery fails", async () => {
    mocks.listConnections.mockResolvedValue([connection]);
    mocks.listRepositories
      .mockRejectedValueOnce(new PlotApiError(429, "GITHUB_RATE_LIMITED", "limited"))
      .mockResolvedValueOnce([repository]);

    render(<IntegrationsWorkspace />);

    expect(await screen.findByRole("alert")).toHaveTextContent("GitHub rate limit reached");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByRole("radio", { name: /acme\/plot/i })).toBeVisible();
    expect(mocks.listRepositories).toHaveBeenCalledTimes(2);
  });

  it("offers a stateful reconnect when the stored installation no longer exists", async () => {
    mocks.listConnections.mockResolvedValue([connection]);
    mocks.listRepositories.mockRejectedValue(
      new PlotApiError(502, "GITHUB_NOT_FOUND", "GitHub resource was not found (request provider-id)"),
    );
    mocks.createInstallationRequest.mockReturnValue(new Promise(() => undefined));

    render(<IntegrationsWorkspace />);

    expect(await screen.findByRole("alert")).toHaveTextContent("previous GitHub installation was replaced or removed");
    expect(screen.getByText("Not connected")).toBeVisible();
    expect(screen.queryByText(/No repositories are currently granted/)).not.toBeInTheDocument();

    const reconnect = screen.getByRole("button", { name: "Reconnect GitHub" });
    fireEvent.click(reconnect);

    await waitFor(() => expect(mocks.createInstallationRequest).toHaveBeenCalledTimes(1));
    expect(reconnect).toBeDisabled();
  });

  it("turns an overlapping import into a recoverable status message", async () => {
    mocks.listConnections.mockResolvedValue([connection]);
    mocks.listRepositories.mockResolvedValue([{ ...repository, id: "scope-1", status: "ACTIVE" }]);
    mocks.importRepository.mockRejectedValue(new PlotApiError(409, "IMPORT_ALREADY_RUNNING", "busy"));

    render(<IntegrationsWorkspace />);
    fireEvent.click(await screen.findByRole("radio", { name: /acme\/plot/i }));
    fireEvent.click(screen.getByRole("button", { name: "Import last 30 days" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("An import is already running");
    expect(screen.getByRole("button", { name: "Retry" })).toBeVisible();
  });

  it("shows connection state without owner controls for a non-owner", async () => {
    mocks.getWorkspace.mockResolvedValue({ id: "workspace-1", role: "MEMBER" });
    mocks.listConnections.mockResolvedValue([connection]);

    render(<IntegrationsWorkspace />);

    expect(await screen.findByText("Workspace owner must connect GitHub.")).toBeVisible();
    expect(screen.getByText("GitHub is connected for this workspace")).toBeVisible();
    expect(screen.queryByRole("button", { name: /Connect GitHub|Import last 30 days/ })).not.toBeInTheDocument();
    expect(mocks.listRepositories).not.toHaveBeenCalled();
  });
});
