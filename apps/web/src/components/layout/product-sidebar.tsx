"use client";

import {
  ArrowLeft01Icon,
  MessageMultiple01Icon,
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
import type { FormEvent, ReactNode } from "react";
import type { WorkSessionSummary as ChatSummary } from "@plot/api-client";
import {
  Check,
  ChevronDown,
  LogOut,
  Monitor,
  Moon,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Sun,
  UserRound,
} from "lucide-react";

import type { ProductTheme } from "@/components/layout/product-shell";
import { plotApiClient } from "@/lib/api-client";
import { cn } from "@/lib/utils";

const productNavItems = [
  { href: "/chat", label: "Chat", icon: ChatIcon },
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
  collapsed?: boolean;
  theme: ProductTheme;
  onThemeChange: (theme: ProductTheme) => void;
  onToggleSidebar: () => void;
};

const themeOptions = [
  { value: "system", label: "System", icon: Monitor },
  { value: "light", label: "Light", icon: Sun },
  { value: "dark", label: "Dark", icon: Moon },
] satisfies Array<{ value: ProductTheme; label: string; icon: typeof Monitor }>;

const appHomeHref = "/chat";

type Account = {
  user: { id: string; email: string; displayName: string };
  workspaces: Array<{ id: string; name: string; slug: string; logoUrl: string | null; role: string }>;
  defaultWorkspaceId: string;
};

export function ProductSidebar({ collapsed = false, theme, onThemeChange, onToggleSidebar }: ProductSidebarProps) {
  const pathname = usePathname();
  const settingsMode = pathname === "/settings" || pathname.startsWith("/settings/");
  const [account, setAccount] = useState<Account | null>(null);
  const [selectedWorkspaceId, setSelectedWorkspaceId] = useState<string | null>(null);
  const [recentChats, setRecentChats] = useState<ChatSummary[]>([]);
  const [workspaceMenuOpen, setWorkspaceMenuOpen] = useState(false);
  const [creatingWorkspace, setCreatingWorkspace] = useState(false);
  const [workspaceName, setWorkspaceName] = useState("");
  const [workspaceCreateError, setWorkspaceCreateError] = useState<string | null>(null);
  const [isCreatingWorkspace, setIsCreatingWorkspace] = useState(false);
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

  useEffect(() => {
    if (settingsMode || !selectedWorkspaceId) return;

    const controller = new AbortController();
    void plotApiClient.listSessions({ signal: controller.signal })
      .then((value) => {
        if (!controller.signal.aborted) setRecentChats(value.slice(0, 8));
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, [settingsMode, selectedWorkspaceId]);

  const currentWorkspace = account?.workspaces.find((item) => item.id === selectedWorkspaceId)
    ?? account?.workspaces.find((item) => item.id === account.defaultWorkspaceId)
    ?? account?.workspaces[0];
  const currentWorkspaceId = currentWorkspace?.id ?? selectedWorkspaceId;
  const currentWorkspaceName = currentWorkspace?.name ?? "Workspace";
  const currentWorkspaceMark = currentWorkspaceName.slice(0, 1).toUpperCase();
  const workspaceLoading = !account;
  const workspaceItems = account?.workspaces.map((item) => ({
    ...item,
    mark: item.name.slice(0, 1).toUpperCase(),
    selected: item.id === currentWorkspaceId,
  })) ?? [];

  async function handleCreateWorkspace(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const name = workspaceName.trim();
    if (!name || isCreatingWorkspace) return;

    setIsCreatingWorkspace(true);
    setWorkspaceCreateError(null);
    try {
      const workspace = await plotApiClient.createWorkspace({ name });
      window.localStorage.setItem("plot.workspaceId", workspace.id);
      setAccount((current) => current
        ? { ...current, workspaces: [...current.workspaces, { ...workspace, role: workspace.role ?? "OWNER" }] }
        : current);
      setSelectedWorkspaceId(workspace.id);
      setWorkspaceMenuOpen(false);
      setCreatingWorkspace(false);
      setWorkspaceName("");
      window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: workspace.id } }));
    } catch {
      setWorkspaceCreateError("Workspace could not be created.");
    } finally {
      setIsCreatingWorkspace(false);
    }
  }

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
    if (!creatingWorkspace) return;
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setCreatingWorkspace(false);
        setWorkspaceCreateError(null);
      }
    }
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [creatingWorkspace]);

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
      <aside className={cn(
        "hidden h-dvh shrink-0 flex-col border-r border-black/[0.08] bg-[#f6f7f9] pr-px text-[#2f3237] transition-[width] duration-200 dark:border-white/10 dark:bg-[#202126] dark:text-[#f4f4f5] lg:flex",
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
            <div className="font-display text-[22px] leading-none tracking-normal text-black/85 dark:text-white/90">
              Plot
            </div>
          </Link>
          <button
            type="button"
            onClick={onToggleSidebar}
            aria-label={collapsed ? "Open sidebar" : "Close sidebar"}
            className={cn(
              "inline-flex shrink-0 items-center justify-center rounded-[8px] text-black/35 transition hover:bg-black/5 hover:text-black/65 dark:text-white/38 dark:hover:bg-white/10 dark:hover:text-white/70",
              collapsed ? "size-9" : "size-8",
            )}
          >
            {collapsed ? <PanelLeftOpen className="size-4" /> : <PanelLeftClose className="size-4" />}
          </button>
        </div>

        <div ref={workspaceMenuRef} className={cn("relative pb-4", collapsed ? "px-2" : "px-3")}>
              <div className={cn("mb-2 flex h-7 items-center pl-0.5", collapsed && "hidden")}>
                <div className="text-[11px] font-medium uppercase text-black/35 dark:text-white/35">Workspace</div>
              </div>

              <button
                type="button"
                onClick={() => {
                  setWorkspaceMenuOpen((open) => !open);
                  setProfileMenuOpen(false);
                }}
                disabled={workspaceLoading}
                aria-label={workspaceLoading ? "Loading workspace" : currentWorkspaceName}
                aria-busy={workspaceLoading}
                aria-expanded={workspaceMenuOpen}
                aria-haspopup="menu"
                className={cn(
                  collapsed
                    ? "mx-auto flex size-10 items-center justify-center rounded-[12px] border-0 px-0 text-left transition"
                    : "flex h-9 w-full items-center gap-2 rounded-[8px] border px-2 text-left text-[13px] font-semibold transition",
                  collapsed
                    ? workspaceMenuOpen
                      ? "bg-white text-black/82 shadow-sm dark:bg-white/10 dark:text-white"
                      : "hover:bg-white/65 dark:hover:bg-white/10"
                    : workspaceMenuOpen
                      ? "border-black/20 bg-white text-black/82 shadow-sm dark:border-white/16 dark:bg-white/10 dark:text-white"
                      : "border-black/[0.12] bg-white/40 text-black/76 hover:bg-white/65 dark:border-white/12 dark:bg-white/5 dark:text-white/78 dark:hover:bg-white/10",
                  workspaceLoading && "cursor-wait opacity-80",
                )}
              >
                {workspaceLoading ? (
                  <span aria-hidden="true" className="size-6 rounded-[7px] bg-black/[0.06] dark:bg-white/[0.08]" />
                ) : (
                  <>
                    <WorkspaceAvatar logoUrl={currentWorkspace?.logoUrl} mark={currentWorkspaceMark} variant="trigger" />
                    <span className={cn("min-w-0 flex-1 truncate", collapsed && "sr-only")}>{currentWorkspaceName}</span>
                    <ChevronDown
                      className={cn(
                        "size-4 shrink-0 text-black/38 transition dark:text-white/40",
                        collapsed && "hidden",
                        workspaceMenuOpen && "rotate-180",
                      )}
                    />
                  </>
                )}
              </button>

              {workspaceMenuOpen && (
                <div
                  role="menu"
                  aria-label="Workspace menu"
                  className={cn(
                    "absolute z-50 w-[228px] overflow-hidden rounded-[12px] border border-black/[0.08] bg-white text-[13px] text-black/76 shadow-[0_12px_30px_rgb(15_23_42_/_0.1)] dark:border-white/10 dark:bg-[#292a2f] dark:text-white/80",
                    collapsed ? "left-2 top-[48px]" : "left-3 top-[76px]",
                  )}
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
                          if (workspace.id === currentWorkspaceId) {
                            setWorkspaceMenuOpen(false);
                            return;
                          }
                          window.localStorage.setItem("plot.workspaceId", workspace.id);
                          setSelectedWorkspaceId(workspace.id);
                          setWorkspaceMenuOpen(false);
                          window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: workspace.id } }));
                        }}
                        className={cn(
                          "flex h-9 w-full items-center gap-2 rounded-[8px] px-2 text-left font-medium transition hover:bg-black/[0.04] focus-visible:bg-black/[0.04] focus-visible:outline-none dark:hover:bg-white/10 dark:focus-visible:bg-white/10",
                          workspace.selected && "bg-black/[0.035] text-black/82 dark:bg-white/[0.07] dark:text-white/88",
                        )}
                      >
                        <WorkspaceAvatar logoUrl={workspace.logoUrl} mark={workspace.mark} variant="menu" />
                        <span className="min-w-0 flex-1 truncate text-[13px]">{workspace.name}</span>
                        {workspace.selected && <Check className="size-4 shrink-0 text-black/55 dark:text-white/60" />}
                      </button>
                    ))}
                  </div>
                  <div className="border-t border-black/[0.08] p-1 dark:border-white/10">
                    <button
                      type="button"
                      aria-label="Create workspace"
                      onClick={() => {
                        setWorkspaceMenuOpen(false);
                        setCreatingWorkspace(true);
                        setWorkspaceName("");
                        setWorkspaceCreateError(null);
                      }}
                      className="flex h-9 w-full items-center gap-2.5 rounded-[8px] px-2 text-left text-[13px] font-medium text-black/65 transition hover:bg-black/[0.04] dark:text-white/65 dark:hover:bg-white/10"
                    >
                      <Plus className="size-5 shrink-0" />
                      Create workspace
                    </button>
                  </div>
                </div>
              )}
        </div>

        {settingsMode && (
          <div className={cn("pb-4", collapsed ? "px-2" : "px-3")}>
            <Link
              href={appHomeHref}
              className={cn(
                "flex h-8 items-center gap-2 rounded-[8px] text-[13px] font-medium text-black/52 transition hover:bg-black/[0.04] hover:text-black/78 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 dark:text-white/52 dark:hover:bg-white/[0.07] dark:hover:text-white/82 dark:focus-visible:ring-white/25",
                collapsed ? "justify-center px-0" : "px-2",
              )}
            >
              <HugeiconsIcon
                icon={ArrowLeft01Icon}
                size={16}
                color="currentColor"
                strokeWidth={1.5}
                aria-hidden="true"
                className="shrink-0"
              />
              <span className={collapsed ? "sr-only" : undefined}>Back to app</span>
            </Link>
          </div>
        )}

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
          )) : productNavItems.map((item) => <SidebarNavLink key={item.href} collapsed={collapsed} item={item} pathname={pathname} />)}
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
                  title={chat.title || "Untitled chat"}
                  className={cn(
                    "flex h-8 w-full items-center gap-2 rounded-[8px] text-left text-[13px] transition",
                    collapsed ? "mx-auto w-9 justify-center px-0" : "px-2.5",
                    "text-black/55 hover:bg-black/[0.04] hover:text-black/80 dark:text-white/55 dark:hover:bg-white/[0.08] dark:hover:text-white/85",
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

        <div ref={profileMenuRef} className={cn(
          "relative border-t border-black/[0.06] py-3 dark:border-white/10",
          collapsed ? "px-2" : "px-3",
        )}>
          {profileMenuOpen && (
            <div className={cn(
              "absolute bottom-[58px] z-50 rounded-[10px] border border-black/[0.08] bg-white p-2 text-[13px] text-black/80 shadow-[0_12px_34px_rgb(15_23_42_/_0.14)] dark:border-white/10 dark:bg-[#2a2b30] dark:text-white/85",
              collapsed ? "left-2 w-[228px]" : "left-3 right-3",
            )}>
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

              <Link
                href="/settings/account"
                onClick={() => setProfileMenuOpen(false)}
                className="flex w-full items-center gap-2 rounded-[8px] px-2 py-2 text-left transition hover:bg-black/[0.04] dark:hover:bg-white/10"
              >
                <SettingsIcon />
                Settings
              </Link>

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
            className={cn(
              "flex w-full items-center gap-2 rounded-[16px] py-1 text-left transition hover:bg-black/[0.04] dark:hover:bg-white/10",
              collapsed ? "justify-center px-0" : "px-1",
            )}
          >
            <div className={cn(
              "flex items-center justify-center border border-black/10 bg-white text-xs font-semibold text-black/65 dark:border-white/10 dark:bg-white/10 dark:text-white/75",
              collapsed ? "size-9 rounded-[12px]" : "size-8 rounded-full",
            )}>
              <UserRound className="size-4" />
            </div>
            <div className={cn("min-w-0 flex-1", collapsed && "sr-only")}>
              <div className="truncate text-[13px] font-semibold">{account?.user.displayName ?? "Plot"}</div>
              <div className="truncate text-xs text-black/45 dark:text-white/45">{account?.user.email ?? currentWorkspaceName}</div>
            </div>
            <HugeiconsIcon
              icon={MoreVerticalIcon}
              size={16}
              color="currentColor"
              strokeWidth={1.5}
              aria-hidden="true"
              className={cn("ml-auto shrink-0 text-black/38 dark:text-white/42", collapsed && "hidden")}
            />
          </button>
        </div>
      </aside>

      {creatingWorkspace && (
        <div
          className="fixed inset-0 z-[100] flex items-center justify-center bg-black/20 p-4 backdrop-blur-[2px] dark:bg-black/45"
          onMouseDown={() => {
            setCreatingWorkspace(false);
            setWorkspaceCreateError(null);
          }}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="create-workspace-title"
            className="w-full max-w-[380px] rounded-[16px] border border-black/[0.1] bg-white p-6 text-black/85 shadow-[0_18px_50px_rgb(15_23_42_/_0.16)] dark:border-white/12 dark:bg-[#292a2f] dark:text-white/88"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <h2 id="create-workspace-title" className="font-display text-[24px] leading-none tracking-normal">Create workspace</h2>
            <p className="mt-2 text-[13px] leading-5 text-black/48 dark:text-white/50">Give your new workspace a name to get started.</p>
            <form onSubmit={(event) => void handleCreateWorkspace(event)} className="mt-6 space-y-4">
              <div className="space-y-2">
                <label htmlFor="workspace-name" className="text-[12px] font-medium text-black/68 dark:text-white/70">Workspace name</label>
                <input
                  id="workspace-name"
                  autoFocus
                  value={workspaceName}
                  onChange={(event) => setWorkspaceName(event.target.value)}
                  placeholder="e.g. Product"
                  maxLength={80}
                  className="h-10 w-full rounded-[9px] border border-black/12 bg-white px-3 text-sm text-black/80 outline-none placeholder:text-black/35 focus:border-black/30 focus:ring-2 focus:ring-black/[0.06] dark:border-white/14 dark:bg-white/[0.06] dark:text-white/88 dark:placeholder:text-white/35"
                />
                {workspaceCreateError && <p role="alert" className="text-[12px] text-red-600 dark:text-red-300">{workspaceCreateError}</p>}
              </div>
              <div className="flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setCreatingWorkspace(false);
                    setWorkspaceCreateError(null);
                  }}
                  className="h-9 rounded-[9px] px-3 text-[13px] font-medium text-black/55 transition hover:bg-black/[0.05] dark:text-white/58 dark:hover:bg-white/10"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={!workspaceName.trim() || isCreatingWorkspace}
                  className="h-9 rounded-[9px] bg-[#ef3f2c] px-3.5 text-[13px] font-medium text-white transition hover:bg-[#dc3424] disabled:cursor-not-allowed disabled:opacity-45"
                >
                  {isCreatingWorkspace ? "Creating…" : "Create workspace"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
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

function SidebarNavLink({ collapsed, item, pathname }: { collapsed: boolean; item: SidebarNavItem; pathname: string }) {
  const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
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
        isMenu ? "size-6 rounded-[7px] text-[12px]" : "size-6 rounded-[7px] text-[14px]",
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

function ChatIcon() {
  return (
    <HugeiconsIcon
      icon={MessageMultiple01Icon}
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
