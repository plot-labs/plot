// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PasswordSignInForm, PasswordSignUpForm } from "./password-sign-in-form";

const fetchMock = vi.fn<typeof fetch>();

describe("PasswordSignInForm", () => {
  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
  });

  it("posts credentials and starts auth completion after a successful sign-in", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });

    render(<PasswordSignInForm />);
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: " member@example.com " } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "correct horse battery staple" } });
    fireEvent.click(screen.getByRole("button", { name: "Log in" }));

    await waitFor(() => expect(assign).toHaveBeenCalledWith("/auth/complete"));
    expect(fetchMock).toHaveBeenCalledWith("/api/auth/sign-in/password", {
      method: "POST",
      credentials: "include",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ email: "member@example.com", password: "correct horse battery staple" }),
    });
  });

  it("posts credentials and starts auth completion after a successful sign-up", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });

    render(<PasswordSignUpForm />);
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "new@example.com" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "correct horse battery staple" } });
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() => expect(assign).toHaveBeenCalledWith("/auth/complete"));
    expect(fetchMock).toHaveBeenCalledWith("/api/auth/sign-up/password", {
      method: "POST",
      credentials: "include",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ email: "new@example.com", password: "correct horse battery staple" }),
    });
  });

  it("shows the server error without redirecting when credentials are rejected", async () => {
    fetchMock.mockResolvedValue(Response.json({ error: "INVALID_CREDENTIALS", message: "Invalid email or password" }, { status: 401 }));
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });

    render(<PasswordSignInForm />);
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "member@example.com" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "wrong password" } });
    fireEvent.click(screen.getByRole("button", { name: "Log in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password");
    expect(assign).not.toHaveBeenCalled();
  });

  it("toggles password visibility", () => {
    render(<PasswordSignInForm />);
    const passwordInput = screen.getByLabelText("Password");

    expect(passwordInput).toHaveAttribute("type", "password");
    fireEvent.click(screen.getByRole("button", { name: "Show password" }));
    expect(passwordInput).toHaveAttribute("type", "text");
    expect(screen.getByRole("button", { name: "Hide password" })).toBeVisible();
  });

  it("requires a valid email and password before calling the auth endpoint", () => {
    render(<PasswordSignInForm />);

    fireEvent.click(screen.getByRole("button", { name: "Log in" }));

    expect(screen.getByText("Email is required")).toBeVisible();
    expect(screen.getByText("Password is required")).toBeVisible();
    expect(fetchMock).not.toHaveBeenCalled();
  });
  it("requires a ten-character password before calling the signup endpoint", () => {
    render(<PasswordSignUpForm />);

    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "new@example.com" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "short" } });
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(screen.getByText("Password must be at least 10 characters")).toBeVisible();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
