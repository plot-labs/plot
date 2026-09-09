// @vitest-environment jsdom
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AutonomyHome, AutonomyHomeItem } from "@plot/api-client";

const api = vi.hoisted(() => ({ getAutonomyHome: vi.fn(), dismissOpportunity: vi.fn(), restoreOpportunity: vi.fn() }));
vi.mock("@/lib/api-client", async (importOriginal) => ({
  ...await importOriginal<typeof import("@/lib/api-client")>(),
  plotApiClient: api,
}));
import { AutonomyHomeWorkspace } from "./autonomy-home";

const item = (overrides: Partial<AutonomyHomeItem> = {}): AutonomyHomeItem => ({
  id: "op-1", sourceScopeId: "scope-1", title: "OAuth support", disposition: "AWAITING_EVIDENCE", reason: "Release availability is unconfirmed.", dismissed: false, version: 3,
  missingFacts: ["Confirm customer availability"], lastErrorCode: null, goalState: null, agentRunId: null, chatId: null, updatedAt: "2026-09-09T00:00:00Z", ...overrides,
});

describe("Autonomy Home", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    localStorage.setItem("plot.workspaceId", "workspace-1");
  });

  it("shows held evidence separately from draft activity without implying publication", async () => {
    api.getAutonomyHome.mockResolvedValue({ mode: "ACTIVE", items: [item(), item({ id: "op-2", title: "API changes", disposition: "ELIGIBLE", agentRunId: "agent-2", chatId: "chat-2", missingFacts: [] })] });
    render(<AutonomyHomeWorkspace view="activity" />);
    expect(await screen.findByText("OAuth support")).toBeInTheDocument();
    expect(screen.getByText("Confirm customer availability")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Review and discuss" })).toHaveAttribute("href", "/chat?chat=chat-2&agent=agent-2");
    expect(screen.getByText(/Publishing still requires your review/)).toBeInTheDocument();
  });

  it("uses the displayed version to dismiss and exposes restore", async () => {
    api.getAutonomyHome.mockResolvedValue({ mode: "ACTIVE", items: [item()] });
    api.dismissOpportunity.mockResolvedValue(item({ dismissed: true, version: 4 }));
    render(<AutonomyHomeWorkspace view="activity" />);
    fireEvent.click(await screen.findByRole("button", { name: "Dismiss" }));
    await waitFor(() => expect(api.dismissOpportunity).toHaveBeenCalledWith("op-1", 3, expect.objectContaining({ signal: expect.any(AbortSignal) })));
    fireEvent.click(await screen.findByText("Excluded and dismissed (1)"));
    expect(screen.getByRole("button", { name: "Restore" })).toBeInTheDocument();
  });

  it("discards a late response from the previous workspace", async () => {
    let finishOld!: (value: AutonomyHome) => void;
    api.getAutonomyHome.mockReturnValueOnce(new Promise<AutonomyHome>((resolve) => { finishOld = resolve; }));
    api.getAutonomyHome.mockResolvedValueOnce({ mode: "OFF", items: [] });
    render(<AutonomyHomeWorkspace view="activity" />);
    await waitFor(() => expect(api.getAutonomyHome).toHaveBeenCalledTimes(1));
    act(() => {
      localStorage.setItem("plot.workspaceId", "workspace-2");
      window.dispatchEvent(new Event("plot:workspace-changed"));
    });
    expect(await screen.findByText(/Automatic assessment is not enabled/)).toBeInTheDocument();
    await act(async () => { finishOld({ mode: "ACTIVE", items: [item()] }); });
    expect(screen.queryByText("OAuth support")).not.toBeInTheDocument();
  });
});

it("does not apply an old workspace decision after a switch", async () => {
  vi.resetAllMocks();
  localStorage.setItem("plot.workspaceId", "workspace-1");
  let finishDecision!: (value: AutonomyHomeItem) => void;
  api.getAutonomyHome.mockResolvedValueOnce({ mode: "ACTIVE", items: [item()] });
  api.getAutonomyHome.mockResolvedValueOnce({ mode: "ACTIVE", items: [item({ title: "New workspace update" })] });
  api.dismissOpportunity.mockReturnValue(new Promise<AutonomyHomeItem>((resolve) => { finishDecision = resolve; }));
  render(<AutonomyHomeWorkspace view="activity" />);
  fireEvent.click(await screen.findByRole("button", { name: "Dismiss" }));
  act(() => {
    localStorage.setItem("plot.workspaceId", "workspace-2");
    window.dispatchEvent(new Event("plot:workspace-changed"));
  });
  expect(await screen.findByText("New workspace update")).toBeInTheDocument();
  await act(async () => { finishDecision(item({ dismissed: true, version: 4 })); });
  expect(screen.getByText("New workspace update")).toBeInTheDocument();
  expect(screen.queryByText("Excluded and dismissed (1)")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Dismiss" })).toBeEnabled();
});

 it("summarizes held changes in Overview without exposing dismissal controls", async () => {
    api.getAutonomyHome.mockResolvedValue({ mode: "ACTIVE", items: [item()] });
    render(<AutonomyHomeWorkspace />);
    expect(await screen.findByText(/1 changes under consideration/)).toBeVisible();
    expect(screen.queryByRole("button", { name: "Dismiss" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "View all activity" })).toHaveAttribute("href", "/activity");
    expect(screen.getByRole("link", { name: "Ask Plot" })).toHaveAttribute("href", "/chat");
  });
