import { describe, expect, it, vi } from "vitest";

const withAuth = vi.hoisted(() => vi.fn());

vi.mock("@workos-inc/authkit-nextjs", () => ({ withAuth }));

import { fetchPlotAuthSession } from "@/lib/plot-auth";

describe("plot auth helpers", () => {
  it("reads the managed WorkOS session and keeps its access token server-side", async () => {
    withAuth.mockResolvedValue({
      user: {
        id: "user-1",
        email: "member@example.com",
        name: "Plot Member",
        profilePictureUrl: null,
      },
      accessToken: "workos-access-token",
      organizationId: "org-1",
    });

    const session = await fetchPlotAuthSession();

    expect(session?.user?.id).toBe("user-1");
    expect(session?.accessToken).toBe("workos-access-token");
    expect(session?.organizationId).toBe("org-1");
  });

  it("returns no session when AuthKit has no authenticated user", async () => {
    withAuth.mockResolvedValue({ user: null });
    await expect(fetchPlotAuthSession()).resolves.toBeNull();
  });
});
