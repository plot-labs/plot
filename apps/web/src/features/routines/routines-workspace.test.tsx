// @vitest-environment jsdom

import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  workspaceId: "workspace-1" as string | null,
  listRoutines: vi.fn(),
  listGitHubConnections: vi.fn(),
  createRoutine: vi.fn(),
  updateRoutine: vi.fn(),
  runRoutineNow: vi.fn(),
}));

vi.mock("@/lib/api-client", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api-client")>("@/lib/api-client");
  return {
    ...actual,
    getSelectedWorkspaceId: () => mocks.workspaceId,
    plotApiClient: {
      listRoutines: mocks.listRoutines,
      listGitHubConnections: mocks.listGitHubConnections,
      createRoutine: mocks.createRoutine,
      updateRoutine: mocks.updateRoutine,
      runRoutineNow: mocks.runRoutineNow,
    },
  };
});

import type { Routine } from "@/lib/api-client";
import { RoutinesWorkspace } from "./routines-workspace";

const activeConnection = {
  id: "connection-1",
  installationId: 42,
  status: "ACTIVE",
  repositories: [{
    id: "source-1",
    externalRepositoryId: 101,
    owner: "acme",
    name: "plot",
    displayName: "acme/plot",
    url: "https://github.com/acme/plot",
    status: "ACTIVE",
    monitoring: null,
  }],
};

