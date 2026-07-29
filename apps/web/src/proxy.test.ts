import { describe, expect, it } from "vitest";

import { config } from "./proxy";

describe("application proxy", () => {
  it("matches application and API routes without intercepting static assets", () => {
    expect(config.matcher).toContain("_next/static");
    expect(config.matcher).toContain(".*\\.[^/]+$");
    expect(config.matcher).not.toBe("/:path*");
  });
});
