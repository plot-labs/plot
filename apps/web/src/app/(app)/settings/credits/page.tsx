import { Suspense } from "react";

import { WorkspaceCredits } from "@/features/workspace-settings/workspace-credits";

export default function SettingsCreditsPage() {
  return (
    <Suspense fallback={<div className="h-full bg-[#f4f6f8] dark:bg-[#101112]" />}>
      <WorkspaceCredits />
    </Suspense>
  );
}
