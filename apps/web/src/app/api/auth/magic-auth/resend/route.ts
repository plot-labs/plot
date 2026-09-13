import {
  authErrorResponse,
  createWorkOSMagicAuth,
  csrfErrorResponse,
  getPendingMagicAuthState,
  isAllowedAuthRequestOrigin,
} from "@/lib/workos-auth-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: Request): Promise<Response> {
  if (!isAllowedAuthRequestOrigin(request)) return csrfErrorResponse();

  const pendingState = await getPendingMagicAuthState();
  if (!pendingState) {
    return Response.json(
      { error: "MAGIC_AUTH_SESSION_EXPIRED", message: "Start again to request a new sign-in code." },
      { status: 400, headers: { "cache-control": "no-store" } },
    );
  }

  try {
    await createWorkOSMagicAuth(request, pendingState);
    return Response.json(
      { status: "sent" },
      { status: 202, headers: { "cache-control": "no-store" } },
    );
  } catch (error) {
    return authErrorResponse(error, "The sign-in email could not be sent. Please try again.");
  }
}
