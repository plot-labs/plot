import { redirect } from "next/navigation";

import { buildRedirectPath, type LegacyRouteProps } from "@/lib/route-redirect";

export default async function AutonomyPage({ searchParams }: LegacyRouteProps) {
  redirect(buildRedirectPath("/automation", await searchParams));
}
