import { NextResponse, type NextRequest } from "next/server";

const appHosts = new Set(["app.useplot.xyz"]);

export function proxy(request: NextRequest) {
	if (process.env.PLOT_CERTIFICATION_LOOPBACK_GUARD === "true" && !hasOnlyLoopbackAuthorities(request)) {
		return new NextResponse(null, { status: 421 });
	}
  const host = request.headers.get("host")?.split(":")[0]?.toLowerCase();

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
	return host !== null && isLoopbackAuthority(host) &&
		(forwardedHost === null || forwardedHost.split(",").every((value) => isLoopbackAuthority(value.trim())));
}

function isLoopbackAuthority(value: string): boolean {
	const normalized = value.toLowerCase();
	if (normalized === "::1") return true;
	const match = normalized.match(/^\[::1\](?::([0-9]{1,5}))?$/) ??
		normalized.match(/^(?:127\.0\.0\.1|localhost)(?::([0-9]{1,5}))?$/);
	if (!match) return false;
	const port = match[1];
	return port === undefined || (Number.isInteger(Number(port)) && Number(port) >= 1 && Number(port) <= 65_535);
}
