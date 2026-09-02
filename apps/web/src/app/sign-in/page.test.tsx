// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/components/auth/animated-dither-artwork", () => ({
  AnimatedDitherArtwork: () => <div />,
}));

import SignInPage from "./page";

describe("SignInPage", () => {
  it("starts GitHub sign-in through the auth proxy route", () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });

    render(<SignInPage />);
    fireEvent.click(screen.getByRole("button", { name: "GitHub" }));

    expect(assign).toHaveBeenCalledWith("/api/auth/sign-in/github?callbackURL=%2Fauth%2Fcomplete");
  });
});
