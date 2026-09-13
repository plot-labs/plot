export const runtime = "nodejs";
export const dynamic = "force-dynamic";

function unsupportedAuthRoute(): Response {
  return Response.json(
    { error: "AUTH_ROUTE_NOT_FOUND", message: "Auth route is not available" },
    { status: 404, headers: { "cache-control": "no-store" } },
  );
}

export function GET(_request?: Request): Response {
  void _request;
  return unsupportedAuthRoute();
}

export function POST(_request?: Request): Response {
  void _request;
  return unsupportedAuthRoute();
}

export function PUT(_request?: Request): Response {
  void _request;
  return unsupportedAuthRoute();
}

export function PATCH(_request?: Request): Response {
  void _request;
  return unsupportedAuthRoute();
}

export function DELETE(_request?: Request): Response {
  void _request;
  return unsupportedAuthRoute();
}
