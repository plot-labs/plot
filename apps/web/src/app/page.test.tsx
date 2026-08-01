// @vitest-environment jsdom

import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/components/landing/hero-terminal", () => ({
  HeroTerminal: () => <div data-testid="hero-terminal" />,
}));
vi.mock("@/components/landing/animated-plot-signal", () => ({
  AnimatedPlotSignal: () => <div data-testid="plot-signal" />,
}));
vi.mock("@/components/landing/animated-wave", () => ({
  AnimatedWave: () => <div data-testid="animated-wave" />,
}));

import Home from "./page";

describe("public landing page", () => {
  it("describes the current release-to-changelog workflow and labels future scope", () => {
    render(<Home />);

    expect(screen.getByRole("heading", { level: 1, name: /review-ready changelog/i })).toBeVisible();
    expect(screen.getByText(/published release range/i)).toBeVisible();
    expect(screen.getAllByText(/copy.*download.*Markdown/i).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Coming next").length).toBeGreaterThan(0);
    expect(screen.getAllByText(/docs impact suggestions/i).length).toBeGreaterThan(0);
    expect(screen.getAllByRole("link", { name: "Coming next" })[0]).toHaveAttribute("href", "#style");

    expect(screen.queryByText(/Choose a shipping window and release cadence/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/docs gaps, customer impact/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/docs updates, customer updates, and launch drafts from one/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Approved examples and explicit style rules/i)).not.toBeInTheDocument();

    expect(screen.getAllByRole("link", { name: "Join waitlist" }).length).toBeGreaterThan(0);
    expect(screen.getByRole("link", { name: "Privacy" })).toHaveAttribute("href", "/privacy");
    expect(screen.getByRole("link", { name: "Terms" })).toHaveAttribute("href", "/terms");
  });
});
