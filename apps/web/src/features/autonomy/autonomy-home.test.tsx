// @vitest-environment jsdom
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ActivityItem, ActivityPage } from "@plot/api-client";

const api = vi.hoisted(() => ({
  getActivity: vi.fn(),
}));

vi.mock("@/lib/api-client", async (importOriginal) => ({
  ...await importOriginal<typeof import("@/lib/api-client")>(),
  plotApiClient: api,
}));

import { AutonomyHomeWorkspace } from "./autonomy-home";

const activityItem = (overrides: Partial<ActivityItem> = {}): ActivityItem => ({
  id: "act-1",
  sourceScopeId: "scope-1",
  signalId: "sig-1",
  responseVersionId: null,
  agentRunId: null,
  chatId: null,
  artifactId: null,
  title: "OAuth support",
  status: "IN_PROGRESS",
  reason: "Release availability is under evaluation.",
  semanticTime: "2026-09-09T00:00:00Z",
  updatedAt: "2026-09-09T00:00:00Z",
  ...overrides,
});

describe("Autonomy Home", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    localStorage.setItem("plot.workspaceId", "workspace-1");
  });

  it("shows activity items and distinguishes status labels without implying publication", async () => {
    api.getActivity.mockResolvedValue({
      items: [
        activityItem(),
        activityItem({
          id: "act-2",
          title: "API changes",
          status: "READY_FOR_REVIEW",
          agentRunId: "agent-2",
          chatId: "chat-2",
          responseVersionId: "ver-2",
          reason: "Draft changelog is ready for review.",
        }),
      ],
      nextCursor: null,
      hasMore: false,
      highWaterMark: "hwm-1",
    });

    render(<AutonomyHomeWorkspace view="activity" />);
    expect(await screen.findByText("OAuth support")).toBeInTheDocument();
    expect(screen.getByText("Drafting in progress")).toBeInTheDocument();
    expect(screen.getByText("API changes")).toBeInTheDocument();
    expect(screen.getByText("Ready for review")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Review and discuss" })).toHaveAttribute(
      "href",
      "/chat?chat=chat-2&version=ver-2",
    );
    expect(screen.getByText(/Publishing still requires your review/)).toBeInTheDocument();
  });

  it("filters activity items by status category and renders filtered empty state when none match", async () => {
    api.getActivity.mockResolvedValue({
      items: [
        activityItem({ id: "act-1", title: "Active release draft", status: "READY_FOR_REVIEW" }),
      ],
      nextCursor: null,
      hasMore: false,
      highWaterMark: "hwm-1",
    });

    render(<AutonomyHomeWorkspace view="activity" />);
    expect(await screen.findByText("Active release draft")).toBeInTheDocument();

    // Click Decided filter tab
    fireEvent.click(screen.getByRole("tab", { name: /Decided/ }));
    expect(screen.queryByText("Active release draft")).not.toBeInTheDocument();
    expect(screen.getByText("No matching activity found for the selected filter.")).toBeInTheDocument();

    // Click Active filter tab
    fireEvent.click(screen.getByRole("tab", { name: /Active/ }));
    expect(screen.getByText("Active release draft")).toBeInTheDocument();
  });

  it("handles pagination and preserves loaded items when pagination fails", async () => {
    api.getActivity
      .mockResolvedValueOnce({
        items: [activityItem({ id: "act-1", title: "First page item" })],
        nextCursor: "cursor-2",
        hasMore: true,
        highWaterMark: "hwm-1",
      })
      .mockRejectedValueOnce(new Error("Network error loading next page"));

    render(<AutonomyHomeWorkspace view="activity" />);
    expect(await screen.findByText("First page item")).toBeInTheDocument();

    const loadMoreButton = screen.getByRole("button", { name: "Load more activity" });
    fireEvent.click(loadMoreButton);

    expect(await screen.findByRole("alert")).toHaveTextContent("Network error loading next page");
    // Loaded items remain intact
    expect(screen.getByText("First page item")).toBeInTheDocument();
  });

  it("discards a late response from the previous workspace when workspace changes", async () => {
    let finishOld!: (value: ActivityPage) => void;
    api.getActivity.mockReturnValueOnce(new Promise<ActivityPage>((resolve) => { finishOld = resolve; }));
    api.getActivity.mockResolvedValueOnce({ items: [], nextCursor: null, hasMore: false, highWaterMark: "hwm-2" });

    render(<AutonomyHomeWorkspace view="activity" />);
    await waitFor(() => expect(api.getActivity).toHaveBeenCalledTimes(1));

    act(() => {
      localStorage.setItem("plot.workspaceId", "workspace-2");
      window.dispatchEvent(new Event("plot:workspace-changed"));
    });

    expect(await screen.findByText(/Plot assesses connected changes/)).toBeInTheDocument();
    await act(async () => {
      finishOld({ items: [activityItem()], nextCursor: null, hasMore: false, highWaterMark: "hwm-1" });
    });
    expect(screen.queryByText("OAuth support")).not.toBeInTheDocument();
  });

  it("clears pagination loading when a workspace refresh cancels the page request", async () => {
    let finishPageRequest!: (value: ActivityPage) => void;
    api.getActivity
      .mockResolvedValueOnce({
        items: [activityItem({ id: "act-1", title: "Workspace one" })],
        nextCursor: "cursor-2",
        hasMore: true,
        highWaterMark: "hwm-1",
      })
      .mockReturnValueOnce(new Promise<ActivityPage>((resolve) => { finishPageRequest = resolve; }))
      .mockResolvedValueOnce({
        items: [activityItem({ id: "act-2", title: "Workspace two" })],
        nextCursor: "cursor-3",
        hasMore: true,
        highWaterMark: "hwm-2",
      });

    render(<AutonomyHomeWorkspace view="activity" />);
    expect(await screen.findByText("Workspace one")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Load more activity" }));
    expect(screen.getByRole("button", { name: "Loading more activity…" })).toBeDisabled();

    act(() => {
      localStorage.setItem("plot.workspaceId", "workspace-2");
      window.dispatchEvent(new Event("plot:workspace-changed"));
    });

    expect(await screen.findByText("Workspace two")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Load more activity" })).toBeEnabled();

    await act(async () => {
      finishPageRequest({ items: [], nextCursor: null, hasMore: false, highWaterMark: "hwm-1" });
    });
  });

  it("renders empty activity state when no items exist", async () => {
    api.getActivity.mockResolvedValue({
      items: [],
      nextCursor: null,
      hasMore: false,
      highWaterMark: "hwm-0",
    });

    render(<AutonomyHomeWorkspace />);
    expect(await screen.findByText("No activity yet")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "New chat" })).toHaveAttribute("href", "/chat");
  });
});
