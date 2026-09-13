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

import SignUpPage from "./page";

describe("SignUpPage", () => {
  it("renders the passwordless signup path and links existing users to sign in", () => {
    render(<SignUpPage />);

    expect(screen.getByRole("heading", { name: "Create your account" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Log in" })).toHaveAttribute("href", "/sign-in");
    expect(screen.getByRole("button", { name: "Email me a sign-in code" })).toBeVisible();
    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
  });

  it("starts WorkOS signup through the server route", () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });

    render(<SignUpPage />);
    fireEvent.click(screen.getByRole("button", { name: "GitHub" }));

    expect(assign).toHaveBeenCalledWith("/api/auth/sign-up?returnTo=%2Fauth%2Fcomplete");
  });

  it("does not show the old approved-email gate copy", () => {
    render(<SignUpPage />);

    expect(screen.queryByText("Use your approved Plot email to create an account.")).not.toBeInTheDocument();
  });
});
