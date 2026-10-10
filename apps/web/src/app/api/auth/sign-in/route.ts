import { createAuthEntryHandlers } from "@/lib/workos-auth-entry-route";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const handlers = createAuthEntryHandlers("/sign-in");

export const GET = handlers.GET;
export const POST = handlers.POST;
