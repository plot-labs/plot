import { cookies } from "next/headers";
import {
  getWorkOS,
  saveSession,
} from "@workos-inc/authkit-nextjs";

import { isAllowedWorkOSOrigin } from "./workos-auth";

const PENDING_AUTH_COOKIE = "plot.workos.pending_auth";
const PENDING_AUTH_MAX_AGE_SECONDS = 10 * 60;
const PENDING_MAGIC_AUTH_COOKIE = "plot.workos.pending_magic_auth";
const PENDING_MAGIC_AUTH_MAX_AGE_SECONDS = 10 * 60;
const SOCIAL_AUTH_STATE_COOKIE = "plot.workos.social_state";
const SOCIAL_AUTH_STATE_MAX_AGE_SECONDS = 10 * 60;
const SOCIAL_AUTH_CALLBACK_PATH = "/auth/callback";
const MAX_EMAIL_LENGTH = 320;

export type WorkOSSocialAuthEntryPath = "/sign-in" | "/sign-up";

export type WorkOSSocialAuthState = {
  entryPath: WorkOSSocialAuthEntryPath;
  nonce: string;
  returnTo: string;
};

export type WorkOSMagicAuthEntryPath = "/sign-in" | "/sign-up";

export type WorkOSMagicAuthState = {
  email: string;
  entryPath: WorkOSMagicAuthEntryPath;
};

export type PendingAuthState =
  {
    kind: "authentication";
    email: string;
    pendingAuthenticationToken: string;
    userId?: string;
  };

export type WorkOSAuthFailure = {
  code?: string;
  email?: string;
  pendingAuthenticationToken?: string;
  status?: number;
  userId?: string;
};

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null ? value as Record<string, unknown> : null;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function numberValue(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

export function workOSClientId(): string {
  const clientId = process.env.WORKOS_CLIENT_ID?.trim();
  if (!clientId) throw new Error("WORKOS_NOT_CONFIGURED");
  return clientId;
}

export function workOSUserManagement() {
  return getWorkOS().userManagement;
}

export function requestAuthContext(request: Request): {
  ipAddress?: string;
  userAgent?: string;
} {
  const forwardedFor = request.headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  const userAgent = request.headers.get("user-agent")?.trim();
  return {
    ...(forwardedFor ? { ipAddress: forwardedFor } : {}),
    ...(userAgent ? { userAgent } : {}),
  };
}

export function isAllowedAuthRequestOrigin(request: Request): boolean {
  const candidate = request.headers.get("origin") ?? request.headers.get("referer");
  if (!candidate) return process.env.NODE_ENV !== "production";

  let origin: string;
  try {
    origin = new URL(candidate).origin;
  } catch {
    return false;
  }

  const configuredOrigins = [
    ...(process.env.PLOT_WORKOS_ALLOWED_ORIGINS ?? "").split(","),
    ...(process.env.PLOT_APP_ORIGIN ? [process.env.PLOT_APP_ORIGIN] : []),
  ].map((value) => value.trim()).filter(Boolean);
  return isAllowedWorkOSOrigin(origin, configuredOrigins.length ? configuredOrigins : [new URL(request.url).origin]);
}

export function csrfErrorResponse(): Response {
  return Response.json(
    { error: "CSRF_ORIGIN_REJECTED", message: "Request origin is not allowed" },
    { status: 403, headers: { "cache-control": "no-store" } },
  );
}

export function parseAuthBodyValue(value: unknown, maxLength: number): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim();
  return normalized && normalized.length <= maxLength ? normalized : null;
}

export function normalizeEmail(value: unknown): string | null {
  const email = parseAuthBodyValue(value, MAX_EMAIL_LENGTH)?.toLowerCase() ?? null;
  return email && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ? email : null;
}

export async function parseAuthBody(request: Request): Promise<Record<string, unknown> | null> {
  try {
    const body: unknown = await request.json();
    return record(body);
  } catch {
    return null;
  }
}

export async function saveWorkOSSession(
  authenticationResponse: Parameters<typeof saveSession>[0],
  request: Request,
): Promise<void> {
  await saveSession(authenticationResponse, request.url);
}

