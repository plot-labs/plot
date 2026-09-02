export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const hopByHopHeaders = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailers",
  "transfer-encoding",
  "upgrade",
]);

function authUpstreamBase(): string {
  const configured = process.env.PLOT_API_BASE_URL?.trim();
  if (configured) return configured.replace(/\/$/, "");
  return process.env.NODE_ENV === "production"
    ? "https://api.useplot.xyz"
    : "http://127.0.0.1:8080";
}

async function proxyAuthRequest(request: Request): Promise<Response> {
  const requestUrl = new URL(request.url);
  const upstreamPath = requestUrl.pathname.replace(/^\/api\/auth/, "/api/auth");
  const upstream = new URL(`${upstreamPath}${requestUrl.search}`, authUpstreamBase());

  const headers = new Headers();
  request.headers.forEach((value, key) => {
    if (!hopByHopHeaders.has(key.toLowerCase())) headers.set(key, value);
  });
  headers.delete("host");

  const body = request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer();
  let upstreamResponse: Response;
  try {
    upstreamResponse = await fetch(upstream, {
      method: request.method,
      headers,
      body,
      redirect: "manual",
      cache: "no-store",
    });
  } catch {
    return Response.json(
      { error: "AUTH_UPSTREAM_UNAVAILABLE", message: "Auth service is unavailable" },
      { status: 502, headers: { "Cache-Control": "no-store" } },
    );
  }

  const responseHeaders = new Headers({ "Cache-Control": "no-store" });
  upstreamResponse.headers.forEach((value, key) => {
    if (!hopByHopHeaders.has(key.toLowerCase())) responseHeaders.set(key, value);
  });

  return new Response(upstreamResponse.body, {
    status: upstreamResponse.status,
    headers: responseHeaders,
  });
}

export function GET(request: Request) {
  return proxyAuthRequest(request);
}

export function POST(request: Request) {
  return proxyAuthRequest(request);
}

export function PUT(request: Request) {
  return proxyAuthRequest(request);
}

export function PATCH(request: Request) {
  return proxyAuthRequest(request);
}

export function DELETE(request: Request) {
  return proxyAuthRequest(request);
}
