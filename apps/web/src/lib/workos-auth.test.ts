import { describe, expect, it } from "vitest";

import {
  isAllowedWorkOSOrigin,
  parseWorkOSWebConfig,
  safeWorkOSReturnPath,
} from "@/lib/workos-auth";

describe("WorkOS web auth configuration", () => {
  it("requires the minimum server-side configuration when enabled", () => {
    expect(() => parseWorkOSWebConfig({
      WORKOS_AUTH_ENABLED: "true",
      WORKOS_CLIENT_ID: "",
      WORKOS_API_KEY: "sk_test",
      WORKOS_COOKIE_PASSWORD: "a".repeat(32),
      WORKOS_REDIRECT_URI: "https://app.useplot.xyz/auth/callback",
      PLOT_APP_ORIGIN: "https://app.useplot.xyz",
      PLOT_WORKOS_ALLOWED_ORIGINS: "https://app.useplot.xyz",
    })).toThrow("WORKOS_CLIENT_ID");
  });

  it("rejects an untrusted callback origin", () => {
    expect(isAllowedWorkOSOrigin(
      "https://evil.example",
      ["https://app.useplot.xyz"],
    )).toBe(false);
  });

  it("allows only local return paths", () => {
    expect(safeWorkOSReturnPath("/auth/complete")).toBe("/auth/complete");
    expect(safeWorkOSReturnPath("https://evil.example")).toBe("/auth/complete");
    expect(safeWorkOSReturnPath("//evil.example")).toBe("/auth/complete");
  });
});
