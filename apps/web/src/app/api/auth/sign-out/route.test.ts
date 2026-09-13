import { beforeEach, describe, expect, it, vi } from "vitest";

const signOutMock = vi.hoisted(() => vi.fn());

vi.mock("@workos-inc/authkit-nextjs", () => ({
  signOut: signOutMock,
}));

import { POST } from "./route";

describe("WorkOS sign-out route", () => {
  beforeEach(() => {
    signOutMock.mockReset();
    signOutMock.mockResolvedValue(undefined);
  });

  it("terminates the managed session and returns to sign-in", async () => {
    const request = new Request("http://plot.test/api/auth/sign-out", { method: "POST" });
    const response = await POST(request);

    expect(signOutMock).toHaveBeenCalledWith({ returnTo: "/sign-in" });
    expect(response.status).toBe(307);
    expect(response.headers.get("location")).toBe("http://plot.test/sign-in");
  });

  it("still leaves the browser signed out when provider logout fails", async () => {
    signOutMock.mockRejectedValue(new Error("provider unavailable"));

    const response = await POST(new Request("http://plot.test/api/auth/sign-out", { method: "POST" }));

    expect(response.status).toBe(307);
    expect(response.headers.get("location")).toBe("http://plot.test/sign-in");
  });
});
