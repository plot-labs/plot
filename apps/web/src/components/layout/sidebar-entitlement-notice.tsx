"use client";

import Link from "next/link";

import { useWorkspaceEntitlement } from "@/lib/use-workspace-entitlement";

export function SidebarEntitlementNotice({ collapsed }: { collapsed: boolean }) {
  const entitlement = useWorkspaceEntitlement();
  if (collapsed || !entitlement || entitlement.accessMode === "full") return null;

	const subscriptionRequired = entitlement.entitlementStatus === "subscription_required";
	const copy = subscriptionRequired
		? "A Founding subscription is required to use this workspace."
		: entitlement.accessMode === "complete_only"
			? "New AI work is paused. You can still edit, export, and publish existing drafts."
			: "This workspace is read-only. You can still export drafts and unpublish live changelog entries.";

  return (
    <div className="px-3 pb-3">
      <section
        aria-label="Workspace access"
        className="rounded-[12px] border border-black/[0.08] bg-white/70 px-3 py-2.5 text-[12px] leading-5 text-black/62 dark:border-white/10 dark:bg-white/[0.05] dark:text-white/65"
      >
        <p>{copy}</p>
        <Link
          href="/settings/general"
          className="mt-1.5 inline-block font-medium text-black/78 underline-offset-2 hover:underline dark:text-white/80"
        >
			{subscriptionRequired ? "Subscribe to use Plot" : "Plan and access"}
        </Link>
      </section>
    </div>
  );
}
