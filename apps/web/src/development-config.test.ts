import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

describe("local development config", () => {
  it("uses one loopback host for Next.js and auth", () => {
    const env = Object.fromEntries(
      readFileSync(new URL("../../../.env.example", import.meta.url), "utf8")
        .split("\n")
        .filter((line) => line && !line.startsWith("#"))
        .map((line) => line.split("=", 2)),
    );
    const appUrl = new URL(env.NEXT_PUBLIC_APP_URL);
    const packageJson = JSON.parse(
      readFileSync(new URL("../package.json", import.meta.url), "utf8"),
    ) as { scripts: { dev: string } };

    expect(packageJson.scripts.dev).toContain(`--hostname ${appUrl.hostname}`);
    expect(env.BETTER_AUTH_URL).toBe(appUrl.origin);
    expect(env.PLOT_AUTH_JWKSURI).toBe(`${appUrl.origin}/api/auth/jwks`);
  });
});
