"use client";

import { PlugSocketIcon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import type { ReactNode } from "react";
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { ChevronDown, PanelLeftOpen } from "lucide-react";

import { ProductSidebar } from "@/components/layout/product-sidebar";
import { cn } from "@/lib/utils";

export type ProductTheme = "system" | "light" | "dark";

export function ProductShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const [theme, setTheme] = useState<ProductTheme>("light");
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [systemDark, setSystemDark] = useState(() => {
    if (typeof window === "undefined") {
      return false;
    }

    return window.matchMedia("(prefers-color-scheme: dark)").matches;
  });

  useEffect(() => {
    const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");

    function updateSystemTheme(event: MediaQueryListEvent) {
      setSystemDark(event.matches);
    }

    mediaQuery.addEventListener("change", updateSystemTheme);

    return () => mediaQuery.removeEventListener("change", updateSystemTheme);
  }, []);

  const darkMode = theme === "dark" || (theme === "system" && systemDark);

  return (
    <div className={darkMode ? "dark" : undefined}>
      <div className="flex min-h-dvh bg-[#eef0f3] text-[#18181b] dark:bg-[#202126] dark:text-[#f4f4f5] lg:h-dvh lg:overflow-hidden">
        {sidebarOpen && (
          <ProductSidebar
            theme={theme}
            onThemeChange={setTheme}
            onToggleSidebar={() => setSidebarOpen(false)}
          />
        )}

        <div
          className={cn(
            "relative flex min-w-0 flex-1 flex-col overflow-hidden bg-[#eef0f3] dark:bg-[#111113]",
            !sidebarOpen && "shell-sidebar-closed",
          )}
        >
          {!sidebarOpen && (
            <button
              type="button"
              onClick={() => setSidebarOpen(true)}
              aria-label="Open sidebar"
              className="absolute left-4 top-3 z-40 hidden size-8 items-center justify-center rounded-xl text-black/42 transition hover:bg-black/5 hover:text-black/70 dark:text-white/45 dark:hover:bg-white/10 dark:hover:text-white/75 lg:inline-flex"
            >
              <PanelLeftOpen className="size-4" />
            </button>
          )}
          <MobileProductNavigation pathname={pathname} />
          <main className="min-h-0 w-full flex-1 overflow-y-auto lg:overflow-hidden">{children}</main>
        </div>
      </div>
    </div>
  );
}

function MobileProductNavigation({ pathname }: { pathname: string }) {
  const [workspaceMenuOpen, setWorkspaceMenuOpen] = useState(false);
  const workspaceMenuRef = useRef<HTMLDivElement>(null);
  const artifactsActive = pathname === "/artifacts" || pathname.startsWith("/artifacts/");
  const integrationsActive = pathname === "/integrations" || pathname.startsWith("/integrations/");

  useEffect(() => {
    if (!workspaceMenuOpen) return;

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
      if (event.key === "Escape") setWorkspaceMenuOpen(false);
    }

    document.addEventListener("mousedown", closeOnOutsidePointer);
    document.addEventListener("keydown", closeOnEscape);

    return () => {
      document.removeEventListener("mousedown", closeOnOutsidePointer);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [workspaceMenuOpen]);

  return (
    <nav
      aria-label="Product navigation"
      className="flex h-[49px] shrink-0 items-center gap-1 border-b border-black/[0.08] bg-white px-4 py-2 text-sm dark:border-white/10 dark:bg-[#111113] lg:hidden"
    >
      <Link
        href="/artifacts"
        aria-current={artifactsActive ? "page" : undefined}
        className={cn(
          "rounded-[8px] px-3 py-1.5 font-medium transition",
          artifactsActive
            ? "bg-[#eef0f3] text-black dark:bg-white/12 dark:text-white"
            : "text-black/55 hover:bg-black/[0.04] dark:text-white/55 dark:hover:bg-white/10",
        )}
      >
        Artifacts
      </Link>

      <div ref={workspaceMenuRef} className="relative ml-auto">
        <button
          type="button"
          onClick={() => setWorkspaceMenuOpen((open) => !open)}
          aria-expanded={workspaceMenuOpen}
          aria-haspopup="menu"
          className={cn(
            "flex items-center gap-1 rounded-[8px] px-3 py-1.5 font-medium transition",
            integrationsActive || workspaceMenuOpen
              ? "bg-[#eef0f3] text-black dark:bg-white/12 dark:text-white"
              : "text-black/55 hover:bg-black/[0.04] dark:text-white/55 dark:hover:bg-white/10",
          )}
        >
          Workspace
          <ChevronDown className={cn("size-3.5 transition", workspaceMenuOpen && "rotate-180")} />
        </button>

        {workspaceMenuOpen && (
          <div
            role="menu"
            aria-label="Workspace menu"
            className="absolute right-0 top-10 z-50 w-44 rounded-[10px] border border-black/[0.08] bg-white p-1 text-[13px] text-black/76 shadow-[0_10px_28px_rgb(15_23_42_/_0.08)] dark:border-white/10 dark:bg-[#292a2f] dark:text-white/80"
          >
            <Link
              href="/integrations"
              role="menuitem"
              aria-current={integrationsActive ? "page" : undefined}
              onClick={() => setWorkspaceMenuOpen(false)}
              className={cn(
                "flex h-9 items-center gap-2 rounded-[7px] px-2 font-medium transition hover:bg-black/[0.04] focus-visible:bg-black/[0.04] focus-visible:outline-none dark:hover:bg-white/10 dark:focus-visible:bg-white/10",
                integrationsActive && "bg-black/[0.035] text-black/82 dark:bg-white/[0.07] dark:text-white/88",
              )}
            >
              <HugeiconsIcon
                icon={PlugSocketIcon}
                size={16}
                color="currentColor"
                strokeWidth={1.5}
                aria-hidden="true"
                className="shrink-0"
              />
              Integrations
            </Link>
          </div>
        )}
      </div>
    </nav>
  );
}
