import { describe, expect, it, vi } from "vitest";

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
    vi.useFakeTimers();
    try {
      const limiter = createFixedWindowLimiter(5, 1);
      expect(limiter.check("a")).toBe(false);
      expect(limiter.check("a")).toBe(true);
      vi.advanceTimersByTime(10);
      expect(limiter.check("a")).toBe(false);
      expect(limiter.retryAfterMs("a")).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it("reports a retry delay only while the key is limited", () => {
    const limiter = createFixedWindowLimiter(60_000, 1);
    expect(limiter.retryAfterMs("missing")).toBe(0);
    expect(limiter.check("a")).toBe(false);
    expect(limiter.retryAfterMs("a")).toBe(0);
    expect(limiter.check("a")).toBe(true);
    const delay = limiter.retryAfterMs("a");
    expect(delay).toBeGreaterThan(0);
    expect(delay).toBeLessThanOrEqual(60_000);
  });
});
