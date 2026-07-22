import { auth } from "@/lib/auth";
import { isCertificationLoopbackRequest } from "@/lib/certification-loopback";
import { isAllowedEmail, isUuid, parseAllowedEmails } from "@plot/auth/policy";

const allowedRequestHeaders = new Set(["accept", "content-type", "idempotency-key", "x-plot-workspace-id"]);
const allowedResponseHeaders = new Set(["cache-control", "content-disposition", "content-type", "location"]);
const safeSegment = /^[A-Za-z0-9][A-Za-z0-9_-]*$/;

type RouteContext = { params: Promise<{ path: string[] }> };
type ProxyDependencies = {
  fetch?: typeof fetch;
  baseUrl?: string;
  getSession?: (request: Request) => Promise<{ user?: { email?: string | null } } | null>;
  getServerJwt?: (request: Request) => Promise<string | null>;
};

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
export async function PUT(request: Request, context: RouteContext) {
  return handle(request, context);
}
export async function DELETE(request: Request, context: RouteContext) {
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

  const authResult = await authenticateRequest(request, dependencies);
  if (!authResult.ok) return authResult.response;
  if (isStateChanging(request.method) && !isSameOrigin(request)) {
    return Response.json({ error: "CSRF_ORIGIN_REJECTED", message: "Request origin is not allowed" }, {
      status: 403,
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
  const workspace = headers.get("x-plot-workspace-id");
  if (workspace && !isUuid(workspace)) {
    return Response.json({ error: "WORKSPACE_INVALID", message: "Workspace header is invalid" }, {
      status: 400,
      headers: { "Cache-Control": "no-store" },
    });
  }
  headers.delete("authorization");
  headers.delete("cookie");
  headers.set("Authorization", `Bearer ${authResult.jwt}`);
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
  if (request.method === "GET" && path.join("/") === "github/installations/callback") {
    return githubInstallationCallbackRedirect(request, upstreamResponse);
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

/**
 * GitHub reaches this browser route with a one-time state query parameter.
 * Consume it upstream, then discard both provider parameters instead of
 * reflecting them into client storage, logs, or the final URL.
 */
async function githubInstallationCallbackRedirect(request: Request, upstreamResponse: Response): Promise<Response> {
  const integrationsUrl = new URL("/integrations", request.url);
  if (upstreamResponse.ok) {
    const payload = await readJsonRecord(upstreamResponse);
    const connectionId = payload?.connectionId;
    if (typeof connectionId === "string" && isUuid(connectionId)) {
      integrationsUrl.searchParams.set("githubConnection", connectionId);
      return Response.redirect(integrationsUrl, 303);
    }
    integrationsUrl.searchParams.set("githubError", "failed");
    return Response.redirect(integrationsUrl, 303);
  }
  const payload = await readJsonRecord(upstreamResponse);
  integrationsUrl.searchParams.set("githubError", callbackErrorKind(upstreamResponse.status, payload?.error));
  return Response.redirect(integrationsUrl, 303);
}

async function readJsonRecord(response: Response): Promise<Record<string, unknown> | null> {
  try {
    const value: unknown = await response.json();
    return typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

function callbackErrorKind(status: number, code: unknown): "invalid" | "unauthorized" | "unavailable" | "failed" {
  if (status === 400 || code === "INVALID_GITHUB_STATE" || code === "GITHUB_CALLBACK_INVALID") return "invalid";
  if (status === 401 || status === 403 || code === "FORBIDDEN") return "unauthorized";
  if (status === 429 || status >= 500 || code === "GITHUB_NOT_CONFIGURED" || code === "GITHUB_PROVIDER_UNAVAILABLE") return "unavailable";
  return "failed";
}

function isAllowed(method: string, path: string[]): boolean {
  if (path.length === 0 || path.some((segment) => !safeSegment.test(segment))) return false;
  const route = path.join("/");
  if (method === "GET" && route === "me") return true;
  if (method === "POST" && route === "account/bootstrap") return true;
  if (method === "GET" && route === "workspaces") return true;
  if (method === "GET" && /^workspaces\/[0-9a-fA-F-]+$/.test(route)) return true;
  if (method === "PATCH" && /^workspaces\/[0-9a-fA-F-]+$/.test(route)) return true;
  if (method === "GET" && route === "github/connections") return true;
	if (method === "GET" && /^github\/connections\/[0-9a-fA-F-]+\/repositories$/.test(route)) return true;
  if (method === "POST" && route === "github/installations/requests") return true;
  if (method === "POST" && route === "github/installations/callback") return true;
  if (method === "GET" && route === "github/installations/callback") return true;
  if (method === "PUT" && /^github\/repositories\/[^/]+$/.test(route)) return true;
  if (method === "DELETE" && /^github\/repositories\/[^/]+$/.test(route)) return true;
  if (method === "POST" && /^github\/repositories\/[^/]+\/imports$/.test(route)) return true;
  if (method === "GET" && /^github\/imports\/[^/]+$/.test(route)) return true;
  if (method === "GET" && route === "blocks") return true;
  if (method === "POST" && route === "blocks") return true;
  if (method === "GET" && /^blocks\/[^/]+$/.test(route)) return true;
  if (method === "PATCH" && /^blocks\/[^/]+$/.test(route)) return true;
  if (method === "GET" && route === "sessions") return true;
  if (method === "POST" && route === "sessions") return true;
  if (method === "GET" && /^sessions\/[^/]+$/.test(route)) return true;
  if (method === "PATCH" && /^sessions\/[^/]+$/.test(route)) return true;
  if (method === "GET" && route === "tasks") return true;
  if (method === "POST" && route === "tasks") return true;
  if (method === "GET" && /^tasks\/[^/]+$/.test(route)) return true;
  if (method === "PATCH" && /^tasks\/[^/]+$/.test(route)) return true;
  if (method === "POST" && route === "generations") return true;
  if (method === "GET" && /^generations\/[^/]+$/.test(route)) return true;
  if (method === "GET" && /^generations\/[^/]+\/events$/.test(route)) return true;
  if (method === "GET" && route === "content-packs") return true;
  if (method === "GET" && /^content-packs\/[^/]+$/.test(route)) return true;
  if (method === "POST" && /^generations\/[^/]+\/interventions\/[^/]+\/resolution$/.test(route)) return true;
  if (method === "PATCH" && /^content-variants\/[^/]+\/sentences\/[^/]+$/.test(route)) return true;
  return method === "POST" && /^content-variants\/[^/]+\/exports$/.test(route);
}

type AuthResult = { ok: true; jwt: string } | { ok: false; response: Response };

async function authenticateRequest(request: Request, dependencies: ProxyDependencies): Promise<AuthResult> {
  if (isCertificationLoopbackRequest(request)) {
    return { ok: true, jwt: "certification-loopback" };
  }
  // Existing route unit tests inject an upstream fetcher. Production requests
  // always take the Better Auth session path.
  if (process.env.NODE_ENV === "test" && dependencies.fetch && !dependencies.getSession && !dependencies.getServerJwt) {
    return { ok: true, jwt: "test-injected" };
  }
  const sessionHeaders = new Headers(request.headers);
  sessionHeaders.delete("authorization");
  // Auth adapters only need URL and headers. Do not clone the body: doing so
  // would consume a one-shot request stream before it reaches Kotlin.
  const sessionRequest = new Request(request.url, {
    method: request.method,
    headers: sessionHeaders,
  });
  let session: { user?: { email?: string | null } } | null;
  try {
    session = dependencies.getSession
      ? await dependencies.getSession(sessionRequest)
      : await auth.api.getSession({ headers: sessionHeaders });
  } catch {
    return { ok: false, response: Response.json({ error: "UNAUTHORIZED", message: "Authentication is required" }, { status: 401, headers: { "Cache-Control": "no-store" } }) };
  }
  const email = session?.user?.email;
  const allowed = parseAllowedEmails(process.env.AUTH_ALLOWED_EMAILS);
  if (!session || !email || !isAllowedEmail(email, allowed)) {
    return { ok: false, response: Response.json({ error: "UNAUTHORIZED", message: "Authentication is required" }, { status: 401, headers: { "Cache-Control": "no-store" } }) };
  }
  let jwt: string | null;
  try {
    jwt = dependencies.getServerJwt
      ? await dependencies.getServerJwt(sessionRequest)
      : await getServerJwt(sessionRequest);
  } catch {
    jwt = null;
  }
  if (!jwt) {
    return { ok: false, response: Response.json({ error: "UNAUTHORIZED", message: "Authentication is required" }, { status: 401, headers: { "Cache-Control": "no-store" } }) };
  }
  return { ok: true, jwt };
}

async function getServerJwt(request: Request): Promise<string | null> {
  const sessionHeaders = new Headers(request.headers);
  sessionHeaders.delete("authorization");
  const result = await auth.api.getToken({ headers: sessionHeaders });
  return result?.token ?? null;
}

function isStateChanging(method: string): boolean {
  return ["POST", "PUT", "PATCH", "DELETE"].includes(method.toUpperCase());
}

function isSameOrigin(request: Request): boolean {
  const origin = request.headers.get("origin");
  if (origin) return origin === new URL(request.url).origin;
  const referer = request.headers.get("referer");
  if (!referer) return true;
  try { return new URL(referer).origin === new URL(request.url).origin; } catch { return false; }
}

function parseBaseUrl(value: string): URL {
  const url = new URL(value);
  if (!new Set(["http:", "https:"]).has(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error("Invalid upstream");
  }
  url.pathname = url.pathname.replace(/\/$/, "");
  return url;
}
