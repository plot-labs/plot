import { beforeEach, describe, expect, it, vi } from "vitest";

const fetchMock = vi.fn<typeof fetch>();

import { GET, POST } from "./route";

describe("auth upstream proxy route", () => {
  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
    delete process.env.PLOT_API_BASE_URL;
  });

  it("proxies GET requests to the Kotlin auth API", async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ user: { id: "user-1" } }), {
      status: 200,
      headers: { "content-type": "application/json", "set-cookie": "plot.session=abc; Path=/" },
    }));

    const response = await GET(new Request("http://127.0.0.1:3000/api/auth/session", {
      headers: { cookie: "plot.session=abc" },
    }));

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledOnce();
    const upstreamUrl = new URL(String(fetchMock.mock.calls[0]?.[0]));
    expect(upstreamUrl.origin).toBe("http://127.0.0.1:8080");
    expect(upstreamUrl.pathname).toBe("/api/auth/session");
    expect(response.headers.get("set-cookie")).toContain("plot.session=abc");
  });

  it("proxies POST sign-out requests", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    const response = await POST(new Request("http://127.0.0.1:3000/api/auth/sign-out", {
      method: "POST",
      headers: { cookie: "plot.session=abc" },
    }));

    expect(response.status).toBe(204);
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe("POST");
  });
});
