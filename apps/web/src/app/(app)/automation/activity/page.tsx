import { redirect } from "next/navigation";

import { buildRedirectPath, type LegacyRouteProps } from "@/lib/route-redirect";

export default async function AutomationActivityPage({ searchParams }: LegacyRouteProps) {
  const params = await searchParams;
  // Old activity links that point at a specific chat still open it; bare links land on Home, where activity now lives.
  redirect(params.chat ? buildRedirectPath("/chat", params) : "/home");
}
