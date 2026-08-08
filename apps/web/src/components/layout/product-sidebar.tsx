"use client";

import {
  ArrowLeft01Icon,
  MoreVerticalIcon,
  PlugSocketIcon,
  Settings02Icon,
  Shapes01Icon,
} from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import {
  Check,
  ChevronDown,
  LogOut,
  Monitor,
  Moon,
  PanelLeftClose,
  Plus,
  Sun,
  UserRound,
} from "lucide-react";

import type { ProductTheme } from "@/components/layout/product-shell";
import { cn } from "@/lib/utils";

const productNavItems = [
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

type ProductSidebarProps = {
  theme: ProductTheme;
  onThemeChange: (theme: ProductTheme) => void;
  onToggleSidebar: () => void;
};

const themeOptions = [
  { value: "system", label: "System", icon: Monitor },
  { value: "light", label: "Light", icon: Sun },
  { value: "dark", label: "Dark", icon: Moon },
] satisfies Array<{ value: ProductTheme; label: string; icon: typeof Monitor }>;

const appHomeHref = "/artifacts";

type Account = {
  user: { id: string; email: string; displayName: string };
  workspaces: Array<{ id: string; name: string; slug: string; logoUrl: string | null; role: string }>;
  defaultWorkspaceId: string;
};

export function ProductSidebar({ theme, onThemeChange, onToggleSidebar }: ProductSidebarProps) {
  const pathname = usePathname();
  const settingsMode = pathname === "/settings" || pathname.startsWith("/settings/");
  const [account, setAccount] = useState<Account | null>(null);
  const [selectedWorkspaceId, setSelectedWorkspaceId] = useState<string | null>(null);
  const [workspaceMenuOpen, setWorkspaceMenuOpen] = useState(false);
  const [profileMenuOpen, setProfileMenuOpen] = useState(false);
  const workspaceMenuRef = useRef<HTMLDivElement>(null);
  const profileMenuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/plot/me", { cache: "no-store", headers: { Accept: "application/json" } })
      .then((response) => response.ok ? response.json() as Promise<Account> : null)
      .then((value) => {
        if (cancelled || !value) return;
        setAccount(value);
        const savedId = window.localStorage.getItem("plot.workspaceId");
        const savedWorkspace = savedId ? value.workspaces.find((item) => item.id === savedId) : undefined;
        const resolvedWorkspaceId = savedWorkspace?.id ?? value.defaultWorkspaceId ?? value.workspaces[0]?.id ?? null;
        if (resolvedWorkspaceId) window.localStorage.setItem("plot.workspaceId", resolvedWorkspaceId);
        else window.localStorage.removeItem("plot.workspaceId");
        setSelectedWorkspaceId(resolvedWorkspaceId);
      })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, []);

  const currentWorkspace = account?.workspaces.find((item) => item.id === selectedWorkspaceId)
    ?? account?.workspaces.find((item) => item.id === account.defaultWorkspaceId)
    ?? account?.workspaces[0];
  const currentWorkspaceId = currentWorkspace?.id ?? selectedWorkspaceId;
  const currentWorkspaceName = currentWorkspace?.name ?? "Workspace";
  const currentWorkspaceMark = currentWorkspaceName.slice(0, 1).toUpperCase();
  const workspaceItems = account?.workspaces.map((item) => ({
    ...item,
    mark: item.name.slice(0, 1).toUpperCase(),
    selected: item.id === currentWorkspaceId,
  })) ?? [];

  useEffect(() => {
    function updateWorkspace(event: Event) {
      const workspace = (event as CustomEvent<{ id?: string; name?: string; logoUrl?: string | null }>).detail;
      if (!workspace?.id) return;
      setAccount((current) => current
        ? {
          ...current,
          workspaces: current.workspaces.map((item) => item.id === workspace.id
            ? { ...item, name: workspace.name ?? item.name, logoUrl: workspace.logoUrl === undefined ? item.logoUrl : workspace.logoUrl }
            : item),
        }
        : current);
    }

    window.addEventListener("plot:workspace-updated", updateWorkspace);
    return () => window.removeEventListener("plot:workspace-updated", updateWorkspace);
  }, []);

  useEffect(() => {
    if (!workspaceMenuOpen) {
      return;
    }

    function closeOnOutsidePointer(event: MouseEvent) {
      if (
        event.target instanceof Node &&
        workspaceMenuRef.current &&
        !workspaceMenuRef.current.contains(event.target)
      ) {
        setWorkspaceMenuOpen(false);
      }
    }

    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setWorkspaceMenuOpen(false);
      }
    }

    document.addEventListener("mousedown", closeOnOutsidePointer);
    document.addEventListener("keydown", closeOnEscape);

    return () => {
      document.removeEventListener("mousedown", closeOnOutsidePointer);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [workspaceMenuOpen]);

  useEffect(() => {
    if (!profileMenuOpen) {
      return;
    }

    function closeOnOutsidePointer(event: MouseEvent) {
      if (
        event.target instanceof Node &&
        profileMenuRef.current &&
        !profileMenuRef.current.contains(event.target)
      ) {
        setProfileMenuOpen(false);
      }
    }

    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setProfileMenuOpen(false);
      }
    }

    document.addEventListener("mousedown", closeOnOutsidePointer);
    document.addEventListener("keydown", closeOnEscape);

    return () => {
      document.removeEventListener("mousedown", closeOnOutsidePointer);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [profileMenuOpen]);

  return (
    <>
      <aside className="hidden h-dvh w-[252px] shrink-0 flex-col border-r border-black/[0.08] bg-[#f6f7f9] pr-px text-[#2f3237] dark:border-white/10 dark:bg-[#202126] dark:text-[#f4f4f5] lg:flex">
        <div className="flex items-center gap-2 px-4 pb-4 pt-5">
          <Link
            href={appHomeHref}
            aria-label="Plot home"
            className="flex min-w-0 flex-1 items-center gap-2"
          >
            <Image src="/plot-icon.svg" alt="" width={24} height={24} className="size-6 shrink-0 dark:invert" />
            <div className="font-display text-[22px] leading-none tracking-normal text-black/85 dark:text-white/90">
              Plot
            </div>
          </Link>
          <button
            type="button"
            onClick={onToggleSidebar}
            aria-label="Close sidebar"
            className="inline-flex size-8 shrink-0 items-center justify-center rounded-[8px] text-black/35 transition hover:bg-black/5 hover:text-black/65 dark:text-white/38 dark:hover:bg-white/10 dark:hover:text-white/70"
          >
            <PanelLeftClose className="size-4" />
          </button>
        </div>

        <div ref={workspaceMenuRef} className="relative px-3 pb-4">
              <div className="mb-2 flex h-7 items-center pl-0.5">
                <div className="text-[11px] font-medium uppercase text-black/35 dark:text-white/35">Workspace</div>
              </div>

              <button
                type="button"
                onClick={() => {
                  setWorkspaceMenuOpen((open) => !open);
                  setProfileMenuOpen(false);
                }}
                aria-expanded={workspaceMenuOpen}
                aria-haspopup="menu"
                className={cn(
                  "flex h-9 w-full items-center gap-2 rounded-[8px] border px-2 text-left text-[13px] font-semibold transition",
                  workspaceMenuOpen
                    ? "border-black/20 bg-white text-black/82 shadow-sm dark:border-white/16 dark:bg-white/10 dark:text-white"
                    : "border-black/[0.12] bg-white/40 text-black/76 hover:bg-white/65 dark:border-white/12 dark:bg-white/5 dark:text-white/78 dark:hover:bg-white/10",
                )}
              >
                <WorkspaceAvatar logoUrl={currentWorkspace?.logoUrl} mark={currentWorkspaceMark} variant="trigger" />
                <span className="min-w-0 flex-1 truncate">{currentWorkspaceName}</span>
                <ChevronDown
                  className={cn(
                    "size-4 shrink-0 text-black/38 transition dark:text-white/40",
                    workspaceMenuOpen && "rotate-180",
                  )}
                />
              </button>

              {workspaceMenuOpen && (
                <div
                  role="menu"
                  aria-label="Workspace menu"
                  className="absolute left-3 top-[76px] z-50 w-[228px] overflow-hidden rounded-[12px] border border-black/[0.08] bg-white text-[13px] text-black/76 shadow-[0_12px_30px_rgb(15_23_42_/_0.1)] dark:border-white/10 dark:bg-[#292a2f] dark:text-white/80"
                >
                  <div className="border-b border-black/[0.08] px-3 pb-1.5 pt-2.5 text-[12px] font-medium text-black/45 dark:border-white/10 dark:text-white/45">
                    Workspaces
                  </div>
                  <div className="p-1">
                    {workspaceItems.map((workspace) => (
                      <button
                        key={workspace.id}
                        type="button"
                        role="menuitemradio"
                        aria-label={`${workspace.name} workspace`}
                        aria-checked={workspace.selected}
                        onClick={() => {
                          window.localStorage.setItem("plot.workspaceId", workspace.id);
                          setSelectedWorkspaceId(workspace.id);
                          setWorkspaceMenuOpen(false);
                          window.location.reload();
                        }}
                        className={cn(
                          "flex h-9 w-full items-center gap-2.5 rounded-[8px] px-2 text-left font-medium transition hover:bg-black/[0.04] focus-visible:bg-black/[0.04] focus-visible:outline-none dark:hover:bg-white/10 dark:focus-visible:bg-white/10",
                          workspace.selected && "bg-black/[0.035] text-black/82 dark:bg-white/[0.07] dark:text-white/88",
                        )}
                      >
                        <WorkspaceAvatar logoUrl={workspace.logoUrl} mark={workspace.mark} variant="menu" />
                        <span className="min-w-0 flex-1 truncate text-[15px]">{workspace.name}</span>
                        {workspace.selected && <Check className="size-4 shrink-0 text-black/55 dark:text-white/60" />}
                      </button>
                    ))}
                  </div>
                  <div className="border-t border-black/[0.08] p-1 dark:border-white/10">
                    <button
                      type="button"
                      disabled
                      aria-label="Create workspace"
                      title="Workspace creation coming soon"
                      className="flex h-9 w-full items-center gap-2.5 rounded-[8px] px-2 text-left text-[13px] font-medium text-black/40 dark:text-white/40"
                    >
                      <Plus className="size-5 shrink-0" />
                      Create workspace
                    </button>
                  </div>
                </div>
              )}
        </div>

        {settingsMode && (
          <div className="px-3 pb-4">
            <Link
              href={appHomeHref}
              className="flex h-8 items-center gap-2 rounded-[8px] px-2 text-[13px] font-medium text-black/52 transition hover:bg-black/[0.04] hover:text-black/78 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 dark:text-white/52 dark:hover:bg-white/[0.07] dark:hover:text-white/82 dark:focus-visible:ring-white/25"
            >
              <HugeiconsIcon
                icon={ArrowLeft01Icon}
                size={16}
                color="currentColor"
                strokeWidth={1.5}
                aria-hidden="true"
                className="shrink-0"
              />
              Back to app
            </Link>
          </div>
        )}

        <nav
          className="space-y-1 px-3 pb-4"
          aria-label={settingsMode ? "Settings navigation" : "Product sidebar navigation"}
        >
          {settingsMode ? workspaceSettingsNavGroups.map((group) => (
            <div key={group.label}>
              <div className="px-2 pb-1 pt-3 text-[11px] font-medium uppercase tracking-[0.08em] text-black/35 dark:text-white/35">
                {group.label}
              </div>
              <div className="space-y-1">
                {group.items.map((item) => <SidebarNavLink key={item.href} item={item} pathname={pathname} />)}
              </div>
            </div>
          )) : productNavItems.map((item) => <SidebarNavLink key={item.href} item={item} pathname={pathname} />)}
        </nav>

        <div className="min-h-0 flex-1" />

        <div ref={profileMenuRef} className="relative border-t border-black/[0.06] px-3 py-3 dark:border-white/10">
          {profileMenuOpen && (
            <div className="absolute bottom-[58px] left-3 right-3 z-50 rounded-[10px] border border-black/[0.08] bg-white p-2 text-[13px] text-black/80 shadow-[0_12px_34px_rgb(15_23_42_/_0.14)] dark:border-white/10 dark:bg-[#2a2b30] dark:text-white/85">
              <Link
                href="/settings/account"
                onClick={() => setProfileMenuOpen(false)}
                className="flex w-full items-center gap-2 rounded-[8px] px-2 py-2 text-left transition hover:bg-black/[0.04] dark:hover:bg-white/10"
              >
                <SettingsIcon />
                Settings
              </Link>
              <div className="flex items-center justify-between gap-3 px-2 py-2">
                <div className="font-medium">Theme</div>
                <div
                  className="flex items-center rounded-[8px] bg-black/[0.06] p-0.5 dark:bg-white/10"
                  role="radiogroup"
                  aria-label="Display theme"
                >
                  {themeOptions.map((option) => {
                    const Icon = option.icon;
                    const active = theme === option.value;

                    return (
                      <button
                        key={option.value}
                        type="button"
                        role="radio"
                        aria-checked={active}
                        aria-label={option.label}
                        onClick={() => onThemeChange(option.value)}
                        className={cn(
                          "inline-flex size-8 items-center justify-center rounded-[6px] transition",
                          active
                            ? "bg-white text-black shadow-sm dark:bg-[#3a3b40] dark:text-white"
                            : "text-black/35 hover:text-black/65 dark:text-white/35 dark:hover:text-white/70",
                        )}
                      >
                        <Icon className="size-4" />
                      </button>
                    );
                  })}
                </div>
              </div>

              <button
                type="button"
                onClick={async () => {
                  await fetch("/api/auth/sign-out", { method: "POST", credentials: "include" });
                  window.location.assign("/sign-in");
                }}
                className="flex w-full items-center gap-2 rounded-[8px] px-2 py-2 text-left transition hover:bg-black/[0.04] dark:hover:bg-white/10"
              >
                <LogOut className="size-4 text-black/45 dark:text-white/45" />
                Sign out
              </button>
            </div>
          )}

          <button
            type="button"
            onClick={() => setProfileMenuOpen((open) => !open)}
            aria-expanded={profileMenuOpen}
            aria-haspopup="menu"
            className="flex w-full items-center gap-2 rounded-[16px] px-1 py-1 text-left transition hover:bg-black/[0.04] dark:hover:bg-white/10"
          >
            <div className="flex size-8 items-center justify-center rounded-full border border-black/10 bg-white text-xs font-semibold text-black/65 dark:border-white/10 dark:bg-white/10 dark:text-white/75">
              <UserRound className="size-4" />
            </div>
            <div className="min-w-0 flex-1">
              <div className="truncate text-[13px] font-semibold">{account?.user.displayName ?? "Plot"}</div>
              <div className="truncate text-xs text-black/45 dark:text-white/45">{account?.user.email ?? currentWorkspaceName}</div>
            </div>
            <HugeiconsIcon
              icon={MoreVerticalIcon}
              size={16}
              color="currentColor"
              strokeWidth={1.5}
              aria-hidden="true"
              className="ml-auto shrink-0 text-black/38 dark:text-white/42"
            />
          </button>
        </div>
      </aside>
    </>
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

type SidebarNavItem = {
  href: string;
  label: string;
  icon: () => ReactNode;
};

function SidebarNavLink({ item, pathname }: { item: SidebarNavItem; pathname: string }) {
  const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
  const Icon = item.icon;

  return (
    <Link
      href={item.href}
      aria-current={active ? "page" : undefined}
      className={cn(
        "flex h-8 items-center gap-2 rounded-[8px] px-2.5 text-[13px] font-medium transition",
        active
          ? "bg-white/75 text-[#18181b] shadow-sm shadow-black/[0.03] dark:bg-white/10 dark:text-white"
          : "text-black/65 hover:bg-black/5 dark:text-white/65 dark:hover:bg-white/10",
      )}
    >
      <Icon />
      {item.label}
    </Link>
  );
}

function WorkspaceAvatar({
  logoUrl,
  mark,
  variant,
}: {
  logoUrl?: string | null;
  mark: string;
  variant: "menu" | "trigger";
}) {
  const isMenu = variant === "menu";
  return (
    <span
      className={cn(
        "flex shrink-0 items-center justify-center overflow-hidden font-serif font-semibold leading-none",
        isMenu ? "size-7 rounded-[8px] text-[13px]" : "size-6 rounded-[7px] text-[14px]",
        logoUrl ? "bg-black/[0.04] dark:bg-white/10" : "bg-[#ef3f2c] text-white",
      )}
    >
      {logoUrl ? (
        <Image
          src={logoUrl}
          alt=""
          width={isMenu ? 28 : 24}
          height={isMenu ? 28 : 24}
          unoptimized
          className="size-full object-cover"
        />
      ) : mark}
    </span>
  );
}

function ArtifactsIcon() {
  return (
    <HugeiconsIcon
      icon={Shapes01Icon}
      size={16}
      color="currentColor"
      strokeWidth={1.5}
      aria-hidden="true"
      className="shrink-0"
    />
  );
}

function IntegrationsIcon() {
  return (
    <HugeiconsIcon
      icon={PlugSocketIcon}
      size={16}
      color="currentColor"
      strokeWidth={1.5}
      aria-hidden="true"
      className="shrink-0"
    />
  );
}