describe("RoutinesWorkspace", () => {
  beforeEach(() => {
    mocks.workspaceId = "workspace-1";
    mocks.listRoutines.mockReset().mockResolvedValue([]);
    mocks.listGitHubConnections.mockReset().mockResolvedValue([activeConnection]);
    mocks.createRoutine.mockReset();
    mocks.updateRoutine.mockReset();
    mocks.runRoutineNow.mockReset();
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
      configurable: true,
      value: vi.fn(),
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("keeps missing-selection and load errors distinct from a ready empty list", async () => {
    mocks.workspaceId = null;
    render(<RoutinesWorkspace />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Select a workspace to manage routines.");
    expect(screen.queryByText("No routines yet")).not.toBeInTheDocument();

    mocks.workspaceId = "workspace-1";
    act(() => {
      window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: "workspace-1" } }));
    });

    expect(await screen.findByText("No routines yet")).toBeVisible();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("does not render the empty state when routines fail to load", async () => {
    mocks.listRoutines.mockRejectedValueOnce(new Error("offline")).mockResolvedValue([]);
    render(<RoutinesWorkspace />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Routines could not be loaded.");
    expect(screen.queryByText("No routines yet")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Retry loading routines" }));
    expect(await screen.findByText("No routines yet")).toBeVisible();
  });

  it("ignores a create response from the previous workspace", async () => {
    const pendingCreate = deferred<Routine>();
    const workspaceTwoRoutine = routine({ id: "routine-2", name: "Workspace two routine" });
    mocks.listRoutines.mockResolvedValueOnce([]).mockResolvedValueOnce([workspaceTwoRoutine]);
    mocks.createRoutine.mockReturnValue(pendingCreate.promise);
    render(<RoutinesWorkspace />);

    await screen.findByText("No routines yet");
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Routine name" }), {
      target: { value: "Old workspace routine" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Create routine" }));
    await waitFor(() => expect(mocks.createRoutine).toHaveBeenCalledTimes(1));
    const signal = mocks.createRoutine.mock.calls[0]?.[1].signal as AbortSignal;

    switchWorkspace("workspace-2");
    expect(signal.aborted).toBe(true);
    await screen.findByText("Workspace two routine");

    await act(async () => {
      pendingCreate.resolve(routine({ id: "routine-old", name: "Old workspace routine" }));
      await pendingCreate.promise;
    });
    expect(screen.queryByText("Old workspace routine")).not.toBeInTheDocument();
  });

  it("ignores a toggle response from the previous workspace", async () => {
    const pendingUpdate = deferred<Routine>();
    const oldRoutine = routine({ id: "routine-old", name: "Old routine", enabled: true });
    mocks.listRoutines
      .mockResolvedValueOnce([oldRoutine])
      .mockResolvedValueOnce([routine({ id: "routine-2", name: "Workspace two routine" })]);
    mocks.updateRoutine.mockReturnValue(pendingUpdate.promise);
    render(<RoutinesWorkspace />);

    fireEvent.click(await screen.findByRole("button", { name: "Pause Old routine" }));
    await waitFor(() => expect(mocks.updateRoutine).toHaveBeenCalledTimes(1));
    const signal = mocks.updateRoutine.mock.calls[0]?.[2].signal as AbortSignal;
    switchWorkspace("workspace-2");
    expect(signal.aborted).toBe(true);
    await screen.findByText("Workspace two routine");

    await act(async () => {
      pendingUpdate.resolve({ ...oldRoutine, enabled: false });
      await pendingUpdate.promise;
    });
    expect(screen.queryByText("Old routine")).not.toBeInTheDocument();
  });

  it("ignores a run response from the previous workspace", async () => {
    const pendingRun = deferred<Routine>();
    const oldRoutine = routine({ id: "routine-old", name: "Old routine" });
    mocks.listRoutines
      .mockResolvedValueOnce([oldRoutine])
      .mockResolvedValueOnce([routine({ id: "routine-2", name: "Workspace two routine" })]);
    mocks.runRoutineNow.mockReturnValue(pendingRun.promise);
    render(<RoutinesWorkspace />);

    fireEvent.click(await screen.findByRole("button", { name: "Run" }));
    await waitFor(() => expect(mocks.runRoutineNow).toHaveBeenCalledTimes(1));
    const signal = mocks.runRoutineNow.mock.calls[0]?.[1].signal as AbortSignal;
    switchWorkspace("workspace-2");
    expect(signal.aborted).toBe(true);
    await screen.findByText("Workspace two routine");

    await act(async () => {
      pendingRun.resolve({ ...oldRoutine, lastRunStatus: "READY" });
      await pendingRun.promise;
    });
    expect(screen.queryByText("Old routine")).not.toBeInTheDocument();
  });

  it("keeps an in-progress run disabled until an explicit refresh settles it", async () => {
    const idleRoutine = routine({ id: "routine-1", name: "Release routine" });
    mocks.listRoutines
      .mockResolvedValueOnce([idleRoutine])
      .mockResolvedValueOnce([{ ...idleRoutine, lastRunStatus: "READY" }]);
    mocks.runRoutineNow.mockResolvedValue({ ...idleRoutine, lastRunStatus: "QUEUED" });
    render(<RoutinesWorkspace />);

    const run = await screen.findByRole("button", { name: "Run" });
    fireEvent.click(run);

    expect(await screen.findByText("Last run: queued")).toBeVisible();
    expect(run).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Refresh routines" }));

    expect(await screen.findByText("Last run: ready", {}, { timeout: 2_000 })).toBeVisible();
    await waitFor(() => expect(screen.getByRole("button", { name: "Run" })).toBeEnabled());
    expect(mocks.listRoutines).toHaveBeenCalledTimes(2);
  });

  it("focuses and scrolls to the create form, then restores focus when it closes", async () => {
    render(<RoutinesWorkspace />);
    await screen.findByText("No routines yet");

    fireEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(screen.getByRole("textbox", { name: "Routine name" })).toHaveFocus();
    expect(HTMLElement.prototype.scrollIntoView).toHaveBeenCalledWith({ block: "start" });

    fireEvent.click(screen.getByRole("button", { name: "Close create routine" }));
    expect(screen.getByRole("button", { name: "Create" })).toHaveFocus();
  });

  it("keeps the closed desktop list scrollable", async () => {
    render(<RoutinesWorkspace />);
    await screen.findByText("No routines yet");

    expect(screen.getByRole("heading", { name: "Routines" }).closest("section"))
      .toHaveClass("lg:h-full", "lg:overflow-y-auto");
  });
});

function routine(overrides: Partial<Routine> = {}): Routine {
  return {
    id: "routine-1",
    name: "Weekly update",
    sourceScopeId: "source-1",
    sourceLabel: "acme/plot",
    instruction: "Summarize the latest changes.",
    cadence: "WEEKLY",
    enabled: true,
    lastRunAt: null,
    nextRunAt: "2026-08-10T00:00:00Z",
    lastGenerationRunId: null,
    lastRunStatus: null,
    lastErrorCode: null,
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

function switchWorkspace(workspaceId: string) {
  mocks.workspaceId = workspaceId;
  act(() => {
    window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: workspaceId } }));
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}
