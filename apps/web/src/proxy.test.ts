import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";

import { config, isGatedHost, proxy } from "./proxy";

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

  it("allows unauthenticated access to public changelog pages on gated hosts", () => {
    const request = new NextRequest("http://localhost:3000/changelog/acme", {
      headers: { host: "localhost:3000" },
    });

    expect(proxy(request).status).toBe(200);
  });

  it("redirects unauthenticated visitors away from gated app routes", () => {
    const request = new NextRequest("http://localhost:3000/chat", {
      headers: { host: "localhost:3000" },
    });

    expect(proxy(request).status).toBe(307);
    expect(proxy(request).headers.get("location")).toBe("http://localhost:3000/sign-in");
  });
});
