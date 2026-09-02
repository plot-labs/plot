import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

describe("local development config", () => {
  it("uses one loopback host for Next.js and auth", () => {
    const readEnv = (path: string) =>
      Object.fromEntries(
        readFileSync(new URL(path, import.meta.url), "utf8")
          .split("\n")
          .filter((line) => line && !line.startsWith("#"))
          .map((line) => line.split("=", 2)),
      );
    const webEnv = readEnv("../.env.example");
    const apiEnv = readEnv("../../api/.env.example");
    const appUrl = new URL(webEnv.NEXT_PUBLIC_APP_URL);
    const apiUrl = new URL(webEnv.PLOT_API_BASE_URL);
    const packageJson = JSON.parse(
      readFileSync(new URL("../package.json", import.meta.url), "utf8"),
    ) as { scripts: { dev: string } };

    expect(packageJson.scripts.dev).toContain(`--hostname ${appUrl.hostname}`);
    expect(webEnv.PLOT_API_BASE_URL).toBe(apiUrl.origin);
    expect(apiEnv.PLOT_AUTH_ISSUER).toBe(appUrl.origin);
    expect(apiEnv.PLOT_AUTH_APP_ORIGIN).toBe(appUrl.origin);
    expect(apiEnv.PLOT_AUTH_API_ORIGIN).toBe(appUrl.origin);
  });
});
