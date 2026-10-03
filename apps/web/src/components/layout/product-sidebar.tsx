"use client";

import { ArrowLeft01Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import Image from "next/image";
import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { PanelLeftClose, PanelLeftOpen, Settings } from "lucide-react";
import { useState } from "react";

import { ProfileMenu } from "@/components/layout/profile-menu";
import { SidebarNavigation } from "@/components/layout/sidebar-navigation";
import { SidebarEntitlementNotice } from "@/components/layout/sidebar-entitlement-notice";
import { SidebarOnboarding } from "@/components/layout/sidebar-onboarding";
import { useRecentChats } from "@/components/layout/use-recent-chats";
import { useSidebarWorkspace } from "@/components/layout/use-sidebar-workspace";
import { WorkspaceSwitcher } from "@/components/layout/workspace-switcher";
import type { ProductTheme } from "@/components/layout/product-shell";
import { cn } from "@/lib/utils";
import { isSettingsPath } from "./product-navigation";

type ProductSidebarProps = {
  collapsed?: boolean;
  theme: ProductTheme;
  onThemeChange: (theme: ProductTheme) => void;
  onToggleSidebar: () => void;
};

const appHomeHref = "/home";

export function ProductSidebar({ collapsed = false, theme, onThemeChange, onToggleSidebar }: ProductSidebarProps) {
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const selectedChatId = pathname === "/chat" ? searchParams.get("chat") : null;
  const settingsMode = isSettingsPath(pathname);
  const workspace = useSidebarWorkspace();
  const recentChats = useRecentChats({ settingsMode, selectedWorkspaceId: workspace.currentWorkspaceId });
  const [profileMenuOpen, setProfileMenuOpen] = useState(false);

  return (
    <>
      <aside className={cn(
        "hidden h-dvh shrink-0 flex-col bg-[#f6f7f9] text-[#2f3237] transition-[width] duration-200 dark:bg-[#202126] dark:text-[#f4f4f5] lg:flex",
        collapsed ? "w-[72px]" : "w-[252px]",
      )}>
        <div className={cn(
          "flex items-center gap-2",
          collapsed ? "justify-center px-2 pb-3 pt-4" : "px-4 pb-4 pt-5",
        )}>
          <Link
            href={appHomeHref}
            aria-label="Plot home"
            className={cn("flex min-w-0 items-center gap-2", collapsed ? "hidden" : "flex-1")}
          >
            <Image src="/plot-icon.svg" alt="" width={24} height={24} className="size-6 shrink-0 dark:invert" />
            <div className="font-display text-[22px] leading-none tracking-normal text-black/85 dark:text-white/90">Plot</div>
          </Link>
          <button
            type="button"
            onClick={onToggleSidebar}
            aria-label={collapsed ? "Open sidebar" : "Close sidebar"}
            className={cn("glass-button glass-icon", "inline-flex shrink-0 items-center justify-center", collapsed ? "size-9" : "size-8")}
          >
            {collapsed ? <PanelLeftOpen className="size-4" /> : <PanelLeftClose className="size-4" />}
          </button>
        </div>

        <WorkspaceSwitcher
          collapsed={collapsed}
          workspaceLoading={workspace.workspaceLoading}
          currentWorkspace={workspace.currentWorkspace}
          currentWorkspaceId={workspace.currentWorkspaceId}
          currentWorkspaceName={workspace.currentWorkspaceName}
          currentWorkspaceMark={workspace.currentWorkspaceMark}
          workspaceItems={workspace.workspaceItems}
          workspaceMenuOpen={workspace.workspaceMenuOpen}
          setWorkspaceMenuOpen={workspace.setWorkspaceMenuOpen}
          onOpen={() => setProfileMenuOpen(false)}
          selectWorkspace={workspace.selectWorkspace}
          creatingWorkspace={workspace.creatingWorkspace}
          setCreatingWorkspace={workspace.setCreatingWorkspace}
          workspaceName={workspace.workspaceName}
          setWorkspaceName={workspace.setWorkspaceName}
          workspaceCreateError={workspace.workspaceCreateError}
          setWorkspaceCreateError={workspace.setWorkspaceCreateError}
          workspaceSwitchError={workspace.workspaceSwitchError}
          switchingWorkspaceId={workspace.switchingWorkspaceId}
          isCreatingWorkspace={workspace.isCreatingWorkspace}
          handleCreateWorkspace={workspace.handleCreateWorkspace}
        />

        {settingsMode && (
          <div className={cn("pb-4", collapsed ? "px-2" : "px-3")}>
            <Link
              href={appHomeHref}
              className={cn("glass-button", "flex items-center gap-2", collapsed ? "justify-center" : "")}
            >
              <HugeiconsIcon icon={ArrowLeft01Icon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" className="shrink-0" />
              <span className={collapsed ? "sr-only" : undefined}>Back to app</span>
            </Link>
          </div>
        )}

        <SidebarNavigation
          collapsed={collapsed}
          settingsMode={settingsMode}
          pathname={pathname}
          selectedChatId={selectedChatId}
          recentChats={recentChats}
        />

        {!settingsMode && !collapsed && <SidebarOnboarding workspaceId={workspace.currentWorkspaceId} />}
        {!settingsMode ? <SidebarEntitlementNotice collapsed={collapsed} /> : null}

        <nav aria-label="Workspace utilities" className={cn("pb-2", collapsed ? "px-2" : "px-3")}>
          <Link href="/settings/general" title="Settings" aria-current={settingsMode ? "location" : undefined}
            className={cn("glass-control", "flex h-8 items-center gap-2 rounded-lg text-[13px] font-medium", collapsed ? "justify-center" : "px-2.5")}>
            <Settings className="size-4 shrink-0" aria-hidden="true" />
            <span className={collapsed ? "sr-only" : undefined}>Settings</span>
          </Link>
        </nav>

        <ProfileMenu
          collapsed={collapsed}
          theme={theme}
          onThemeChange={onThemeChange}
          account={workspace.account}
          currentWorkspaceName={workspace.currentWorkspaceName}
          profileMenuOpen={profileMenuOpen}
          setProfileMenuOpen={setProfileMenuOpen}
          onOpen={() => workspace.setWorkspaceMenuOpen(false)}
        />
      </aside>
    </>
  );
}
