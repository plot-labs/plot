import { redirect } from "next/navigation";

import { buildRedirectPath, type LegacyRouteProps } from "@/lib/route-redirect";

export default async function UpdatesPage({ searchParams }: LegacyRouteProps) {
  redirect(buildRedirectPath("/contents", await searchParams));
}
