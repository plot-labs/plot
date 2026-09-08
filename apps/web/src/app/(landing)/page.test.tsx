// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import Home from "./page";

describe("public landing page", () => {
  it("shows the product workspace and preserves the publish and waitlist paths", () => {
    render(<Home />);

    expect(
      screen.getByRole("heading", {
        level: 1,
        name: /you can publish/i,
      }),
    ).toBeVisible();
    expect(screen.getByText(/published release range/i)).toBeVisible();
    expect(screen.getAllByText(/publish/i).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/public changelog/i).length).toBeGreaterThan(0);
    expect(screen.getByLabelText("Example workspace sidebar")).toBeVisible();
    expect(screen.getByLabelText("Example changelog document")).toBeVisible();
    expect(screen.getByRole("button", { name: /Sources · 2/ })).toBeVisible();
    expect(screen.queryByText("Coming next")).not.toBeInTheDocument();

    expect(screen.queryByText(/outside Plot/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/publish outside/i)).not.toBeInTheDocument();

    expect(screen.queryByText(/Choose a shipping window and release cadence/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/docs gaps, customer impact/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/docs updates, customer updates, and launch drafts from one/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Approved examples and explicit style rules/i)).not.toBeInTheDocument();

    expect(screen.getAllByRole("link", { name: "Join waitlist" }).length).toBeGreaterThan(0);
    expect(screen.getByRole("link", { name: "Privacy" })).toHaveAttribute("href", "/privacy");
    expect(screen.getByRole("link", { name: "Terms" })).toHaveAttribute("href", "/terms");
  });

  it("keeps the landing preview interactive without requesting private data", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    render(<Home />);
    expect(await screen.findByRole("textbox", { name: "Draft content" })).toHaveAttribute("contenteditable", "true");
    expect(screen.getByRole("button", { name: "Heading" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Sources · 2" }));
    expect(screen.getByRole("dialog", { name: "Sources" })).toBeVisible();
    expect(screen.getAllByText(/#142 · Search projects by name/).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole("button", { name: "Close sources" }));
    expect(screen.queryByRole("dialog", { name: "Sources" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(screen.getByText("Saved")).toBeVisible());
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });
});
