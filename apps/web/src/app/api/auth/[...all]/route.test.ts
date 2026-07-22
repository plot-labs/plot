import { beforeEach, describe, expect, it, vi } from "vitest";

const { authHandler } = vi.hoisted(() => ({
  authHandler: vi.fn<(request: Request) => Promise<Response>>(),
}));

vi.mock("@/lib/auth", () => ({
  // The production auth export is a lazy Proxy with an empty object target.
  // Accessing handler initializes Better Auth, while `"handler" in auth` is false.
  auth: new Proxy({}, {
    get(_target, property) {
      return property === "handler" ? authHandler : undefined;
    },
  }),
}));

import { GET, POST } from "./route";

describe("Better Auth Next.js route", () => {
  beforeEach(() => {
    authHandler.mockReset();
    authHandler.mockResolvedValue(new Response(null, { status: 204 }));
  });

  it.each([GET, POST])("delegates to the lazy auth handler", async (routeHandler) => {
    const request = new Request("http://127.0.0.1:3000/api/auth/test");

    const response = await routeHandler(request);

    expect(response.status).toBe(204);
    expect(authHandler).toHaveBeenCalledOnce();
    expect(authHandler).toHaveBeenCalledWith(request);
  });
});
