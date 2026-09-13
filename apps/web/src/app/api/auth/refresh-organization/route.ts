import { refreshSession } from "@workos-inc/authkit-nextjs";

import {
  authErrorResponse,
  csrfErrorResponse,
  isAllowedAuthRequestOrigin,
  parseAuthBody,
  parseAuthBodyValue,
} from "@/lib/workos-auth-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const WORKOS_ORGANIZATION_ID = /^[A-Za-z0-9_-]{1,255}$/;

export async function POST(request: Request): Promise<Response> {
  if (!isAllowedAuthRequestOrigin(request)) return csrfErrorResponse();
  const body = await parseAuthBody(request);
  const organizationId = parseAuthBodyValue(body?.organizationId, 255);
  if (!organizationId || !WORKOS_ORGANIZATION_ID.test(organizationId)) {
    return Response.json(
      { error: "INVALID_REQUEST", message: "A valid organization is required" },
      { status: 400, headers: { "cache-control": "no-store" } },
    );
  }

  try {
    const session = await refreshSession({ organizationId });
    if (!session.user) {
      return Response.json(
        { error: "UNAUTHORIZED", message: "Authentication is required" },
        { status: 401, headers: { "cache-control": "no-store" } },
      );
    }
    return Response.json(
      { organizationId: session.organizationId ?? organizationId },
      { status: 200, headers: { "cache-control": "no-store" } },
    );
  } catch (error) {
    return authErrorResponse(error, "The authentication session could not be refreshed.");
  }
}
