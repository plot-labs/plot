import { describe, expect, it } from "vitest";

import type { BrowserCertificationConfig } from "./certification-manifest";
import { createCertificationPlaywrightConfig } from "./playwright-certification-config";

const config = (mode: "real-source" | "synthetic") => createCertificationPlaywrightConfig({
  mode,
  baseUrl: "http://127.0.0.1:3000",
  outputRoot: "/tmp/plot-browser-certification",
} as BrowserCertificationConfig);

describe("certification Playwright config", () => {
  it("runs one Chromium worker with no retry and no real-source diagnostics", () => {
    const real = config("real-source");
    expect(real.workers).toBe(1);
    expect(real.retries).toBe(0);
    expect(real.timeout).toBeGreaterThanOrEqual(330_000);
    expect(real.fullyParallel).toBe(false);
    expect(real.projects).toHaveLength(1);
    expect(real.projects?.[0]?.name).toBe("chromium-certification");
    expect(real.use).toMatchObject({ trace: "off", screenshot: "off", video: "off" });
    expect(real.preserveOutput).toBe("never");
    expect(JSON.stringify(real.reporter)).toContain("redacted-reporter.ts");
    expect(JSON.stringify(real.reporter)).not.toContain("html");
  });

  it("keeps diagnostics only in the explicit synthetic mode", () => {
    const synthetic = config("synthetic");
    expect(synthetic.use).toMatchObject({
      trace: "retain-on-failure",
      screenshot: "only-on-failure",
      video: "retain-on-failure",
    });
    expect(synthetic.reporter).toEqual([["line"]]);
  });
});
