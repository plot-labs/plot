import { NextResponse } from "next/server";

import { safeWorkOSReturnPath } from "@/lib/workos-auth";
import {
  authErrorResponse,
  createWorkOSMagicAuth,
  csrfErrorResponse,
  isAllowedAuthRequestOrigin,
  normalizeEmail,
  parseAuthBody,
  workOSGitHubAuthorizationUrl,
} from "@/lib/workos-auth-server";

type AuthEntryPath = "/sign-in" | "/sign-up";

const EMAIL_FAILURE_MESSAGES: Record<AuthEntryPath, string> = {
  "/sign-in": "The sign-in email could not be sent. Please try again.",
  "/sign-up": "The sign-up email could not be sent. Please try again.",
};

type AuthEntryHandlers = {
  GET: (request: Request) => Promise<Response>;
  POST: (request: Request) => Promise<Response>;
};

/**
 * Builds the route handlers shared by the sign-in and sign-up entry points.
 * GET starts GitHub OAuth and POST sends a magic-auth email. Both flows return
 * the visitor to the page they started from, which is the only difference
 * between the two routes.
 */
export function createAuthEntryHandlers(entryPath: AuthEntryPath): AuthEntryHandlers {
  async function GET(request: Request): Promise<Response> {
    const requestUrl = new URL(request.url);
    const returnTo = safeWorkOSReturnPath(
      requestUrl.searchParams.get("returnTo") ?? requestUrl.searchParams.get("callbackURL"),
    );

    try {
      return NextResponse.redirect(await workOSGitHubAuthorizationUrl(request, {
        entryPath,
        returnTo,
      }));
    } catch {
      const destination = new URL(entryPath, request.url);
      destination.searchParams.set("error", "provider_unavailable");
      return NextResponse.redirect(destination);
    }
  }

  async function POST(request: Request): Promise<Response> {
    if (!isAllowedAuthRequestOrigin(request)) return csrfErrorResponse();
    const body = await parseAuthBody(request);
    const email = normalizeEmail(body?.email);
    if (!email) {
      return Response.json(
        { error: "INVALID_REQUEST", message: "Enter a valid email address" },
        { status: 400, headers: { "cache-control": "no-store" } },
      );
    }

    try {
      await createWorkOSMagicAuth(request, { email, entryPath });
      return Response.json(
        { email, status: "verification_required" },
        { status: 202, headers: { "cache-control": "no-store" } },
      );
    } catch (error) {
      return authErrorResponse(error, EMAIL_FAILURE_MESSAGES[entryPath]);
    }
  }

  return { GET, POST };
}