export function workOSAuthFailure(error: unknown): WorkOSAuthFailure {
  const candidate = record(error);
  const rawData = record(candidate?.rawData);
  const code =
    stringValue(candidate?.code) ??
    stringValue(candidate?.error) ??
    stringValue(rawData?.code) ??
    stringValue(rawData?.error);
  const pendingAuthenticationToken =
    stringValue(candidate?.pendingAuthenticationToken) ??
    stringValue(rawData?.pending_authentication_token);
  const email = stringValue(candidate?.email) ?? stringValue(rawData?.email);
  const userId = stringValue(rawData?.user_id) ?? stringValue(candidate?.userId);

  return {
    ...(code ? { code } : {}),
    ...(email ? { email } : {}),
    ...(pendingAuthenticationToken ? { pendingAuthenticationToken } : {}),
    ...(numberValue(candidate?.status) ? { status: numberValue(candidate?.status) } : {}),
    ...(userId ? { userId } : {}),
  };
}

export function authErrorResponse(error: unknown, fallbackMessage = "Authentication could not be completed") {
  const failure = workOSAuthFailure(error);
  const code = failure.code;
  const status = failure.status;

  if (code === "email_verification_required") {
    return Response.json(
      { error: "EMAIL_VERIFICATION_REQUIRED", message: "Check your email for a verification code." },
      { status: 202, headers: { "cache-control": "no-store" } },
    );
  }

  if (code === "user_already_exists" || code === "email_already_exists" || status === 409) {
    return Response.json(
      {
        error: "ACCOUNT_ALREADY_EXISTS",
        message: "An account with this email may already exist. Try signing in instead.",
      },
      { status: 409, headers: { "cache-control": "no-store" } },
    );
  }

  if (status === 429) {
    return Response.json(
      { error: "TOO_MANY_REQUESTS", message: "Too many attempts. Please try again shortly." },
      { status: 429, headers: { "cache-control": "no-store", "retry-after": "60" } },
    );
  }

  if (code === "WORKOS_NOT_CONFIGURED") {
    return Response.json(
      { error: "AUTH_PROVIDER_UNAVAILABLE", message: "Authentication service is unavailable" },
      { status: 503, headers: { "cache-control": "no-store" } },
    );
  }

  if (status !== undefined && status >= 500) {
    return Response.json(
      { error: "AUTH_PROVIDER_UNAVAILABLE", message: "Authentication service is unavailable" },
      { status: 503, headers: { "cache-control": "no-store" } },
    );
  }

  return Response.json(
    { error: "AUTHENTICATION_FAILED", message: fallbackMessage },
    { status: 400, headers: { "cache-control": "no-store" } },
  );
}

function encodePendingAuthState(state: PendingAuthState): string {
  return Buffer.from(JSON.stringify(state), "utf8").toString("base64url");
}

function decodePendingAuthState(value: string): PendingAuthState | null {
  try {
    const parsed: unknown = JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
    const candidate = record(parsed);
    if (candidate?.kind === "authentication") {
      const email = stringValue(candidate.email);
      const pendingAuthenticationToken = stringValue(candidate.pendingAuthenticationToken);
      const userId = stringValue(candidate.userId);
      return email && pendingAuthenticationToken
        ? {
            kind: "authentication",
            email,
            pendingAuthenticationToken,
            ...(userId ? { userId } : {}),
          }
        : null;
    }
  } catch {
    return null;
  }
  return null;
}

function encodePendingMagicAuthState(state: WorkOSMagicAuthState): string {
  return Buffer.from(JSON.stringify(state), "utf8").toString("base64url");
}

function decodePendingMagicAuthState(value: string): WorkOSMagicAuthState | null {
  try {
    const parsed: unknown = JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
    const candidate = record(parsed);
    const email = stringValue(candidate?.email);
    const entryPath = candidate?.entryPath;
    if (!email || (entryPath !== "/sign-in" && entryPath !== "/sign-up")) return null;
    return { email, entryPath };
  } catch {
    return null;
  }
}

function securePendingCookie(request?: Request): boolean {
  return request ? new URL(request.url).protocol === "https:" : false;
}

function encodeSocialAuthState(state: WorkOSSocialAuthState): string {
  return Buffer.from(JSON.stringify(state), "utf8").toString("base64url");
}

function decodeSocialAuthState(value: string): WorkOSSocialAuthState | null {
  try {
    const parsed: unknown = JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
    const candidate = record(parsed);
    const entryPath = candidate?.entryPath;
    const nonce = stringValue(candidate?.nonce);
    const returnTo = stringValue(candidate?.returnTo);
    if (
      (entryPath !== "/sign-in" && entryPath !== "/sign-up") ||
      !nonce ||
      !returnTo
    ) {
      return null;
    }
    return { entryPath, nonce, returnTo };
  } catch {
    return null;
  }
}

