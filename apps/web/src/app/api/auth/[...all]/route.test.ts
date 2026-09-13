import { beforeEach, describe, expect, it, vi } from "vitest";

const fetchMock = vi.fn<typeof fetch>();

import { GET, POST } from "./route";

describe("retired auth catch-all route", () => {
  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
    delete process.env.PLOT_API_BASE_URL;
  });

  it.each([GET, POST])("does not proxy unsupported auth requests to Kotlin", async (handler) => {
    const response = await handler(new Request("http://127.0.0.1:3000/api/auth/session", { method: handler === GET ? "GET" : "POST" }));

    expect(response.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
