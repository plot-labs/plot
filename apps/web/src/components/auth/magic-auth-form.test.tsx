// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { MagicSignInForm, MagicSignUpForm } from "./magic-auth-form";

describe("MagicAuthForm", () => {
  it("requests a sign-in code with only an email", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({ email: "member@example.com", status: "verification_required" }, { status: 202 }),
    );
    vi.stubGlobal("fetch", fetchMock);

    render(<MagicSignInForm />);
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: " Member@Example.com " } });
    fireEvent.click(screen.getByRole("button", { name: "Email me a sign-in code" }));

    await waitFor(() => expect(screen.getByLabelText("Sign-in code, digit 1 of 6")).toBeVisible());
    expect(fetchMock).toHaveBeenCalledWith("/api/auth/sign-in", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ email: "member@example.com" }),
    }));
    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
  });

  it("uses the signup endpoint for new accounts", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({ email: "new@example.com", status: "verification_required" }, { status: 202 }),
    );
    vi.stubGlobal("fetch", fetchMock);

    render(<MagicSignUpForm />);
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "new@example.com" } });
    fireEvent.click(screen.getByRole("button", { name: "Email me a sign-in code" }));

    await waitFor(() => expect(screen.getByLabelText("Sign-in code, digit 1 of 6")).toBeVisible());
    expect(fetchMock).toHaveBeenCalledWith("/api/auth/sign-up", expect.objectContaining({ method: "POST" }));
  });

  it("verifies the code and completes authentication", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ email: "member@example.com", status: "verification_required" }, { status: 202 }))
      .mockResolvedValueOnce(Response.json({ status: "authenticated" }));
    vi.stubGlobal("fetch", fetchMock);
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });

    render(<MagicSignInForm />);
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "member@example.com" } });
    fireEvent.click(screen.getByRole("button", { name: "Email me a sign-in code" }));
    await waitFor(() => expect(screen.getByLabelText("Sign-in code, digit 1 of 6")).toBeVisible());
    fireEvent.paste(screen.getByLabelText("Sign-in code, digit 1 of 6"), { clipboardData: { getData: () => "123456" } });
    fireEvent.click(screen.getByRole("button", { name: "Continue to Plot" }));

    await waitFor(() => expect(assign).toHaveBeenCalledWith("/auth/complete"));
    expect(fetchMock).toHaveBeenLastCalledWith("/api/auth/magic-auth/verify", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ code: "123456" }),
    }));
  });

  it("resends the code without exposing the email in the request", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ email: "member@example.com", status: "verification_required" }, { status: 202 }))
      .mockResolvedValueOnce(Response.json({ status: "sent" }, { status: 202 }));
    vi.stubGlobal("fetch", fetchMock);

    render(<MagicSignInForm />);
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "member@example.com" } });
    fireEvent.click(screen.getByRole("button", { name: "Email me a sign-in code" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Resend code" })).toBeVisible());
    fireEvent.click(screen.getByRole("button", { name: "Resend code" }));

    await waitFor(() => expect(screen.getByText("A new sign-in code is on its way.")).toBeVisible());
    expect(fetchMock).toHaveBeenLastCalledWith("/api/auth/magic-auth/resend", expect.objectContaining({ method: "POST" }));
  });
});
