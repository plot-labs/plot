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

  it("redirects unauthenticated visitors away from gated app routes", () => {
    const request = new NextRequest("http://localhost:3000/chat", {
      headers: { host: "localhost:3000" },
    });

    expect(proxy(request).status).toBe(307);
    expect(proxy(request).headers.get("location")).toBe("http://localhost:3000/sign-in");
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
