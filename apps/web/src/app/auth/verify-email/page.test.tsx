// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

const searchParams = { get: vi.fn(() => "member@example.com") };

vi.mock("next/navigation", () => ({
  useSearchParams: () => searchParams,
}));
vi.mock("@/components/auth/animated-dither-artwork", () => ({
  AnimatedDitherArtwork: () => <div />,
}));

import VerifyEmailPage from "./page";

describe("VerifyEmailPage", () => {
  it("submits the code and completes an unverified social sign-in", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ status: "authenticated" })));
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });

    render(<VerifyEmailPage />);

    expect(screen.getByRole("heading", { name: "Verify your email" })).toBeVisible();
    expect(screen.getByText("member@example.com")).toBeVisible();
    fireEvent.paste(screen.getByLabelText("Verification code, digit 1 of 6"), { clipboardData: { getData: () => "123456" } });
    fireEvent.click(screen.getByRole("button", { name: "Verify email" }));

    await waitFor(() => expect(assign).toHaveBeenCalledWith("/auth/complete"));
  });

  it("resends the verification code through the server route", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ status: "sent" }, { status: 202 }));
    vi.stubGlobal("fetch", fetchMock);

    render(<VerifyEmailPage />);
    fireEvent.click(screen.getByRole("button", { name: "Resend code" }));

    await waitFor(() => expect(screen.getByText("A new verification code is on its way.")).toBeVisible());
    expect(fetchMock).toHaveBeenCalledWith("/api/auth/verify-email/resend", {
      method: "POST",
      credentials: "include",
      headers: { Accept: "application/json" },
    });
  });
});
