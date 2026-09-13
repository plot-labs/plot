import "@astryxdesign/core/astryx.css";

import type { ReactNode } from "react";
import { redirect } from "next/navigation";

import { ProductShell } from "@/components/layout/product-shell";
import { fetchPlotAuthSession } from "@/lib/plot-auth";

export default async function AppLayout({ children }: { children: ReactNode }) {
  const session = await fetchPlotAuthSession();
  if (!session?.user?.email) redirect("/sign-in");
  return <ProductShell>{children}</ProductShell>;
}
