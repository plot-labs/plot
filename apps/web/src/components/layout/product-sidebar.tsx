"use client";

import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import {
  CalendarClock,
  Check,
  ChevronDown,
  CreditCard,
  FileText,
  FolderOpen,
  LogOut,
  MessageSquareText,
  Mic2,
  Monitor,
  Moon,
  PackageOpen,
  Puzzle,
  Plus,
  Search,
  Settings,
  Sun,
  UserRound,
  UserPlus,
} from "lucide-react";

import { AccountSettingsModal } from "@/components/layout/account-settings-modal";
import type { ProductTheme } from "@/components/layout/product-shell";
import { getProductShellData } from "@/lib/api-client";
import { cn } from "@/lib/utils";

const navItems = [
  { href: "/sessions", label: "Sessions", icon: MessageSquareText },
  { href: "/sources", label: "Sources", icon: FolderOpen },
  { href: "/packs", label: "Packs", icon: PackageOpen },
  { href: "/voice", label: "Voice", icon: Mic2 },
];

type ProductSidebarProps = {
  theme: ProductTheme;
  onThemeChange: (theme: ProductTheme) => void;
};

const themeOptions = [
  { value: "system", label: "System", icon: Monitor },
  { value: "light", label: "Light", icon: Sun },
  { value: "dark", label: "Dark", icon: Moon },
] satisfies Array<{ value: ProductTheme; label: string; icon: typeof Monitor }>;

const workspaceItems = [
  { id: "personal", name: "Personal", detail: "Current workspace", mark: "P", selected: true },
  { id: "plot", name: "Plot", detail: "Dev workspace", mark: "P", selected: false },
  { id: "launch", name: "Launch copy", detail: "Draft workspace", mark: "L", selected: false },
  { id: "hiring", name: "Hiring", detail: "Research workspace", mark: "H", selected: false },
];

