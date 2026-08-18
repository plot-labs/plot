"use client";

import {
  MessageMultiple01Icon,
  PlugSocketIcon,
  Shapes01Icon,
  Settings02Icon,
  ZapIcon,
} from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import type { WorkSessionSummary as ChatSummary } from "@plot/api-client";
import Link from "next/link";
import type { ReactNode } from "react";

import { UserRound } from "lucide-react";
import { cn } from "@/lib/utils";

const productNavItems = [
  { href: "/chat", label: "Chat", icon: ChatIcon },
  { href: "/routines", label: "Routines", icon: RoutinesIcon },
  { href: "/artifacts", label: "Artifacts", icon: ArtifactsIcon },
];

const workspaceSettingsNavGroups = [
  {
    label: "Account",
    items: [{ href: "/settings/account", label: "Account", icon: AccountIcon }],
  },
  {
    label: "Workspace",
    items: [
      { href: "/settings/general", label: "General", icon: SettingsIcon },
      { href: "/settings/integrations", label: "Integrations", icon: IntegrationsIcon },
    ],
  },
];

type SidebarNavigationProps = {
  collapsed: boolean;
  settingsMode: boolean;
  pathname: string;
  selectedChatId: string | null;
  recentChats: ChatSummary[];
};

export function SidebarNavigation({ collapsed, settingsMode, pathname, selectedChatId, recentChats }: SidebarNavigationProps) {
  return (
    <>
      <nav
        className={cn("space-y-1 pb-4", collapsed ? "px-2" : "px-3")}
        aria-label={settingsMode ? "Settings navigation" : "Product sidebar navigation"}
      >
        {settingsMode ? workspaceSettingsNavGroups.map((group) => (
          <div key={group.label}>
            <div className={cn(
              "px-2 pb-1 pt-3 text-[11px] font-medium uppercase tracking-[0.08em] text-black/35 dark:text-white/35",
              collapsed && "sr-only",
            )}>
              {group.label}
            </div>
            <div className="space-y-1">
              {group.items.map((item) => <SidebarNavLink key={item.href} collapsed={collapsed} item={item} pathname={pathname} />)}
            </div>
          </div>
        )) : productNavItems.map((item) => (
          <SidebarNavLink
            key={item.href}
            collapsed={collapsed}
            item={item}
            pathname={pathname}
            active={item.href === "/chat" ? pathname === "/chat" && !selectedChatId : undefined}
          />
        ))}
      </nav>

      {!settingsMode && recentChats.length ? (
        <div className={cn("min-h-0 flex-1 overflow-y-auto", collapsed ? "px-2" : "px-3")}>
          <div className={cn(
            "px-2 pb-1 pt-2 text-[11px] font-medium uppercase tracking-[0.08em] text-black/35 dark:text-white/35",
            collapsed && "sr-only",
          )}>
            History
          </div>
          <div className="space-y-1">
            {recentChats.map((chat) => (
              <Link
                key={chat.id}
                href={`/chat?chat=${encodeURIComponent(chat.id)}`}
                aria-current={selectedChatId === chat.id ? "page" : undefined}
                title={chat.title || "Untitled chat"}
                className={cn(
                  "flex h-8 w-full items-center gap-2 rounded-[8px] text-left text-[13px] transition",
                  collapsed ? "mx-auto w-9 justify-center px-0" : "px-2.5",
                  selectedChatId === chat.id
                    ? "bg-white/75 text-[#18181b] shadow-sm shadow-black/[0.03] dark:bg-white/10 dark:text-white"
                    : "text-black/55 hover:bg-black/[0.04] hover:text-black/80 dark:text-white/55 dark:hover:bg-white/[0.08] dark:hover:text-white/85",
                )}
              >
                <HugeiconsIcon
                  icon={MessageMultiple01Icon}
                  size={14}
                  color="currentColor"
                  strokeWidth={1.5}
                  aria-hidden="true"
                  className="shrink-0"
                />
                <span className={cn("min-w-0 flex-1 truncate", collapsed && "sr-only")}>{chat.title || "Untitled chat"}</span>
              </Link>
            ))}
          </div>
        </div>
      ) : <div className="min-h-0 flex-1" />}
    </>
  );
}

type SidebarNavItem = {
  href: string;
  label: string;
  icon: () => ReactNode;
};

function SidebarNavLink({ collapsed, item, pathname, active: activeOverride }: { collapsed: boolean; item: SidebarNavItem; pathname: string; active?: boolean }) {
  const active = activeOverride ?? (pathname === item.href || pathname.startsWith(`${item.href}/`));
  const Icon = item.icon;

  return (
    <Link
      href={item.href}
      aria-current={active ? "page" : undefined}
      className={cn(
        "flex h-8 items-center gap-2 rounded-[8px] text-[13px] font-medium transition",
        collapsed ? "mx-auto w-9 justify-center px-0" : "px-2.5",
        active
          ? "bg-white/75 text-[#18181b] shadow-sm shadow-black/[0.03] dark:bg-white/10 dark:text-white"
          : "text-black/65 hover:bg-black/5 dark:text-white/65 dark:hover:bg-white/10",
      )}
    >
      <Icon />
      <span className={collapsed ? "sr-only" : undefined}>{item.label}</span>
    </Link>
  );
}

function SettingsIcon() {
  return (
    <HugeiconsIcon
      icon={Settings02Icon}
      size={16}
      color="currentColor"
      strokeWidth={1.5}
      aria-hidden="true"
      className="shrink-0"
    />
  );
}

function AccountIcon() {
  return <UserRound className="size-4 shrink-0" />;
}

function ArtifactsIcon() {
  return <HugeiconsIcon icon={Shapes01Icon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" className="shrink-0" />;
}

function ChatIcon() {
  return <HugeiconsIcon icon={MessageMultiple01Icon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" className="shrink-0" />;
}

function RoutinesIcon() {
  return <HugeiconsIcon icon={ZapIcon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" className="shrink-0" />;
}

function IntegrationsIcon() {
  return <HugeiconsIcon icon={PlugSocketIcon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" className="shrink-0" />;
}
