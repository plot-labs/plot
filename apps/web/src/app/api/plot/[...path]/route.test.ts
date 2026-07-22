import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";

import { proxyPlotRequest } from "./route";

describe("Plot same-origin proxy", () => {
  const previousAllowedEmails = process.env.AUTH_ALLOWED_EMAILS;

  beforeAll(() => {
    process.env.AUTH_ALLOWED_EMAILS = "member@example.com";
  });

  afterAll(() => {
    if (previousAllowedEmails === undefined) delete process.env.AUTH_ALLOWED_EMAILS;
    else process.env.AUTH_ALLOWED_EMAILS = previousAllowedEmails;
  });

  it("uses the server JWT and never forwards browser credentials", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ ok: true }));
    const request = new Request("http://web.test/api/plot/sessions", {
      method: "POST",
      headers: {
        Authorization: "Bearer forged",
        Cookie: "better-auth.session_token=browser-session",
        Origin: "http://web.test",
        "X-Plot-Workspace-Id": "018fd000-0000-7000-8000-000000000002",
      },
      body: "{}",
    });

    const response = await proxyPlotRequest(request, ["sessions"], {
      fetch: fetcher,
      getSession: async () => ({ user: { email: "member@example.com" } }),
      getServerJwt: async () => "server-issued-jwt",
    });

    expect(response.status).toBe(200);
    const initHeaders = new Headers(fetcher.mock.calls[0]?.[1]?.headers);
    expect(initHeaders.get("authorization")).toBe("Bearer server-issued-jwt");
    expect(initHeaders.get("cookie")).toBeNull();
    expect(initHeaders.get("x-plot-workspace-id")).toBe("018fd000-0000-7000-8000-000000000002");
  });

  it("rejects an expired or missing Better Auth session before reaching Kotlin", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const request = new Request("http://web.test/api/plot/me");

    const response = await proxyPlotRequest(request, ["me"], {
      fetch: fetcher,
      getSession: async () => null,
      getServerJwt: async () => "should-not-be-called",
    });

    expect(response.status).toBe(401);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("allows the certification BFF bypass only on an explicit loopback run", async () => {
    process.env.PLOT_CERTIFICATION_LOOPBACK_GUARD = "true";
    try {
      const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ ok: true }));
      const response = await proxyPlotRequest(
        new Request("http://127.0.0.1:3000/api/plot/sessions", { headers: { Host: "127.0.0.1:3000" } }),
        ["sessions"],
        { fetch: fetcher, baseUrl: "http://127.0.0.1:8080" },
      );

      expect(response.status).toBe(200);
      expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("authorization")).toBe("Bearer certification-loopback");
    } finally {
      delete process.env.PLOT_CERTIFICATION_LOOPBACK_GUARD;
    }
  });

  it("does not enable the certification BFF bypass for non-loopback authorities", async () => {
    process.env.PLOT_CERTIFICATION_LOOPBACK_GUARD = "true";
    try {
      const fetcher = vi.fn<typeof fetch>();
      const response = await proxyPlotRequest(
        new Request("https://app.useplot.xyz/api/plot/sessions", { headers: { Host: "app.useplot.xyz" } }),
        ["sessions"],
        { fetch: fetcher, getSession: async () => null },
      );

      expect(response.status).toBe(401);
      expect(fetcher).not.toHaveBeenCalled();
    } finally {
      delete process.env.PLOT_CERTIFICATION_LOOPBACK_GUARD;
    }
  });

  it("rejects cross-origin state changes", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const request = new Request("http://web.test/api/plot/sessions", {
      method: "POST",
      headers: { Origin: "https://attacker.test" },
    });

    const response = await proxyPlotRequest(request, ["sessions"], {
      fetch: fetcher,
      getSession: async () => ({ user: { email: "member@example.com" } }),
      getServerJwt: async () => "server-issued-jwt",
    });

    expect(response.status).toBe(403);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("allows only declared paths and strips hop-by-hop headers", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response("{}", {
      status: 202,
      headers: {
        "Cache-Control": "no-store",
        Connection: "keep-alive",
        Location: "http://127.0.0.1:8080/api/generations/run-1",
        "Content-Type": "application/json",
      },
    }));
    const request = new Request("http://web.test/api/plot/generations", {
      method: "POST",
      headers: { Connection: "close", "Idempotency-Key": "key", "X-Upstream-Url": "https://attacker.test" },
      body: "{}",
    });

    const response = await proxyPlotRequest(request, ["generations"], { fetch: fetcher, baseUrl: "http://127.0.0.1:8080" });

    expect(response.status).toBe(202);
    expect(response.headers.get("location")).toBe("/api/plot/generations/run-1");
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(response.headers.get("connection")).toBeNull();
    const [url, init] = fetcher.mock.calls[0]!;
    expect(String(url)).toBe("http://127.0.0.1:8080/api/generations");
    expect(new Headers(init?.headers).get("connection")).toBeNull();
    expect(new Headers(init?.headers).get("x-upstream-url")).toBeNull();
  });

  it("returns a private no-store error when the upstream is unavailable", async () => {
    const response = await proxyPlotRequest(
      new Request("http://web.test/api/plot/generations/run-1"),
      ["generations", "run-1"],
      { fetch: vi.fn<typeof fetch>().mockRejectedValue(new Error("private upstream detail")) },
    );

    expect(response.status).toBe(502);
    expect(response.headers.get("cache-control")).toBe("no-store");
    await expect(response.json()).resolves.toEqual({
      error: "PLOT_UPSTREAM_UNAVAILABLE",
      message: "Plot API is unavailable",
    });
  });

  it.each([
    ["github/connections", ["github", "connections"]],
    ["blocks?sourceScopeId=scope-1&page=0&size=100", ["blocks"]],
  ])("allows the read-only reference discovery route %s", async (pathAndQuery, path) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ items: [] }));
    const response = await proxyPlotRequest(
      new Request(`http://web.test/api/plot/${pathAndQuery}`),
      path,
      { fetch: fetcher, baseUrl: "http://127.0.0.1:8080" },
    );
    expect(response.status).toBe(200);
    expect(String(fetcher.mock.calls[0]?.[0])).toBe(`http://127.0.0.1:8080/api/${pathAndQuery}`);
  });

  it("turns a browser GitHub callback into a state-free Integrations redirect", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ connectionId: "018fd000-0000-7000-8000-000000000002" }));
    const response = await proxyPlotRequest(
      new Request("http://web.test/api/plot/github/installations/callback?state=private-state&installation_id=77"),
      ["github", "installations", "callback"],
      { fetch: fetcher, baseUrl: "http://127.0.0.1:8080" },
    );

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe("http://web.test/integrations?githubConnection=018fd000-0000-7000-8000-000000000002");
    expect(String(fetcher.mock.calls[0]?.[0])).toContain("state=private-state");
  });

  it("maps GitHub callback failures to generic Integrations redirects", async () => {
    const response = await proxyPlotRequest(
      new Request("http://web.test/api/plot/github/installations/callback?state=expired&installation_id=77"),
      ["github", "installations", "callback"],
      { fetch: vi.fn<typeof fetch>().mockResolvedValue(Response.json({ error: "INVALID_GITHUB_STATE" }, { status: 400 })), baseUrl: "http://127.0.0.1:8080" },
    );

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe("http://web.test/integrations?githubError=invalid");
  });

  it("passes through generation event streams without buffering", async () => {
    const body = new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode("event: checkpoint\n\n"));
        controller.close();
      },
    });
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(body, {
      headers: { "Content-Type": "text/event-stream" },
    }));

    const response = await proxyPlotRequest(
      new Request("http://web.test/api/plot/generations/run-1/events"),
      ["generations", "run-1", "events"],
      { fetch: fetcher, baseUrl: "http://127.0.0.1:8080" },
    );

    expect(response.headers.get("content-type")).toContain("text/event-stream");
    expect(response.headers.get("x-accel-buffering")).toBe("no");
    expect(response.body).toBe(body);
  });

  it.each([
    ["GET", ["https:", "attacker.test"]],
    ["DELETE", ["generations", "run-1"]],
    ["POST", ["admin"]],
    ["GET", ["..", "secrets"]],
  ])("rejects arbitrary %s %o", async (method, path) => {
    const fetcher = vi.fn<typeof fetch>();
    const response = await proxyPlotRequest(new Request("http://web.test/api/plot/x", { method }), path, { fetch: fetcher });
    expect(response.status).toBe(404);
    expect(fetcher).not.toHaveBeenCalled();
  });
});
