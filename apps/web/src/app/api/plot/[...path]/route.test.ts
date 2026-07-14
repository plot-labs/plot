import { describe, expect, it, vi } from "vitest";

import { proxyPlotRequest } from "./route";

describe("Plot same-origin proxy", () => {
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
