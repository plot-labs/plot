const allowedRequestHeaders = new Set(["accept", "authorization", "content-type", "cookie", "idempotency-key"]);
const allowedResponseHeaders = new Set(["cache-control", "content-disposition", "content-type", "location"]);
const safeSegment = /^[A-Za-z0-9][A-Za-z0-9_-]*$/;

type RouteContext = { params: Promise<{ path: string[] }> };
type ProxyDependencies = { fetch?: typeof fetch; baseUrl?: string };

export const dynamic = "force-dynamic";

export async function GET(request: Request, context: RouteContext) {
  return handle(request, context);
}
export async function POST(request: Request, context: RouteContext) {
  return handle(request, context);
}
export async function PATCH(request: Request, context: RouteContext) {
  return handle(request, context);
}

async function handle(request: Request, context: RouteContext): Promise<Response> {
  const { path } = await context.params;
  return proxyPlotRequest(request, path);
}

export async function proxyPlotRequest(
  request: Request,
  path: string[],
  dependencies: ProxyDependencies = {},
): Promise<Response> {
  if (!isAllowed(request.method, path)) {
    return Response.json({ error: "PLOT_PROXY_ROUTE_REJECTED", message: "Plot API route is not allowed" }, {
      status: 404,
      headers: { "Cache-Control": "no-store" },
    });
  }

  let upstream: URL;
  try {
    const base = parseBaseUrl(dependencies.baseUrl ?? process.env.PLOT_API_BASE_URL ?? "http://127.0.0.1:8080");
    upstream = new URL(`/api/${path.map(encodeURIComponent).join("/")}`, base);
    upstream.search = new URL(request.url).search;
  } catch {
    return Response.json({ error: "PLOT_PROXY_NOT_CONFIGURED", message: "Plot API upstream is invalid" }, {
      status: 503,
      headers: { "Cache-Control": "no-store" },
    });
  }

  const headers = new Headers();
  request.headers.forEach((value, key) => {
    if (allowedRequestHeaders.has(key.toLowerCase())) headers.set(key, value);
  });
  const body = request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer();
  let upstreamResponse: Response;
  try {
    upstreamResponse = await (dependencies.fetch ?? fetch)(upstream, {
      method: request.method,
      headers,
      body,
      cache: "no-store",
      redirect: "manual",
      signal: request.signal,
    });
  } catch {
    return Response.json({ error: "PLOT_UPSTREAM_UNAVAILABLE", message: "Plot API is unavailable" }, {
      status: 502,
      headers: { "Cache-Control": "no-store" },
    });
  }
  const responseHeaders = new Headers({ "Cache-Control": "no-store" });
  upstreamResponse.headers.forEach((value, key) => {
    if (allowedResponseHeaders.has(key.toLowerCase())) responseHeaders.set(key, value);
  });
  const location = responseHeaders.get("location");
  if (location) {
    try {
      const target = new URL(location, upstream);
      if (target.origin === upstream.origin && target.pathname.startsWith("/api/")) {
        responseHeaders.set("location", `/api/plot/${target.pathname.slice(5)}${target.search}${target.hash}`);
      } else {
        responseHeaders.delete("location");
      }
    } catch {
      responseHeaders.delete("location");
    }
  }
  if (request.method === "GET" && path.at(-1) === "events") {
    responseHeaders.set("X-Accel-Buffering", "no");
  }
  return new Response(upstreamResponse.body, { status: upstreamResponse.status, headers: responseHeaders });
}

function isAllowed(method: string, path: string[]): boolean {
  if (path.length === 0 || path.some((segment) => !safeSegment.test(segment))) return false;
  const route = path.join("/");
  if (method === "GET" && route === "github/connections") return true;
  if (method === "GET" && route === "blocks") return true;
  if (method === "POST" && route === "generations") return true;
  if (method === "GET" && /^generations\/[^/]+$/.test(route)) return true;
  if (method === "GET" && /^generations\/[^/]+\/events$/.test(route)) return true;
  if (method === "GET" && /^content-packs\/[^/]+$/.test(route)) return true;
  if (method === "POST" && /^generations\/[^/]+\/interventions\/[^/]+\/resolution$/.test(route)) return true;
  if (method === "PATCH" && /^content-variants\/[^/]+\/sentences\/[^/]+$/.test(route)) return true;
  return method === "POST" && /^content-variants\/[^/]+\/exports$/.test(route);
}

function parseBaseUrl(value: string): URL {
  const url = new URL(value);
  if (!new Set(["http:", "https:"]).has(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error("Invalid upstream");
  }
  url.pathname = url.pathname.replace(/\/$/, "");
  return url;
}
