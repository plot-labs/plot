import { Suspense } from "react";

import { AccountSettings } from "@/features/account-settings/account-settings";

export default function SettingsAccountPage() {
  return (
    <Suspense fallback={<div className="h-full bg-shell-workspace" />}>
      <AccountSettings />
    </Suspense>
  );
}
