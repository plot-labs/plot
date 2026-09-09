// @vitest-environment jsdom
import { act, fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("./chat-workspace", () => ({
  ChatWorkspace: ({ target, onNavigate }: { target: { chatId?: string }; onNavigate: (href: string) => void }) => <div>
    <span>{target.chatId ?? "New conversation"}</span>
    <input aria-label="Request" defaultValue="" />
    <button onClick={() => onNavigate("/chat?chat=created&agent=run")}>Start request</button>
  </div>,
}));
import { ConversationPanel, openPlotConversation } from "./conversation-panel";

describe("ConversationPanel", () => {
  it("keeps the underlying page and request when closed and reopened", () => {
    render(<><div data-testid="page">Overview</div><button onClick={() => openPlotConversation()}>Ask</button><ConversationPanel /></>);
    const page = screen.getByTestId("page");
    const trigger = screen.getByRole("button", { name: "Ask" });
    trigger.focus();
    fireEvent.click(trigger);
    fireEvent.change(screen.getByLabelText("Request"), { target: { value: "Draft the release" } });
    expect(screen.getByRole("button", { name: "Close conversation" })).toHaveFocus();
    fireEvent.keyDown(screen.getByLabelText("Request"), { key: "Escape" });
    expect(screen.queryByRole("complementary")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    fireEvent.click(trigger);
    expect(screen.getByLabelText("Request")).toHaveValue("Draft the release");
    expect(screen.getByTestId("page")).toBe(page);
  });

  it("keeps new-run navigation inside the panel and clears it on workspace change", () => {
    render(<ConversationPanel />);
    const originalUrl = window.location.href;
    act(() => openPlotConversation({ chatId: "selected", agentId: "existing" }));
    expect(screen.getByText("selected")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Start request" }));
    expect(screen.getByText("created")).toBeVisible();
    expect(window.location.href).toBe(originalUrl);
    act(() => window.dispatchEvent(new Event("plot:workspace-changed")));
    expect(screen.queryByRole("complementary")).not.toBeInTheDocument();
    act(() => openPlotConversation());
    expect(screen.getByText("New conversation")).toBeVisible();
  });
});
