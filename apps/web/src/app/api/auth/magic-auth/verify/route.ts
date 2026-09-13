import {
  authErrorResponse,
  clearPendingMagicAuthState,
  csrfErrorResponse,
  getPendingMagicAuthState,
  isAllowedAuthRequestOrigin,
  parseAuthBody,
  parseAuthBodyValue,
  requestAuthContext,
  saveWorkOSSession,
  workOSClientId,
  workOSUserManagement,
} from "@/lib/workos-auth-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: Request): Promise<Response> {
  if (!isAllowedAuthRequestOrigin(request)) return csrfErrorResponse();

  const body = await parseAuthBody(request);
  const code = parseAuthBodyValue(body?.code, 6);
  if (!code || !/^\d{6}$/.test(code)) {
    return Response.json(
      { error: "INVALID_REQUEST", message: "Enter the six-digit code from your email." },
      { status: 400, headers: { "cache-control": "no-store" } },
    );
  }

  const pendingState = await getPendingMagicAuthState();
  if (!pendingState) {
    return Response.json(
      { error: "MAGIC_AUTH_SESSION_EXPIRED", message: "Start again to request a new sign-in code." },
      { status: 400, headers: { "cache-control": "no-store" } },
    );
  }

  try {
    const authenticationResponse = await workOSUserManagement().authenticateWithMagicAuth({
      clientId: workOSClientId(),
      code,
      email: pendingState.email,
      ...requestAuthContext(request),
    });
    await saveWorkOSSession(authenticationResponse, request);
    await clearPendingMagicAuthState();
    return Response.json(
      { status: "authenticated" },
      { status: 200, headers: { "cache-control": "no-store" } },
    );
  } catch (error) {
    return authErrorResponse(error, "That sign-in code is invalid or expired.");
  }
}
