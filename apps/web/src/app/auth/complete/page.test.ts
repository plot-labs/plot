import { describe, expect, it } from "vitest";

import { bootstrapErrorMessage } from "./page";

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
