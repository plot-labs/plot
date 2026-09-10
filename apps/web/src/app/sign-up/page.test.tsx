// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/components/auth/animated-dither-artwork", () => ({
  AnimatedDitherArtwork: () => <div />,
}));

import SignUpPage from "./page";

describe("SignUpPage", () => {
  it("renders the password signup path and links existing users to sign in", () => {
    render(<SignUpPage />);

    expect(screen.getByRole("heading", { name: "Create your account" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Log in" })).toHaveAttribute("href", "/sign-in");
    expect(screen.getByRole("button", { name: "Create account" })).toBeVisible();
  });

  it("starts GitHub signup through the auth proxy route", () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });

    render(<SignUpPage />);
    fireEvent.click(screen.getByRole("button", { name: "GitHub" }));

    expect(assign).toHaveBeenCalledWith("/api/auth/sign-in/github?callbackURL=%2Fauth%2Fcomplete");
  });
});
