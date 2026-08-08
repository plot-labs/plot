import { Suspense } from "react";

import { WorkspaceGeneral } from "@/features/workspace-settings/workspace-general";

export default function SettingsGeneralPage() {
  return (
    <Suspense fallback={<div className="h-full bg-[#f4f6f8] dark:bg-[#101112]" />}>
      <WorkspaceGeneral />
    </Suspense>
  );
}
