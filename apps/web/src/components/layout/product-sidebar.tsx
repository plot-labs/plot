"use client";

import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import {
  CalendarClock,
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
  Sun,
  UserRound,
} from "lucide-react";

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

export function ProductSidebar({ theme, onThemeChange }: ProductSidebarProps) {
  const pathname = usePathname();
  const { sessions } = getProductShellData();
  const [profileMenuOpen, setProfileMenuOpen] = useState(false);
  const profileMenuRef = useRef<HTMLDivElement>(null);

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

      <div className="px-3 pb-2 text-[11px] font-medium uppercase text-black/35 dark:text-white/35">
        Workspace
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

            <Link
              href="/settings"
              onClick={() => setProfileMenuOpen(false)}
              className="flex items-center gap-2 rounded-[8px] px-2 py-2 transition hover:bg-black/[0.04] dark:hover:bg-white/10"
            >
              <UserRound className="size-4 text-black/45 dark:text-white/45" />
              Account settings
            </Link>
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
  );
}
