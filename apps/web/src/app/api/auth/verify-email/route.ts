import {
  authErrorResponse,
  csrfErrorResponse,
  isAllowedAuthRequestOrigin,
  parseAuthBody,
  parseAuthBodyValue,
  requestAuthContext,
  clearPendingAuthState,
  getPendingAuthState,
  saveWorkOSSession,
  workOSClientId,
  workOSUserManagement,
} from "@/lib/workos-auth-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: Request): Promise<Response> {
  if (!isAllowedAuthRequestOrigin(request)) return csrfErrorResponse();
  const body = await parseAuthBody(request);
  const code = parseAuthBodyValue(body?.code, 64);
  if (!code) {
    return Response.json(
      { error: "INVALID_REQUEST", message: "Verification code is required" },
      { status: 400, headers: { "cache-control": "no-store" } },
    );
  }

  const pendingState = await getPendingAuthState();
  if (!pendingState) {
    return Response.json(
      { error: "VERIFICATION_SESSION_EXPIRED", message: "Start sign-in again to request a new code." },
      { status: 400, headers: { "cache-control": "no-store" } },
    );
  }

  try {
    const authenticationResponse = await workOSUserManagement().authenticateWithEmailVerification({
      clientId: workOSClientId(),
      code,
      pendingAuthenticationToken: pendingState.pendingAuthenticationToken,
      ...requestAuthContext(request),
    });
    await saveWorkOSSession(authenticationResponse, request);
    await clearPendingAuthState();
    return Response.json(
      { status: "authenticated" },
      { status: 200, headers: { "cache-control": "no-store" } },
    );
  } catch (error) {
    return authErrorResponse(error, "Verification code is invalid or expired.");
  }
}
