import { Suspense } from "react";

import { AccountSettings } from "@/features/account-settings/account-settings";

export default function SettingsAccountPage() {
  return (
    <Suspense fallback={<div className="h-full bg-[#f4f6f8] dark:bg-[#101112]" />}>
      <AccountSettings />
    </Suspense>
  );
}
