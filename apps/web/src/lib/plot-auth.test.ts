import { describe, expect, it, vi } from "vitest";

import { fetchPlotAuthSession, fetchPlotAuthToken } from "@/lib/plot-auth";

describe("plot auth helpers", () => {
  it("fetches session and token from the Kotlin API", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json({ user: { id: "user-1", email: "member@example.com" } }))
      .mockResolvedValueOnce(Response.json({ token: "jwt-token" }));
    vi.stubGlobal("fetch", fetcher);

    const cookie = "plot.session=abc123";
    const session = await fetchPlotAuthSession(cookie);
    const token = await fetchPlotAuthToken(cookie);

    expect(session?.user?.id).toBe("user-1");
    expect(token).toBe("jwt-token");
  });
});
