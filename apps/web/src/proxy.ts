import { authkitProxy } from "@workos-inc/authkit-nextjs";
import { NextResponse, type NextRequest } from "next/server";

import { SESSION_COOKIE } from "@/lib/plot-auth";
import { createFixedWindowLimiter } from "@/lib/rate-limit";
import { isPublicChangelogPath } from "@/lib/public-changelog-url";

const gatedHosts = new Set(["app.useplot.xyz", "localhost", "127.0.0.1"]);
const previewHostPattern = /\.vercel\.app$/;

/**
 * Hosts where the app actually serves workspace content. The cookie check is
 * a UX gate — server-side layout guards are the real authorization — but
 * scoping it to the production host alone left preview deployments serving
 * the full app shell to anonymous visitors.
 */
export function isGatedHost(host: string): boolean {
  return gatedHosts.has(host) || previewHostPattern.test(host);
}

// State-changing API traffic is cheap to spam and expensive to serve (auth
// flows, AI triggers, upstream writes). Per-IP fixed window; best-effort per
// isolate, with platform-level protection as the outer layer. Rejections
// carry Retry-After so well-behaved clients back off instead of retrying hot.
const apiWriteLimiter = createFixedWindowLimiter(60 * 1000, 60);

function workOSAuthEnabled(): boolean {
  return [process.env.WORKOS_AUTH_ENABLED, process.env.PLOT_WORKOS_ENABLED]
    .some((value) => value?.trim().toLowerCase() === "true");
}

function workOSSessionCookieName(): string {
  return process.env.WORKOS_COOKIE_NAME?.trim() ||
    process.env.PLOT_WORKOS_SESSION_COOKIE_NAME?.trim() ||
    SESSION_COOKIE;
}

function workOSSessionRequest(request: NextRequest): boolean {
  if (!workOSAuthEnabled()) return false;
  if (request.nextUrl.pathname === "/auth/callback") return false;
  return request.cookies.has(workOSSessionCookieName());
}

async function refreshWorkOSSession(request: NextRequest): Promise<NextResponse | Response | null> {
  if (!workOSSessionRequest(request)) return null;
  const redirectUri = process.env.NEXT_PUBLIC_WORKOS_REDIRECT_URI?.trim();
  if (!redirectUri) return null;

  try {
    const middleware = authkitProxy({
      redirectUri,
      middlewareAuth: { enabled: false, unauthenticatedPaths: [] },
      signUpPaths: ["/sign-up"],
    }) as unknown as (request: NextRequest) => Promise<NextResponse | Response | undefined> | NextResponse | Response | undefined;
    return (await middleware(request)) ?? null;
  } catch {
    const destination = new URL("/sign-in", request.url);
    destination.searchParams.set("error", "session_refresh_failed");
    return NextResponse.redirect(destination);
  }
}

function clientIp(request: NextRequest): string {
  const forwarded = request.headers.get("x-forwarded-for");
  return forwarded?.split(",")[0]?.trim() || request.headers.get("x-real-ip") || "unknown";
}

export async function proxy(request: NextRequest): Promise<NextResponse | Response> {
  const host = request.headers.get("host")?.split(":")[0]?.toLowerCase();

  if (
    request.nextUrl.pathname.startsWith("/api/") &&
    !["GET", "HEAD", "OPTIONS"].includes(request.method)
  ) {
    const key = clientIp(request);
    if (apiWriteLimiter.check(key)) {
      const retryAfterSec = Math.max(1, Math.ceil(apiWriteLimiter.retryAfterMs(key) / 1000));
      return NextResponse.json(
        { error: "TOO_MANY_REQUESTS", message: "Too many requests" },
        { status: 429, headers: { "retry-after": String(retryAfterSec), "cache-control": "no-store" } },
      );
    }
  }

  const isPublicPath =
    request.nextUrl.pathname === "/sign-in" ||
    request.nextUrl.pathname === "/sign-up" ||
    request.nextUrl.pathname === "/auth/complete" ||
    request.nextUrl.pathname === "/auth/callback" ||
    request.nextUrl.pathname === "/auth/verify-email" ||
    request.nextUrl.pathname.startsWith("/api/auth") ||
    isPublicChangelogPath(request.nextUrl.pathname);
  if (host && isGatedHost(host) && !isPublicPath && !request.cookies.has(workOSSessionCookieName())) {
    return NextResponse.redirect(new URL("/sign-in", request.url));
  }

  const workOSResponse = await refreshWorkOSSession(request);
  if (workOSResponse?.headers.get("location")) return workOSResponse;

  if (host && isGatedHost(host) && request.nextUrl.pathname === "/") {
    const url = request.nextUrl.clone();
    url.pathname = "/home";

    const response = NextResponse.rewrite(url);
    workOSResponse?.headers.forEach((value, key) => response.headers.set(key, value));
    return response;
  }

  return workOSResponse ?? NextResponse.next();
}

export const config = {
	matcher: "/((?!_next/static|_next/image|favicon.ico|robots.txt|sitemap.xml|.*\\.[^/]+$).*)",
};
