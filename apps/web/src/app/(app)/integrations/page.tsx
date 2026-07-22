import { Suspense } from "react";

import { IntegrationsWorkspace } from "@/features/integrations/integrations-workspace";

export default function IntegrationsPage() {
  return (
    <Suspense fallback={<div className="h-screen bg-[#f8fafc] dark:bg-[#111113]" />}>
      <IntegrationsWorkspace />
    </Suspense>
  );
}
