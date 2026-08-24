import { NextResponse, type NextRequest } from "next/server";
import { getSessionCookie } from "better-auth/cookies";

import { createFixedWindowLimiter } from "@/lib/rate-limit";

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
// instance, with platform-level protection as the outer layer.
const apiWriteLimiter = createFixedWindowLimiter(60 * 1000, 60);

function clientIp(request: NextRequest): string {
  const forwarded = request.headers.get("x-forwarded-for");
  return forwarded?.split(",")[0]?.trim() || request.headers.get("x-real-ip") || "unknown";
}

export function proxy(request: NextRequest) {
  const host = request.headers.get("host")?.split(":")[0]?.toLowerCase();

  if (
    request.nextUrl.pathname.startsWith("/api/") &&
    !["GET", "HEAD", "OPTIONS"].includes(request.method) &&
    apiWriteLimiter.check(clientIp(request))
  ) {
    return NextResponse.json(
      { error: "TOO_MANY_REQUESTS", message: "Too many requests" },
      { status: 429 },
    );
  }

  const isPublicAuthPath = request.nextUrl.pathname === "/sign-in" || request.nextUrl.pathname === "/auth/complete" || request.nextUrl.pathname.startsWith("/api/auth");
  if (host && isGatedHost(host) && !isPublicAuthPath && !getSessionCookie(request)) {
    return NextResponse.redirect(new URL("/sign-in", request.url));
  }

  if (host && isGatedHost(host) && request.nextUrl.pathname === "/") {
    const url = request.nextUrl.clone();
    url.pathname = "/chat";

    return NextResponse.rewrite(url);
  }

  return NextResponse.next();
}

export const config = {
	matcher: "/((?!_next/static|_next/image|favicon.ico|robots.txt|sitemap.xml|.*\\.[^/]+$).*)",
};
