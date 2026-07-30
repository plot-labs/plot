// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  signIn: vi.fn(),
}));

vi.mock("@/lib/auth-client", () => ({
  authClient: { signIn: { social: mocks.signIn } },
}));
vi.mock("@/components/auth/animated-dither-artwork", () => ({
  AnimatedDitherArtwork: () => <div />,
}));

import SignInPage from "./page";

describe("SignInPage", () => {
  beforeEach(() => {
    mocks.signIn.mockReset();
  });

  it("recovers when GitHub sign-in cannot start", async () => {
    mocks.signIn.mockRejectedValue(new TypeError("Failed to fetch"));
    render(<SignInPage />);

    fireEvent.click(screen.getByRole("button", { name: "GitHub" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "GitHub sign-in could not start. Please try again.",
    );
    await waitFor(() => expect(screen.getByRole("button", { name: "GitHub" })).toBeEnabled());
  });
});
