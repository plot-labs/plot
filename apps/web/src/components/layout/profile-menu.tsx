"use client";

import { MoreVerticalIcon, Settings02Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import { LogOut, Monitor, Moon, Sun, UserRound } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef } from "react";
import type { Dispatch, SetStateAction } from "react";

import type { ProductTheme } from "@/components/layout/product-shell";
import type { SidebarAccount } from "@/components/layout/use-sidebar-workspace";
import { cn } from "@/lib/utils";

const themeOptions = [
  { value: "system", label: "System", icon: Monitor },
  { value: "light", label: "Light", icon: Sun },
  { value: "dark", label: "Dark", icon: Moon },
] satisfies Array<{ value: ProductTheme; label: string; icon: typeof Monitor }>;

type ProfileMenuProps = {
  collapsed: boolean;
  theme: ProductTheme;
  onThemeChange: (theme: ProductTheme) => void;
  account: SidebarAccount | null;
  currentWorkspaceName: string;
  profileMenuOpen: boolean;
  setProfileMenuOpen: Dispatch<SetStateAction<boolean>>;
  onOpen: () => void;
};

export function ProfileMenu({
  collapsed,
  theme,
  onThemeChange,
  account,
  currentWorkspaceName,
  profileMenuOpen,
  setProfileMenuOpen,
  onOpen,
}: ProfileMenuProps) {
  const profileMenuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!profileMenuOpen) return;

    function closeOnOutsidePointer(event: MouseEvent) {
      if (event.target instanceof Node && profileMenuRef.current && !profileMenuRef.current.contains(event.target)) {
        setProfileMenuOpen(false);
      }
    }

    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") setProfileMenuOpen(false);
    }

    document.addEventListener("mousedown", closeOnOutsidePointer);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("mousedown", closeOnOutsidePointer);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [profileMenuOpen, setProfileMenuOpen]);

  return (
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
        onClick={() => {
          if (!profileMenuOpen) onOpen();
          setProfileMenuOpen(!profileMenuOpen);
        }}
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
