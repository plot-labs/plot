import { NextResponse } from "next/server";
import { signOut } from "@workos-inc/authkit-nextjs";

import { csrfErrorResponse, isAllowedAuthRequestOrigin } from "@/lib/workos-auth-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: Request): Promise<Response> {
  if (!isAllowedAuthRequestOrigin(request)) return csrfErrorResponse();
  try {
    await signOut({ returnTo: "/sign-in" });
  } catch {
    // The local AuthKit cookie is cleared in the SDK's finally block. If the
    // provider logout redirect cannot be created, still leave the browser at
    // the signed-out Plot surface.
  }
  return NextResponse.redirect(new URL("/sign-in", request.url));
}

export const GET = POST;
