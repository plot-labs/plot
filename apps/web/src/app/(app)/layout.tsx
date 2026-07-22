import type { ReactNode } from "react";

import { ProductShell } from "@/components/layout/product-shell";
import { isCertificationLoopbackRequest } from "@/lib/certification-loopback";
import { auth } from "@/lib/auth";
import { isAllowedEmail, parseAllowedEmails } from "@plot/auth/policy";
import { headers } from "next/headers";
import { redirect } from "next/navigation";

export default async function AppLayout({ children }: { children: ReactNode }) {
  const requestHeaders = await headers();
  if (!isCertificationLoopbackRequest({ headers: requestHeaders })) {
    const session = await auth.api.getSession({ headers: requestHeaders }).catch(() => null);
    if (!session) redirect("/sign-in");
    const allowed = parseAllowedEmails(process.env.AUTH_ALLOWED_EMAILS);
    if (process.env.NODE_ENV === "production" && (!session.user.email || !isAllowedEmail(session.user.email, allowed))) {
      redirect("/sign-in");
    }
  }
  return <ProductShell>{children}</ProductShell>;
}
