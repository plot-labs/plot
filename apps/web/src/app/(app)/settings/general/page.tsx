import { Suspense } from "react";

import { WorkspaceGeneral } from "@/features/workspace-settings/workspace-general";

export default function SettingsGeneralPage() {
  return (
    <Suspense fallback={<div className="h-full bg-shell-workspace" />}>
      <WorkspaceGeneral />
    </Suspense>
  );
}
