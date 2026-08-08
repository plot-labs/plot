import { Suspense } from "react";

import { IntegrationsWorkspace } from "@/features/integrations/integrations-workspace";

export default function SettingsIntegrationsPage() {
  return (
    <Suspense fallback={<div className="h-full bg-[#f7f8fa] dark:bg-[#111113]" />}>
      <IntegrationsWorkspace />
    </Suspense>
  );
}