export function ProductSidebar({ theme, onThemeChange }: ProductSidebarProps) {
  const pathname = usePathname();
  const { workspace, sessions } = getProductShellData();
  const [workspaceMenuOpen, setWorkspaceMenuOpen] = useState(false);
  const [profileMenuOpen, setProfileMenuOpen] = useState(false);
  const [accountSettingsOpen, setAccountSettingsOpen] = useState(false);
  const workspaceMenuRef = useRef<HTMLDivElement>(null);
  const profileMenuRef = useRef<HTMLDivElement>(null);
  const workspaceSettingsHref = `/workspaces/${workspace.id}/settings`;

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
      <aside className="hidden h-screen w-[252px] shrink-0 flex-col bg-[#eef0f3] text-[#2f3237] dark:bg-[#202126] dark:text-[#f4f4f5] lg:flex">
        <div className="flex items-center gap-2 px-4 pb-4 pt-5">
          <Image src="/plot-icon.svg" alt="" width={24} height={24} className="size-6 shrink-0 dark:invert" />
          <div className="font-display text-[22px] leading-none tracking-normal text-black/85 dark:text-white/90">
            Plot
          </div>
        </div>

        <div className="space-y-1 px-3 pb-4">
          <button className="flex w-full items-center gap-2 rounded-xl px-2.5 py-1.5 text-left text-[13px] font-medium text-black/70 transition hover:bg-black/5 dark:text-white/75 dark:hover:bg-white/10">
            <Plus className="size-4" />
            New session
          </button>
          <button className="flex w-full items-center gap-2 rounded-xl px-2.5 py-1.5 text-left text-[13px] font-medium text-black/70 transition hover:bg-black/5 dark:text-white/75 dark:hover:bg-white/10">
            <Search className="size-4" />
            Search
          </button>
          <button className="flex w-full items-center gap-2 rounded-xl px-2.5 py-1.5 text-left text-[13px] font-medium text-black/70 transition hover:bg-black/5 dark:text-white/75 dark:hover:bg-white/10">
            <CalendarClock className="size-4" />
            Scheduled
            <span className="ml-auto rounded-full bg-black/[0.06] px-2 py-0.5 text-xs text-black/45 dark:bg-white/10 dark:text-white/50">
              3
            </span>
          </button>
          <button className="flex w-full items-center gap-2 rounded-xl px-2.5 py-1.5 text-left text-[13px] font-medium text-black/70 transition hover:bg-black/5 dark:text-white/75 dark:hover:bg-white/10">
            <Puzzle className="size-4" />
            Integrations
          </button>
        </div>

        <div ref={workspaceMenuRef} className="relative px-3 pb-4">
          <div className="mb-2 flex items-center justify-between">
            <div className="text-[11px] font-medium uppercase text-black/35 dark:text-white/35">Workspace</div>
            <Link
              href={workspaceSettingsHref}
              aria-label="Workspace settings"
              className="inline-flex size-7 items-center justify-center rounded-lg text-black/35 transition hover:bg-black/5 hover:text-black/60 dark:text-white/35 dark:hover:bg-white/10 dark:hover:text-white/65"
            >
              <Settings className="size-4" />
            </Link>
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
              "flex h-10 w-full items-center gap-2 rounded-xl border px-2 text-left text-[13px] font-semibold transition",
              workspaceMenuOpen
                ? "border-black/20 bg-white text-black/82 shadow-sm dark:border-white/16 dark:bg-white/10 dark:text-white"
                : "border-black/[0.12] bg-white/40 text-black/76 hover:bg-white/65 dark:border-white/12 dark:bg-white/5 dark:text-white/78 dark:hover:bg-white/10",
            )}
          >
            <span className="flex size-7 shrink-0 items-center justify-center rounded-[8px] bg-[#ef3f2c] font-serif text-[15px] font-semibold leading-none text-white">
              P
            </span>
            <span className="min-w-0 flex-1 truncate">Personal</span>
            <ChevronDown
              className={cn(
                "size-4 shrink-0 text-black/38 transition dark:text-white/40",
                workspaceMenuOpen && "rotate-180",
              )}
            />
          </button>

          {workspaceMenuOpen && (
            <div className="absolute left-3 top-[72px] z-50 w-[300px] overflow-hidden rounded-[14px] border border-black/[0.1] bg-white text-[13px] text-black/78 shadow-[0_18px_48px_rgb(15_23_42_/_0.18)] dark:border-white/10 dark:bg-[#292a2f] dark:text-white/82">
              <div className="p-4">
                <div className="flex items-center gap-3">
                  <span className="flex size-11 shrink-0 items-center justify-center rounded-[10px] bg-[#ef3f2c] font-serif text-[21px] font-semibold leading-none text-white">
                    P
                  </span>
                  <div className="min-w-0">
                    <div className="truncate text-[16px] font-semibold text-black/84 dark:text-white/88">Personal</div>
                    <div className="mt-0.5 truncate text-[13px] text-black/45 dark:text-white/45">
                      Dev workspace
                    </div>
                  </div>
                </div>

                <div className="mt-4 flex gap-2">
                  <Link
                    href={workspaceSettingsHref}
                    onClick={() => setWorkspaceMenuOpen(false)}
                    className="inline-flex h-9 items-center gap-2 rounded-[9px] border border-black/[0.1] px-3 font-medium text-black/62 transition hover:bg-black/[0.04] dark:border-white/12 dark:text-white/62 dark:hover:bg-white/10"
                  >
                    <Settings className="size-4" />
                    Settings
                  </Link>
                  <button
                    type="button"
                    className="inline-flex h-9 items-center gap-2 rounded-[9px] border border-black/[0.1] px-3 font-medium text-black/62 transition hover:bg-black/[0.04] dark:border-white/12 dark:text-white/62 dark:hover:bg-white/10"
                  >
                    <UserPlus className="size-4" />
                    Invite
                  </button>
                </div>
              </div>

              <div className="border-t border-black/[0.08] py-2 dark:border-white/10">
                {workspaceItems.map((workspace) => (
                  <button
                    key={workspace.id}
                    type="button"
                    onClick={() => setWorkspaceMenuOpen(false)}
                    className="flex w-full items-center gap-3 px-4 py-2.5 text-left transition hover:bg-black/[0.04] dark:hover:bg-white/10"
                  >
                    <span
                      className={cn(
                        "flex size-8 shrink-0 items-center justify-center rounded-[8px] font-serif text-[15px] font-semibold leading-none",
                        workspace.selected
                          ? "bg-[#ef3f2c] text-white"
                          : "bg-black/[0.06] text-black/48 dark:bg-white/10 dark:text-white/52",
                      )}
                    >
                      {workspace.mark}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-semibold text-black/78 dark:text-white/82">
                        {workspace.name}
                      </span>
                      <span className="block truncate text-xs text-black/38 dark:text-white/38">{workspace.detail}</span>
                    </span>
                    {workspace.selected && <Check className="size-4 shrink-0 text-black/72 dark:text-white/76" />}
                  </button>
                ))}
              </div>

              <div className="border-t border-black/[0.08] p-2 dark:border-white/10">
                <button
                  type="button"
                  className="flex w-full items-center gap-2 rounded-[9px] px-2 py-2 text-left font-medium text-[#1677ff] transition hover:bg-[#1677ff]/10 dark:text-[#6aa8ff] dark:hover:bg-[#6aa8ff]/12"
                >
                  <Plus className="size-4" />
                  New workspace
                </button>
              </div>
            </div>
          )}
        </div>

        <nav className="space-y-1 px-3 pb-4">
          {navItems.map((item) => {
            const Icon = item.icon;
            const active = pathname === item.href || pathname.startsWith(`${item.href}/`);

            return (
              <Link
                key={item.href}
                href={item.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "flex items-center gap-2 rounded-xl px-2.5 py-1.5 text-[13px] font-medium transition",
                  active
                    ? "bg-white/75 text-[#18181b] shadow-sm shadow-black/[0.03] dark:bg-white/10 dark:text-white"
                    : "text-black/65 hover:bg-black/5 dark:text-white/65 dark:hover:bg-white/10",
                )}
              >
                <Icon className="size-4" />
                {item.label}
              </Link>
            );
          })}
        </nav>

        <div className="px-3 text-[11px] font-medium uppercase text-black/35 dark:text-white/35">
          Sessions
        </div>

        <div className="min-h-0 flex-1 space-y-1 overflow-y-auto px-3 py-3">
          {sessions.map((session) => {
            return (
              <Link
                key={session.id}
                href={`/sessions?session=${session.id}`}
                className="group flex items-center gap-2 rounded-xl px-2.5 py-1.5 text-[13px] text-black/65 transition hover:bg-black/5 dark:text-white/65 dark:hover:bg-white/10"
              >
                <FileText className="size-3.5 shrink-0 text-black/40 dark:text-white/40" />
                <span className="min-w-0 flex-1 truncate">{session.title}</span>
                <span className="text-xs text-black/35 dark:text-white/35">{session.updatedAt}</span>
              </Link>
            );
          })}
        </div>

        <div ref={profileMenuRef} className="relative border-t border-black/[0.06] px-3 py-3 dark:border-white/10">
          {profileMenuOpen && (
            <div className="absolute bottom-[58px] left-16 right-3 z-50 rounded-[10px] border border-black/[0.08] bg-white p-2 text-[13px] text-black/80 shadow-[0_12px_34px_rgb(15_23_42_/_0.14)] dark:border-white/10 dark:bg-[#2a2b30] dark:text-white/85">
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
                onClick={() => {
                  setProfileMenuOpen(false);
                  setAccountSettingsOpen(true);
                }}
                className="flex w-full items-center gap-2 rounded-[8px] px-2 py-2 text-left transition hover:bg-black/[0.04] dark:hover:bg-white/10"
              >
                <UserRound className="size-4 text-black/45 dark:text-white/45" />
                Account settings
              </button>
              <button
                type="button"
                className="flex w-full items-center gap-2 rounded-[8px] px-2 py-2 text-left transition hover:bg-black/[0.04] dark:hover:bg-white/10"
              >
                <CreditCard className="size-4 text-black/45 dark:text-white/45" />
                Billing
              </button>
              <button
                type="button"
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
            className="flex w-full items-center gap-2 rounded-2xl px-1 py-1 text-left transition hover:bg-black/[0.04] dark:hover:bg-white/10"
          >
            <div className="flex size-8 items-center justify-center rounded-full border border-black/10 bg-white text-xs font-semibold text-black/65 dark:border-white/10 dark:bg-white/10 dark:text-white/75">
              <UserRound className="size-4" />
            </div>
            <div className="min-w-0">
              <div className="truncate text-[13px] font-semibold">Plot</div>
              <div className="text-xs text-black/45 dark:text-white/45">Dev workspace</div>
            </div>
          </button>
        </div>
      </aside>

      {accountSettingsOpen && <AccountSettingsModal open onClose={() => setAccountSettingsOpen(false)} />}
    </>
  );
}
