import type { ReactNode } from "react";

import { ProductShell } from "@/components/layout/product-shell";

export default function AppLayout({ children }: { children: ReactNode }) {
  return <ProductShell>{children}</ProductShell>;
}
