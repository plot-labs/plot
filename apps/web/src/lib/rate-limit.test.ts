import { describe, expect, it } from "vitest";

import { createFixedWindowLimiter } from "./rate-limit";

describe("createFixedWindowLimiter", () => {
  it("allows up to max hits then blocks within the window", () => {
    const limiter = createFixedWindowLimiter(60_000, 3);
    expect(limiter.check("a")).toBe(false);
    expect(limiter.check("a")).toBe(false);
    expect(limiter.check("a")).toBe(false);
    expect(limiter.check("a")).toBe(true);
  });

  it("counts keys independently", () => {
    const limiter = createFixedWindowLimiter(60_000, 1);
    expect(limiter.check("a")).toBe(false);
    expect(limiter.check("b")).toBe(false);
    expect(limiter.check("a")).toBe(true);
  });

  it("resets after the window elapses", () => {
    const limiter = createFixedWindowLimiter(5, 1);
    expect(limiter.check("a")).toBe(false);
    expect(limiter.check("a")).toBe(true);
    return new Promise((resolve) => {
      setTimeout(() => {
        expect(limiter.check("a")).toBe(false);
        resolve(undefined);
      }, 10);
    });
  });
});
