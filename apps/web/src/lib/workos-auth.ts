export type WorkOSWebEnvironment = Record<string, string | undefined>;

export type WorkOSWebConfig = {
  enabled: boolean;
  clientId: string;
  redirectUri: string;
  appOrigin: string;
  allowedOrigins: string[];
  sessionCookieName: string;
};

const DEFAULT_RETURN_PATH = "/auth/complete";
const SAFE_COOKIE_NAME = /^[A-Za-z0-9._-]+$/;

function required(value: string | undefined, name: string): string {
  if (!value?.trim()) throw new Error(name + " is required when WorkOS auth is enabled");
  return value.trim();
}

function parseBoolean(value: string | undefined, fallback: boolean): boolean {
  if (value === undefined || value.trim() === "") return fallback;
  return value.trim().toLowerCase() === "true";
}

function parseOrigins(value: string | undefined): string[] {
  return Array.from(new Set(
    (value ?? "")
      .split(",")
      .map((origin) => origin.trim())
      .filter(Boolean),
  ));
}

function assertHttpUrl(value: string, name: string): string {
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(name + " must be an absolute http(s) URL");
  }
  if (!["http:", "https:"].includes(parsed.protocol) || !parsed.hostname) {
    throw new Error(name + " must be an absolute http(s) URL");
  }
  if (parsed.protocol === "http:" && !["localhost", "127.0.0.1", "[::1]"].includes(parsed.hostname)) {
    throw new Error(name + " may use http only on loopback hosts");
  }
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error(name + " must not contain credentials, query parameters, or fragments");
  }
  return parsed.toString().replace(/\/$/, "");
}

export function parseWorkOSWebConfig(environment: WorkOSWebEnvironment = process.env): WorkOSWebConfig {
  const enabled = parseBoolean(environment.WORKOS_AUTH_ENABLED ?? environment.PLOT_WORKOS_ENABLED, false);
  const clientId = enabled ? required(environment.WORKOS_CLIENT_ID, "WORKOS_CLIENT_ID") : (environment.WORKOS_CLIENT_ID ?? "").trim();
  if (enabled) {
    required(environment.WORKOS_API_KEY, "WORKOS_API_KEY");
    const cookiePassword = required(environment.WORKOS_COOKIE_PASSWORD, "WORKOS_COOKIE_PASSWORD");
    if (cookiePassword.length < 32) {
      throw new Error("WORKOS_COOKIE_PASSWORD must contain at least 32 characters");
    }
  }
  const configuredRedirectUri = environment.WORKOS_REDIRECT_URI ?? environment.NEXT_PUBLIC_WORKOS_REDIRECT_URI;
  const redirectUri = enabled
    ? assertHttpUrl(required(configuredRedirectUri, "NEXT_PUBLIC_WORKOS_REDIRECT_URI"), "NEXT_PUBLIC_WORKOS_REDIRECT_URI")
    : (configuredRedirectUri ?? "").trim();
  const appOrigin = enabled
    ? assertHttpUrl(required(environment.PLOT_APP_ORIGIN, "PLOT_APP_ORIGIN"), "PLOT_APP_ORIGIN")
    : (environment.PLOT_APP_ORIGIN ?? "").trim();
  const allowedOrigins = parseOrigins(environment.PLOT_WORKOS_ALLOWED_ORIGINS ?? environment.PLOT_APP_ORIGIN);
  if (enabled && allowedOrigins.length === 0) {
    throw new Error("PLOT_WORKOS_ALLOWED_ORIGINS is required when WorkOS auth is enabled");
  }
  const normalizedOrigins = allowedOrigins.map((origin) => assertHttpUrl(origin, "PLOT_WORKOS_ALLOWED_ORIGINS"));
  const sessionCookieName = (environment.PLOT_WORKOS_SESSION_COOKIE_NAME ?? "plot.session").trim();
  if (!SAFE_COOKIE_NAME.test(sessionCookieName)) {
    throw new Error("PLOT_WORKOS_SESSION_COOKIE_NAME contains unsafe characters");
  }

  return {
    enabled,
    clientId,
    redirectUri,
    appOrigin,
    allowedOrigins: normalizedOrigins,
    sessionCookieName,
  };
}

export function isAllowedWorkOSOrigin(origin: string | null | undefined, allowedOrigins: readonly string[]): boolean {
  if (!origin?.trim()) return false;
  const normalized = origin.trim().replace(/\/$/, "");
  return allowedOrigins.some((allowed) => allowed.replace(/\/$/, "") === normalized);
}

export function safeWorkOSReturnPath(value: string | null | undefined): string {
  if (!value?.startsWith("/") || value.startsWith("//")) return DEFAULT_RETURN_PATH;
  try {
    const parsed = new URL(value, "https://plot.invalid");
    if (parsed.origin !== "https://plot.invalid") return DEFAULT_RETURN_PATH;
    return parsed.pathname + parsed.search + parsed.hash;
  } catch {
    return DEFAULT_RETURN_PATH;
  }
}

export { DEFAULT_RETURN_PATH };
