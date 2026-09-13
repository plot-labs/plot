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

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: Request): Promise<Response> {
  const requestUrl = new URL(request.url);
  const returnTo = safeWorkOSReturnPath(
    requestUrl.searchParams.get("returnTo") ?? requestUrl.searchParams.get("callbackURL"),
  );

  try {
    return NextResponse.redirect(await workOSGitHubAuthorizationUrl(request, {
      entryPath: "/sign-in",
      returnTo,
    }));
  } catch {
    const destination = new URL("/sign-in", request.url);
    destination.searchParams.set("error", "provider_unavailable");
    return NextResponse.redirect(destination);
  }
}

export async function POST(request: Request): Promise<Response> {
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
    await createWorkOSMagicAuth(request, { email, entryPath: "/sign-in" });
    return Response.json(
      { email, status: "verification_required" },
      { status: 202, headers: { "cache-control": "no-store" } },
    );
  } catch (error) {
    return authErrorResponse(error, "The sign-in email could not be sent. Please try again.");
  }
}
