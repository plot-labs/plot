import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

describe("local development config", () => {
  it("uses one loopback host for Next.js and WorkOS", () => {
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
    expect(webEnv.NEXT_PUBLIC_WORKOS_REDIRECT_URI).toBe(`${appUrl.origin}/auth/callback`);
    expect(webEnv.PLOT_WORKOS_ALLOWED_ORIGINS).toBe(appUrl.origin);
    expect(apiEnv.PLOT_WORKOS_ENABLED).toBe("false");
  });
});
