import { afterEach, describe, expect, it } from "vitest";

import { config, hasOnlyLoopbackAuthorities, proxy } from "./proxy";

const request = (host: string | null, forwardedHost: string | null = null) => ({
  headers: new Headers([
    ...(host ? [["host", host] as [string, string]] : []),
    ...(forwardedHost ? [["x-forwarded-host", forwardedHost] as [string, string]] : []),
  ]),
});

describe("loopback request guard", () => {
  afterEach(() => delete process.env.PLOT_CERTIFICATION_LOOPBACK_GUARD);

  it("accepts loopback Host and forwarded-host chains", () => {
    expect(hasOnlyLoopbackAuthorities(request("127.0.0.1:3000") as never)).toBe(true);
    expect(hasOnlyLoopbackAuthorities(request("[::1]:3000", "localhost:3000, 127.0.0.1:3000") as never)).toBe(true);
  });

  it("rejects missing external and mixed authorities in certification mode", () => {
    process.env.PLOT_CERTIFICATION_LOOPBACK_GUARD = "true";
    expect(hasOnlyLoopbackAuthorities(request(null) as never)).toBe(false);
    expect(hasOnlyLoopbackAuthorities(request("external.invalid") as never)).toBe(false);
    expect(hasOnlyLoopbackAuthorities(request("127.0.0.1:3000", "external.invalid") as never)).toBe(false);
		expect(hasOnlyLoopbackAuthorities(request("[::1]evil") as never)).toBe(false);
		expect(hasOnlyLoopbackAuthorities(request("[::1]:abc") as never)).toBe(false);
		expect(hasOnlyLoopbackAuthorities(request("127.0.0.1:evil") as never)).toBe(false);
		expect(hasOnlyLoopbackAuthorities(request("127.0.0.1:65536") as never)).toBe(false);
    expect((proxy(request("external.invalid") as never) as Response).status).toBe(421);
  });

  it("keeps application and API routes behind the proxy without matching static assets", () => {
    expect(config.matcher).toContain("_next/static");
    expect(config.matcher).toContain(".*\\.[^/]+$");
    expect(config.matcher).not.toBe("/:path*");
  });
});
