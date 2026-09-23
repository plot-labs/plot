// @vitest-environment jsdom

import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getCreditOverview: vi.fn(),
}));

vi.mock("@/lib/api-client", () => ({
  getSelectedWorkspaceId: () => "workspace-1",
  plotApiClient: mocks,
}));

import { WorkspaceCredits } from "./workspace-credits";

describe("WorkspaceCredits", () => {
  beforeEach(() => {
    mocks.getCreditOverview.mockReset().mockResolvedValue({
      balance: 4_998,
      creditedUnits: 5_000,
      consumedUnits: 2,
      usageEvents: [{
        id: "event-1",
        timestamp: "2026-09-19T12:00:00Z",
        credits: 2,
        provider: "openrouter",
        model: "deepseek/deepseek-v4-flash-0731",
      }],
    });
  });

  it("shows the workspace balance and measured usage events", async () => {
    render(<WorkspaceCredits />);

    expect(await screen.findByText("4,998 credits")).toBeVisible();
    expect(screen.getByText("2 credits")).toBeVisible();
    expect(screen.getByText("0.04%")).toBeVisible();
    expect(screen.getByText("deepseek/deepseek-v4-flash-0731")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Add credits" })).not.toBeInTheDocument();
    expect(mocks.getCreditOverview).toHaveBeenCalledTimes(1);
  });

  it("shows an empty state when there are no usage events", async () => {
    mocks.getCreditOverview.mockResolvedValueOnce({
      balance: 5_000,
      creditedUnits: 5_000,
      consumedUnits: 0,
      usageEvents: [],
    });

    render(<WorkspaceCredits />);

    expect(await screen.findByText("No usage events yet.")).toBeVisible();
  });

  it("refreshes the workspace balance when the page regains focus", async () => {
    const visibilityState = Object.getOwnPropertyDescriptor(document, "visibilityState");
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });

    try {
      render(<WorkspaceCredits />);

      expect(await screen.findByText("4,998 credits")).toBeVisible();
      mocks.getCreditOverview.mockResolvedValueOnce({
        balance: 4_997,
        creditedUnits: 5_000,
        consumedUnits: 3,
        usageEvents: [{
          id: "event-2",
          timestamp: "2026-09-22T13:03:14Z",
          credits: 1,
          provider: "openrouter",
          model: "openai/gpt-5.6-luna",
        }],
      });

      window.dispatchEvent(new Event("focus"));

      expect(await screen.findByText("4,997 credits")).toBeVisible();
      expect(screen.getByText("openai/gpt-5.6-luna")).toBeVisible();
      expect(mocks.getCreditOverview).toHaveBeenCalledTimes(2);
    } finally {
      if (visibilityState) {
        Object.defineProperty(document, "visibilityState", visibilityState);
      } else {
        Reflect.deleteProperty(document, "visibilityState");
      }
    }
  });
});
