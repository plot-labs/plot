// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

const searchParams = { get: vi.fn(() => null as string | null) };

vi.mock("next/navigation", () => ({
  useSearchParams: () => searchParams,
}));
vi.mock("@/components/auth/animated-dither-artwork", () => ({
  AnimatedDitherArtwork: () => <div />,
}));

import SignInPage from "./page";

describe("SignInPage", () => {
  it("starts WorkOS sign-in through the server route", () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });

    render(<SignInPage />);
    fireEvent.click(screen.getByRole("button", { name: "GitHub" }));

    expect(assign).toHaveBeenCalledWith("/api/auth/sign-in?returnTo=%2Fauth%2Fcomplete");
  });

  it("renders the passwordless email path and links visitors to signup", () => {
    render(<SignInPage />);

    expect(screen.getByRole("link", { name: "Create one" })).toHaveAttribute("href", "/sign-up");
    expect(screen.queryByText("Only approved Plot accounts can sign in.")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Email me a sign-in code" })).toBeVisible();
    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
  });
});
