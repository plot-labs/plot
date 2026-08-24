import { describe, expect, it } from "vitest";

import { config, isGatedHost } from "./proxy";

describe("application proxy", () => {
  it("matches application and API routes without intercepting static assets", () => {
    expect(config.matcher).toContain("_next/static");
    expect(config.matcher).toContain(".*\\.[^/]+$");
    expect(config.matcher).not.toBe("/:path*");
  });

  it("gates production, local, and preview hosts", () => {
    expect(isGatedHost("app.useplot.xyz")).toBe(true);
    expect(isGatedHost("localhost")).toBe(true);
    expect(isGatedHost("127.0.0.1")).toBe(true);
    expect(isGatedHost("plot-git-feature.vercel.app")).toBe(true);
    expect(isGatedHost("evil.example.com")).toBe(false);
    expect(isGatedHost("vercel.app.attacker.io")).toBe(false);
  });
});
