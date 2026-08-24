export type FixedWindowLimiter = {
  /** Returns true when the key is over its allowance for the current window. */
  check: (key: string) => boolean;
};

/**
 * In-memory fixed-window counter. Best-effort by construction: on
 * multi-instance platforms each instance counts independently, so this caps
 * abuse volume rather than enforcing an exact quota — pair it with
 * platform-level protection where available.
 */
export function createFixedWindowLimiter(windowMs: number, max: number): FixedWindowLimiter {
  const hits = new Map<string, { count: number; expiresAt: number }>();

  return {
    check(key: string): boolean {
      const now = Date.now();
      // Bound memory against key-spray: drop expired entries once the table
      // grows past a comfortable size.
      if (hits.size > 10_000) {
        for (const [existing, hit] of hits) {
          if (hit.expiresAt <= now) hits.delete(existing);
        }
      }
      const hit = hits.get(key);
      if (!hit || hit.expiresAt <= now) {
        hits.set(key, { count: 1, expiresAt: now + windowMs });
        return false;
      }
      hit.count += 1;
      return hit.count > max;
    },
  };
}
