import { beforeEach, describe, expect, it, vi } from "vitest";

const refreshSessionMock = vi.hoisted(() => vi.fn());

vi.mock("@workos-inc/authkit-nextjs", () => ({
  refreshSession: refreshSessionMock,
}));

import { POST } from "./route";

describe("WorkOS organization session refresh route", () => {
  beforeEach(() => {
    refreshSessionMock.mockReset();
  });

  it("refreshes the managed session with the bootstrapped organization", async () => {
    refreshSessionMock.mockResolvedValue({ user: { id: "user-1" }, organizationId: "org-1" });

    const response = await POST(new Request("http://plot.test/api/auth/refresh-organization", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ organizationId: "org-1" }),
    }));

    expect(refreshSessionMock).toHaveBeenCalledWith({ organizationId: "org-1" });
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({ organizationId: "org-1" });
  });

  it("rejects malformed organization identifiers before contacting WorkOS", async () => {
    const response = await POST(new Request("http://plot.test/api/auth/refresh-organization", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ organizationId: "org/other" }),
    }));

    expect(response.status).toBe(400);
    expect(refreshSessionMock).not.toHaveBeenCalled();
  });

  it("returns the provider-safe unavailable response when refresh fails", async () => {
    refreshSessionMock.mockRejectedValue({ status: 503 });

    const response = await POST(new Request("http://plot.test/api/auth/refresh-organization", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ organizationId: "org-1" }),
    }));

    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toEqual({
      error: "AUTH_PROVIDER_UNAVAILABLE",
      message: "Authentication service is unavailable",
    });
  });
});
