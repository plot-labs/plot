import { Suspense } from "react";

import { WorkspaceContentProfile } from "@/features/workspace-settings/workspace-content-profile";

export default function SettingsContentPage() {
  return (
    <Suspense fallback={<div className="h-full bg-shell-workspace" />}>
      <WorkspaceContentProfile />
    </Suspense>
  );
}
