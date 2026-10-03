import { Suspense } from "react";

import { IntegrationsWorkspace } from "@/features/integrations/integrations-workspace";

export default function SettingsIntegrationsPage() {
  return (
    <Suspense fallback={<div className="h-full bg-shell-workspace" />}>
      <IntegrationsWorkspace />
    </Suspense>
  );
}
