// @vitest-environment jsdom

import { render, screen } from "@testing-library/react";
import { createElement } from "react";
import { describe, expect, it, vi } from "vitest";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
}));

vi.mock("@/components/auth/animated-dither-artwork", () => ({
  AnimatedDitherArtwork: () => null,
}));

vi.mock("@/lib/auth-client", () => ({
  authClient: {},
}));

import AuthCompletePage, { bootstrapErrorMessage } from "./page";

describe("bootstrapErrorMessage", () => {
  it.each([
    [new Error("ACCOUNT_LINK_REQUIRED"), "This email is already linked to another Plot account."],
    [new Error("UNAUTHORIZED"), "Your sign-in session expired. Please sign in again."],
    [new Error("ACCESS_DENIED"), "Plot could not create your workspace (ACCESS_DENIED)."],
    [new Error("PLOT_UPSTREAM_UNAVAILABLE"), "Plot API is unavailable. Please try again."],
    [new Error("PRIVATE_UPSTREAM_DETAIL"), "Plot could not create your workspace. Please try again."],
    [null, "Plot could not create your workspace (ACCESS_DENIED)."],
  ])("maps a safe bootstrap failure", (failure, message) => {
    expect(bootstrapErrorMessage(failure)).toBe(message);
  });
});

describe("AuthCompletePage", () => {
  it("redirects a bootstrapped account to Integrations", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ workspaceId: "workspace-1" })));

    render(createElement(AuthCompletePage));

    expect(await screen.findByText("Finishing sign-in…")).toBeInTheDocument();
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/integrations"));
  });
});
