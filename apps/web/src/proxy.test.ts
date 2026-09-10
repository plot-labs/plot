import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";

import { SESSION_COOKIE } from "@/lib/plot-auth";

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
    const listRequest = new NextRequest("http://localhost:3000/acme/changelog", {
      headers: { host: "localhost:3000" },
    });
    const entryRequest = new NextRequest("http://localhost:3000/acme/changelog/v2.4.0", {
      headers: { host: "localhost:3000" },
    });

    expect(proxy(listRequest).status).toBe(200);
    expect(proxy(entryRequest).status).toBe(200);
  });

  it("allows unauthenticated access to sign-up on gated hosts", () => {
    const request = new NextRequest("http://localhost:3000/sign-up", {
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

  it("rate-limits API writes per IP and answers Retry-After", () => {
    const headers = { host: "localhost:3000", "x-forwarded-for": "9.9.9.9" };
    const first = proxy(new NextRequest("http://localhost:3000/api/plot/agent-runs", { method: "POST", headers }));
    expect(first.status).not.toBe(429);
    let limited: Response | undefined;
    for (let attempt = 0; attempt < 60; attempt += 1) {
      const response = proxy(new NextRequest("http://localhost:3000/api/plot/agent-runs", { method: "POST", headers }));
      if (response.status === 429) {
        limited = response;
        break;
      }
    }
    expect(limited?.status).toBe(429);
    const retryAfter = Number(limited?.headers.get("retry-after"));
    expect(retryAfter).toBeGreaterThan(0);
    expect(retryAfter).toBeLessThanOrEqual(60);
    expect(limited?.headers.get("cache-control")).toContain("no-store");
  });

  it("does not rate-limit reads", () => {
    const response = proxy(new NextRequest("http://localhost:3000/api/plot/artifacts", { headers: { host: "localhost:3000", "x-forwarded-for": "9.9.9.10" } }));
    expect(response.status).not.toBe(429);
  });

});


it("opens Home at the authenticated app root while preserving explicit chat links", () => {
  const root = new NextRequest("http://localhost:3000/", { headers: { host: "localhost:3000" } });
  root.cookies.set(SESSION_COOKIE, "test-session");
  expect(proxy(root).headers.get("x-middleware-rewrite")).toBe("http://localhost:3000/home");

  const chat = new NextRequest("http://localhost:3000/chat?chat=session-1&agent=agent-1", { headers: { host: "localhost:3000" } });
  chat.cookies.set(SESSION_COOKIE, "test-session");
  expect(proxy(chat).headers.get("x-middleware-rewrite")).toBeNull();
  expect(proxy(chat).headers.get("location")).toBeNull();
});
