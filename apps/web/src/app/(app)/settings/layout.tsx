import type { ReactNode } from "react";

import { WorkspaceSettingsShell } from "@/features/settings/workspace-settings-shell";

export default function SettingsLayout({ children }: { children: ReactNode }) {
  return <WorkspaceSettingsShell>{children}</WorkspaceSettingsShell>;
}
