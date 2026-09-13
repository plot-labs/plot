import {
  authErrorResponse,
  csrfErrorResponse,
  getPendingAuthState,
  isAllowedAuthRequestOrigin,
  workOSUserManagement,
} from "@/lib/workos-auth-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: Request): Promise<Response> {
  if (!isAllowedAuthRequestOrigin(request)) return csrfErrorResponse();
  const pendingState = await getPendingAuthState();
  let userId = pendingState?.userId;
  if (!userId && pendingState?.kind === "authentication") {
    const users = await workOSUserManagement().listUsers({
      email: pendingState.email,
      limit: 1,
    });
    const matchingUser = users.data?.find((user) =>
      user.email.toLowerCase() === pendingState.email.toLowerCase(),
    );
    userId = matchingUser?.id;
  }
  if (!userId) {
    return Response.json(
      { error: "VERIFICATION_RESEND_UNAVAILABLE", message: "Start sign-in again to request a new code." },
      { status: 400, headers: { "cache-control": "no-store" } },
    );
  }

  try {
    await workOSUserManagement().sendVerificationEmail({ userId });
    return Response.json(
      { status: "sent" },
      { status: 202, headers: { "cache-control": "no-store" } },
    );
  } catch (error) {
    return authErrorResponse(error, "The verification email could not be sent.");
  }
}