function workOSSocialCallbackUrl(request: Request): string {
  const configuredOrigin = process.env.PLOT_APP_ORIGIN?.trim();
  const appOrigin = configuredOrigin || new URL(request.url).origin;
  return new URL(
    SOCIAL_AUTH_CALLBACK_PATH,
    appOrigin.endsWith("/") ? appOrigin : `${appOrigin}/`,
  ).toString();
}

export async function workOSGitHubAuthorizationUrl(
  request: Request,
  options: { entryPath: WorkOSSocialAuthEntryPath; returnTo: string },
): Promise<string> {
  const nonce = crypto.randomUUID();
  const authorizationUrl = await workOSUserManagement().getAuthorizationUrl({
    clientId: workOSClientId(),
    provider: "GitHubOAuth",
    redirectUri: workOSSocialCallbackUrl(request),
    state: nonce,
  });
  const cookieStore = await cookies();
  cookieStore.set({
    name: SOCIAL_AUTH_STATE_COOKIE,
    value: encodeSocialAuthState({ ...options, nonce }),
    httpOnly: true,
    maxAge: SOCIAL_AUTH_STATE_MAX_AGE_SECONDS,
    path: "/",
    sameSite: "lax",
    secure: securePendingCookie(request),
  });
  return authorizationUrl;
}

export async function createWorkOSMagicAuth(
  request: Request,
  state: WorkOSMagicAuthState,
): Promise<void> {
  await workOSUserManagement().createMagicAuth({
    email: state.email,
    ...requestAuthContext(request),
  });
  await setPendingMagicAuthState(state, request);
}

export async function setPendingMagicAuthState(
  state: WorkOSMagicAuthState,
  request?: Request,
): Promise<void> {
  const cookieStore = await cookies();
  cookieStore.set({
    name: PENDING_MAGIC_AUTH_COOKIE,
    value: encodePendingMagicAuthState(state),
    httpOnly: true,
    maxAge: PENDING_MAGIC_AUTH_MAX_AGE_SECONDS,
    path: "/",
    sameSite: "lax",
    secure: securePendingCookie(request),
  });
}

export async function getPendingMagicAuthState(): Promise<WorkOSMagicAuthState | null> {
  const cookieStore = await cookies();
  const value = cookieStore.get(PENDING_MAGIC_AUTH_COOKIE)?.value;
  return value ? decodePendingMagicAuthState(value) : null;
}

export async function clearPendingMagicAuthState(): Promise<void> {
  const cookieStore = await cookies();
  cookieStore.delete(PENDING_MAGIC_AUTH_COOKIE);
}

export async function consumeWorkOSSocialAuthState(): Promise<WorkOSSocialAuthState | null> {
  const cookieStore = await cookies();
  const value = cookieStore.get(SOCIAL_AUTH_STATE_COOKIE)?.value;
  cookieStore.delete(SOCIAL_AUTH_STATE_COOKIE);
  return value ? decodeSocialAuthState(value) : null;
}

export async function setPendingAuthState(state: PendingAuthState, request?: Request): Promise<void> {
  const cookieStore = await cookies();
  cookieStore.set({
    name: PENDING_AUTH_COOKIE,
    value: encodePendingAuthState(state),
    httpOnly: true,
    maxAge: PENDING_AUTH_MAX_AGE_SECONDS,
    path: "/",
    sameSite: "lax",
    secure: securePendingCookie(request),
  });
}

export async function getPendingAuthState(): Promise<PendingAuthState | null> {
  const cookieStore = await cookies();
  const value = cookieStore.get(PENDING_AUTH_COOKIE)?.value;
  return value ? decodePendingAuthState(value) : null;
}

export async function clearPendingAuthState(): Promise<void> {
  const cookieStore = await cookies();
  cookieStore.delete(PENDING_AUTH_COOKIE);
}

export { PENDING_AUTH_COOKIE, PENDING_AUTH_MAX_AGE_SECONDS };
export { PENDING_MAGIC_AUTH_COOKIE, PENDING_MAGIC_AUTH_MAX_AGE_SECONDS };
export { SOCIAL_AUTH_STATE_COOKIE, SOCIAL_AUTH_STATE_MAX_AGE_SECONDS };
