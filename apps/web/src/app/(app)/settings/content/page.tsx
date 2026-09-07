import { Suspense } from "react";

import { WorkspaceContentProfile } from "@/features/workspace-settings/workspace-content-profile";

export default function SettingsContentPage() {
  return (
    <Suspense fallback={<div className="h-full bg-[#f4f6f8] dark:bg-[#101112]" />}>
      <WorkspaceContentProfile />
    </Suspense>
  );
}
