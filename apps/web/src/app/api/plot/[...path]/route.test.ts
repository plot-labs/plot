import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";

import { proxyPlotRequest, serverJwtPayload } from "./route";

describe("Plot same-origin proxy", () => {
  const previousAllowedEmails = process.env.AUTH_ALLOWED_EMAILS;

  beforeAll(() => {
    process.env.AUTH_ALLOWED_EMAILS = "member@example.com";
  });

  afterAll(() => {
    if (previousAllowedEmails === undefined) delete process.env.AUTH_ALLOWED_EMAILS;
    else process.env.AUTH_ALLOWED_EMAILS = previousAllowedEmails;
  });

  it("builds Kotlin JWT claims from the verified Better Auth session", () => {
    expect(serverJwtPayload({ user: { id: " auth-user ", email: " Member@Example.com ", name: " Plot Member " } })).toEqual({
      sub: "auth-user",
      email: "member@example.com",
      name: "Plot Member",
    });
    expect(serverJwtPayload({ user: { id: "auth-user", email: null } })).toBeNull();
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

  it("uses the browser-facing Host header for local same-origin checks", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ ok: true }));
    const request = new Request("http://localhost:3000/api/plot/account/bootstrap", {
      method: "POST",
      headers: { Host: "127.0.0.1:3000", Origin: "http://127.0.0.1:3000" },
    });

    const response = await proxyPlotRequest(request, ["account", "bootstrap"], {
      fetch: fetcher,
      getSession: async () => ({ user: { email: "member@example.com" } }),
      getServerJwt: async () => "server-issued-jwt",
    });

    expect(response.status).toBe(200);
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it("allows only declared paths and strips hop-by-hop headers", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response("{}", {
      status: 202,
      headers: {
        "Cache-Control": "no-store",
        Connection: "keep-alive",
        Location: "http://127.0.0.1:8080/api/agent-runs/run-1",
        "Content-Type": "application/json",
      },
    }));
    const request = new Request("http://web.test/api/plot/agent-runs", {
      method: "POST",
      headers: { Connection: "close", "Idempotency-Key": "key", "X-Upstream-Url": "https://attacker.test" },
      body: "{}",
    });

    const response = await proxyPlotRequest(request, ["agent-runs"], { fetch: fetcher, baseUrl: "http://127.0.0.1:8080" });

    expect(response.status).toBe(202);
    expect(response.headers.get("location")).toBe("/api/plot/agent-runs/run-1");
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(response.headers.get("connection")).toBeNull();
    const [url, init] = fetcher.mock.calls[0]!;
    expect(String(url)).toBe("http://127.0.0.1:8080/api/agent-runs");
    expect(new Headers(init?.headers).get("connection")).toBeNull();
    expect(new Headers(init?.headers).get("x-upstream-url")).toBeNull();
  });

  it("returns a private no-store error when the upstream is unavailable", async () => {
    const response = await proxyPlotRequest(
      new Request("http://web.test/api/plot/agent-runs/00000000-0000-0000-0000-000000000001"),
      ["agent-runs", "00000000-0000-0000-0000-000000000001"],
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

  it.each([
    ["POST", ["workspaces"]],
    ["PATCH", ["workspaces", "018fd000-0000-7000-8000-000000000002"]],
    ["GET", ["routines"]],
    ["GET", ["routines", "018fd000-0000-7000-8000-000000000002"]],
    ["GET", ["routines", "018fd000-0000-7000-8000-000000000002", "agent-runs", "018fd000-0000-7000-8000-000000000003"]],
    ["GET", ["sessions", "018fd000-0000-7000-8000-000000000002", "agent-runs"]],
    ["POST", ["routines"]],
    ["PATCH", ["routines", "018fd000-0000-7000-8000-000000000002"]],
    ["POST", ["routines", "018fd000-0000-7000-8000-000000000002", "run"]],
    ["POST", ["agent-runs"]],
    ["GET", ["agent-runs", "018fd000-0000-7000-8000-000000000003"]],
    ["GET", ["artifacts"]],
    ["GET", ["artifacts", "artifact-1"]],
    ["GET", ["artifact-variants", "variant-1"]],
    ["GET", ["artifact-variants", "variant-1", "history"]],
    ["GET", ["artifact-variants", "variant-1", "history", "revision-1"]],
    ["GET", ["artifact-variants", "variant-1", "history", "at", "0"]],
    ["PATCH", ["artifact-variants", "variant-1"]],
    ["PUT", ["artifact-variants", "variant-1"]],
    ["PATCH", ["artifact-variants", "variant-1", "sentences", "sentence-1"]],
    ["POST", ["artifact-variants", "variant-1", "exports"]],
  ])("allows the explicit artifact route %s %o", async (method, path) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ ok: true }));
    const request = new Request(`http://web.test/api/plot/${path.join("/")}`, {
      method,
      ...(method === "GET" ? {} : { headers: { Origin: "http://web.test", "Content-Type": "application/json" }, body: "{}" }),
    });
    const response = await proxyPlotRequest(request, path, { fetch: fetcher, baseUrl: "http://127.0.0.1:8080" });

    expect(response.status).toBe(200);
    expect(String(fetcher.mock.calls[0]?.[0])).toBe(`http://127.0.0.1:8080/api/${path.join("/")}`);
  });

  it.each([
    [
      "GET",
      ["github", "repositories", "scope-1", "release-activity"],
      "http://127.0.0.1:8080/api/github/repositories/scope-1/release-activity",
    ],
    [
      "POST",
      ["github", "repositories", "scope-1", "release-activity", "request-1", "retry"],
      "http://127.0.0.1:8080/api/github/repositories/scope-1/release-activity/request-1/retry",
    ],
  ])("allows only the explicit release activity %s route", async (method, path, expectedUrl) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ status: "QUEUED" }));
    const response = await proxyPlotRequest(
      new Request("http://web.test/api/plot/release", {
        method,
        headers: method === "POST" ? { Origin: "http://web.test" } : undefined,
      }),
      path,
      { fetch: fetcher, baseUrl: "http://127.0.0.1:8080" },
    );

    expect(response.status).toBe(200);
    expect(String(fetcher.mock.calls[0]?.[0])).toBe(expectedUrl);
  });

  it("allows the explicit GitHub access recheck route", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ status: "QUEUED" }));
    const response = await proxyPlotRequest(
      new Request("http://web.test/api/plot/github/repositories/scope-1/access-check?trigger=RETRY", {
        method: "POST",
        headers: { Origin: "http://web.test" },
      }),
      ["github", "repositories", "scope-1", "access-check"],
      { fetch: fetcher, baseUrl: "http://127.0.0.1:8080" },
    );

    expect(response.status).toBe(200);
    expect(String(fetcher.mock.calls[0]?.[0])).toBe("http://127.0.0.1:8080/api/github/repositories/scope-1/access-check?trigger=RETRY");
  });

  it.each([
    ["GET", ["github", "repositories", "scope-1", "monitoring"]],
    ["POST", ["github", "repositories", "scope-1", "monitoring", "retry"]],
  ])("allows the explicit repository monitoring %s route", async (method, path) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ status: "QUEUED" }));
    const response = await proxyPlotRequest(
      new Request("http://web.test/api/plot/github/repositories/scope-1/monitoring", {
        method,
        headers: method === "POST" ? { Origin: "http://web.test" } : undefined,
      }),
      path,
      { fetch: fetcher, baseUrl: "http://127.0.0.1:8080" },
    );

    expect(response.status).toBe(200);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("does not expose a generic GitHub release activity proxy", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const response = await proxyPlotRequest(
      new Request("http://web.test/api/plot/github/repositories/scope-1/release-activity/request-1", {
        method: "DELETE",
        headers: { Origin: "http://web.test" },
      }),
      ["github", "repositories", "scope-1", "release-activity", "request-1"],
      { fetch: fetcher },
    );

    expect(response.status).toBe(404);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("turns a browser GitHub callback into a state-free Integrations redirect", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ connectionId: "018fd000-0000-7000-8000-000000000002" }));
    const response = await proxyPlotRequest(
      new Request("http://localhost:3000/api/plot/github/installations/callback?state=private-state&installation_id=77", {
        headers: { Host: "127.0.0.1:3000" },
      }),
      ["github", "installations", "callback"],
      { fetch: fetcher, baseUrl: "http://127.0.0.1:8080" },
    );

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe("http://127.0.0.1:3000/settings/integrations?githubConnection=018fd000-0000-7000-8000-000000000002");
    expect(String(fetcher.mock.calls[0]?.[0])).toContain("state=private-state");
  });

  it("maps GitHub callback failures to generic Integrations redirects", async () => {
    const response = await proxyPlotRequest(
      new Request("http://web.test/api/plot/github/installations/callback?state=expired&installation_id=77"),
      ["github", "installations", "callback"],
      { fetch: vi.fn<typeof fetch>().mockResolvedValue(Response.json({ error: "INVALID_GITHUB_STATE" }, { status: 400 })), baseUrl: "http://127.0.0.1:8080" },
    );

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe("http://web.test/settings/integrations?githubError=invalid");
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
