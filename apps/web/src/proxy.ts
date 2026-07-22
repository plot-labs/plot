import { NextResponse, type NextRequest } from "next/server";
import { getSessionCookie } from "better-auth/cookies";
import { isCertificationLoopbackAuthority } from "@/lib/certification-loopback";

const appHosts = new Set(["app.useplot.xyz"]);

export function proxy(request: NextRequest) {
	if (process.env.PLOT_CERTIFICATION_LOOPBACK_GUARD === "true" && !hasOnlyLoopbackAuthorities(request)) {
		return new NextResponse(null, { status: 421 });
	}
  const host = request.headers.get("host")?.split(":")[0]?.toLowerCase();

  const isPublicAuthPath = request.nextUrl.pathname === "/sign-in" || request.nextUrl.pathname === "/auth/complete" || request.nextUrl.pathname.startsWith("/api/auth");
  if (host && appHosts.has(host) && !isPublicAuthPath && !getSessionCookie(request)) {
    return NextResponse.redirect(new URL("/sign-in", request.url));
  }

  if (host && appHosts.has(host) && request.nextUrl.pathname === "/") {
    const url = request.nextUrl.clone();
    url.pathname = "/sessions";

    return NextResponse.rewrite(url);
  }

  return NextResponse.next();
}

export const config = {
	matcher: "/((?!_next/static|_next/image|favicon.ico|robots.txt|sitemap.xml|.*\\.[^/]+$).*)",
};

export function hasOnlyLoopbackAuthorities(request: Pick<NextRequest, "headers">): boolean {
	const host = request.headers.get("host");
	const forwardedHost = request.headers.get("x-forwarded-host");
	return host !== null && isCertificationLoopbackAuthority(host) &&
		(forwardedHost === null || forwardedHost.split(",").every((value) => isCertificationLoopbackAuthority(value.trim())));
}
