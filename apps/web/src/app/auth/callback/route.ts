import { NextRequest, NextResponse } from "next/server";

import { safeWorkOSReturnPath } from "@/lib/workos-auth";
import {
  consumeWorkOSSocialAuthState,
  saveWorkOSSession,
  setPendingAuthState,
  workOSAuthFailure,
  workOSClientId,
  workOSUserManagement,
} from "@/lib/workos-auth-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

function errorRedirect(
  request: NextRequest,
  entryPath: "/sign-in" | "/sign-up",
  error: "provider_cancelled" | "callback_failed",
): Response {
  const destination = new URL(entryPath, request.url);
  destination.searchParams.set("error", error);
  return NextResponse.redirect(destination);
}

export async function GET(request: NextRequest): Promise<Response> {
  const requestUrl = new URL(request.url);
  const pendingState = await consumeWorkOSSocialAuthState();
  const entryPath = pendingState?.entryPath ?? "/sign-in";

  if (requestUrl.searchParams.has("error")) {
    return errorRedirect(request, entryPath, "provider_cancelled");
  }

  const code = requestUrl.searchParams.get("code");
  const state = requestUrl.searchParams.get("state");
  if (!pendingState || !code || !state || state !== pendingState.nonce) {
    console.error("WorkOS GitHub callback state validation failed", {
      hasCode: Boolean(code),
      hasState: Boolean(state),
      hasPendingState: Boolean(pendingState),
      stateMatches: Boolean(pendingState && state === pendingState.nonce),
    });
    return errorRedirect(request, entryPath, "callback_failed");
  }

  try {
    const authenticationResponse = await workOSUserManagement().authenticateWithCode({
      clientId: workOSClientId(),
      code,
    });
    await saveWorkOSSession(authenticationResponse, request);
    return NextResponse.redirect(new URL(safeWorkOSReturnPath(pendingState.returnTo), request.url));
  } catch (error) {
    const failure = workOSAuthFailure(error);
    if (failure.code === "email_verification_required" && failure.pendingAuthenticationToken && failure.email) {
      await setPendingAuthState({
        kind: "authentication",
        email: failure.email,
        pendingAuthenticationToken: failure.pendingAuthenticationToken,
      }, request);
      const destination = new URL("/auth/verify-email", request.url);
      destination.searchParams.set("email", failure.email);
      return NextResponse.redirect(destination);
    }
    console.error("WorkOS GitHub callback exchange failed", failure);
    return errorRedirect(request, entryPath, "callback_failed");
  }
}
