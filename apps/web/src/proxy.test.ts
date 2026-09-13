import { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { SESSION_COOKIE } from "@/lib/plot-auth";

import { config, isGatedHost, proxy } from "./proxy";

const authkitProxyMock = vi.hoisted(() => vi.fn());

vi.mock("@workos-inc/authkit-nextjs", () => ({
  authkitProxy: authkitProxyMock,
}));

describe("application proxy", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    delete process.env.WORKOS_AUTH_ENABLED;
    delete process.env.PLOT_WORKOS_ENABLED;
  });

  afterEach(() => {
    delete process.env.WORKOS_AUTH_ENABLED;
    delete process.env.PLOT_WORKOS_ENABLED;
    delete process.env.WORKOS_CLIENT_ID;
    delete process.env.WORKOS_API_KEY;
    delete process.env.WORKOS_COOKIE_PASSWORD;
    delete process.env.WORKOS_COOKIE_NAME;
    delete process.env.PLOT_WORKOS_SESSION_COOKIE_NAME;
    delete process.env.NEXT_PUBLIC_WORKOS_REDIRECT_URI;
    delete process.env.PLOT_APP_ORIGIN;
    delete process.env.PLOT_WORKOS_ALLOWED_ORIGINS;
  });

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

  it("allows unauthenticated access to public changelog pages on gated hosts", async () => {
    const listRequest = new NextRequest("http://localhost:3000/acme/changelog", {
      headers: { host: "localhost:3000" },
    });
    const entryRequest = new NextRequest("http://localhost:3000/acme/changelog/v2.4.0", {
      headers: { host: "localhost:3000" },
    });

    expect((await proxy(listRequest)).status).toBe(200);
    expect((await proxy(entryRequest)).status).toBe(200);
  });

  it("allows unauthenticated access to sign-up on gated hosts", async () => {
    const request = new NextRequest("http://localhost:3000/sign-up", {
      headers: { host: "localhost:3000" },
    });

    expect((await proxy(request)).status).toBe(200);
  });

  it("allows the Plot-owned callback before a session cookie exists", async () => {
    const request = new NextRequest("http://localhost:3000/auth/callback", {
      headers: { host: "localhost:3000" },
    });

    expect((await proxy(request)).status).toBe(200);
  });

  it("redirects unauthenticated visitors away from gated app routes", async () => {
    const request = new NextRequest("http://localhost:3000/chat", {
      headers: { host: "localhost:3000" },
    });

    expect((await proxy(request)).status).toBe(307);
    expect((await proxy(request)).headers.get("location")).toBe("http://localhost:3000/sign-in");
  });

  it("rate-limits API writes per IP and answers Retry-After", async () => {
    const headers = { host: "localhost:3000", "x-forwarded-for": "9.9.9.9" };
    const first = await proxy(new NextRequest("http://localhost:3000/api/plot/agent-runs", { method: "POST", headers }));
    expect(first.status).not.toBe(429);
    let limited: Response | undefined;
    for (let attempt = 0; attempt < 60; attempt += 1) {
      const response = await proxy(new NextRequest("http://localhost:3000/api/plot/agent-runs", { method: "POST", headers }));
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

  it("does not rate-limit reads", async () => {
    const response = await proxy(new NextRequest("http://localhost:3000/api/plot/artifacts", { headers: { host: "localhost:3000", "x-forwarded-for": "9.9.9.10" } }));
    expect(response.status).not.toBe(429);
  });

  it("composes AuthKit session refresh with the existing route gate", async () => {
    process.env.WORKOS_AUTH_ENABLED = "true";
    process.env.WORKOS_CLIENT_ID = "client_test";
    process.env.WORKOS_API_KEY = "sk_test";
    process.env.WORKOS_COOKIE_PASSWORD = "12345678901234567890123456789012";
    process.env.NEXT_PUBLIC_WORKOS_REDIRECT_URI = "http://localhost:3000/auth/callback";
    process.env.PLOT_APP_ORIGIN = "http://localhost:3000";
    process.env.PLOT_WORKOS_ALLOWED_ORIGINS = "http://localhost:3000";
    authkitProxyMock.mockReturnValue(async () => NextResponse.next());

    const request = new NextRequest("http://localhost:3000/", { headers: { host: "localhost:3000" } });
    request.cookies.set(SESSION_COOKIE, "managed-session");
    const response = await proxy(request);

    expect(response.headers.get("x-middleware-rewrite")).toBe("http://localhost:3000/home");
    expect(authkitProxyMock).toHaveBeenCalledWith(expect.objectContaining({
      redirectUri: "http://localhost:3000/auth/callback",
      middlewareAuth: { enabled: false, unauthenticatedPaths: [] },
    }));
  });

  it("uses AuthKit's default cookie when custom cookie names are unset", async () => {
    process.env.WORKOS_AUTH_ENABLED = "true";
    process.env.NEXT_PUBLIC_WORKOS_REDIRECT_URI = "http://localhost:3000/auth/callback";
    authkitProxyMock.mockReturnValue(async () => NextResponse.next());

    const request = new NextRequest("http://localhost:3000/", { headers: { host: "localhost:3000" } });
    request.cookies.set("wos-session", "managed-session");
    const response = await proxy(request);

    expect(response.headers.get("x-middleware-rewrite")).toBe("http://localhost:3000/home");
    expect(authkitProxyMock).toHaveBeenCalledTimes(1);
  });
});


it("opens Home at the authenticated app root while preserving explicit chat links", async () => {
  const root = new NextRequest("http://localhost:3000/", { headers: { host: "localhost:3000" } });
  root.cookies.set(SESSION_COOKIE, "test-session");
  expect((await proxy(root)).headers.get("x-middleware-rewrite")).toBe("http://localhost:3000/home");

  const chat = new NextRequest("http://localhost:3000/chat?chat=session-1&agent=agent-1", { headers: { host: "localhost:3000" } });
  chat.cookies.set(SESSION_COOKIE, "test-session");
  expect((await proxy(chat)).headers.get("x-middleware-rewrite")).toBeNull();
  expect((await proxy(chat)).headers.get("location")).toBeNull();
});
