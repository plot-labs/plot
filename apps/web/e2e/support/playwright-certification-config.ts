import path from "node:path";

import { defineConfig, devices } from "@playwright/test";

import type { BrowserCertificationConfig } from "./certification-manifest";

export function createCertificationPlaywrightConfig(certification: BrowserCertificationConfig) {
  const realSource = certification.mode === "real-source";
  return defineConfig({
    testDir: path.resolve(__dirname, ".."),
    testMatch: "production-generation-smoke.spec.ts",
    fullyParallel: false,
    workers: 1,
    retries: 0,
    forbidOnly: true,
    timeout: 330_000,
    expect: { timeout: 15_000 },
    preserveOutput: realSource ? "never" : "failures-only",
    outputDir: path.join(certification.outputRoot, ".playwright-transient"),
    reporter: realSource
      ? [[path.resolve(__dirname, "redacted-reporter.ts")]]
      : [["line"]],
    use: {
      baseURL: certification.baseUrl,
      acceptDownloads: false,
      storageState: undefined,
      trace: realSource ? "off" : "retain-on-failure",
      screenshot: realSource ? "off" : "only-on-failure",
      video: realSource ? "off" : "retain-on-failure",
      permissions: ["clipboard-read", "clipboard-write"],
    },
    projects: [
      {
        name: "chromium-certification",
        use: { ...devices["Desktop Chrome"], browserName: "chromium" },
      },
    ],
  });
}
