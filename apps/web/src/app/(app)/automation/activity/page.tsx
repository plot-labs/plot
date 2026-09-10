import { redirect } from "next/navigation";

import { buildRedirectPath, type LegacyRouteProps } from "@/lib/route-redirect";

export default async function AutomationActivityPage({ searchParams }: LegacyRouteProps) {
  redirect(buildRedirectPath("/chat", await searchParams));
}
