import { Suspense } from "react";

import { GitHubIntegrationWorkspace } from "@/features/integrations/github-integration-workspace";

export default function SettingsGitHubIntegrationPage() {
  return (
    <Suspense fallback={<div className="h-full bg-shell-workspace" />}>
      <GitHubIntegrationWorkspace />
    </Suspense>
  );
}
