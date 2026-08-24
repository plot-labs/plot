import { describe, expect, it } from "vitest";

import { isSafeHttpUrl } from "./safe-url";

describe("isSafeHttpUrl", () => {
  it("accepts http and https URLs", () => {
    expect(isSafeHttpUrl("https://github.com/acme/plot/pull/184")).toBe(true);
    expect(isSafeHttpUrl("http://example.com/page")).toBe(true);
  });

  it("rejects script-bearing schemes", () => {
    expect(isSafeHttpUrl("javascript:alert(1)")).toBe(false);
    expect(isSafeHttpUrl("JaVaScRiPt:alert(1)")).toBe(false);
    expect(isSafeHttpUrl("data:text/html,<script>alert(1)</script>")).toBe(false);
    expect(isSafeHttpUrl("vbscript:msgbox(1)")).toBe(false);
  });

  it("rejects unparseable and placeholder values", () => {
    expect(isSafeHttpUrl("#")).toBe(false);
    expect(isSafeHttpUrl("/relative/path")).toBe(false);
    expect(isSafeHttpUrl("not a url")).toBe(false);
    expect(isSafeHttpUrl("")).toBe(false);
  });

  it("rejects nullish input", () => {
    expect(isSafeHttpUrl(null)).toBe(false);
    expect(isSafeHttpUrl(undefined)).toBe(false);
  });
});
