import { describe, expect, it, vi } from "vitest";

vi.mock("@workos-inc/authkit-nextjs", () => ({
  getWorkOS: vi.fn(() => ({ userManagement: {} })),
  saveSession: vi.fn(),
}));

vi.mock("next/headers", () => ({
  cookies: vi.fn(),
}));

import { GET } from "./route";

describe("WorkOS callback route", () => {
  it("exposes the Plot-owned OAuth callback handler", () => {
    expect(GET).toBeTypeOf("function");
  });
});
