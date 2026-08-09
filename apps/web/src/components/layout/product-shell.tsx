"use client";

import { Settings02Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import type { ReactNode } from "react";
import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";

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
        <ProductSidebar
          collapsed={!sidebarOpen}
          theme={theme}
          onThemeChange={setTheme}
          onToggleSidebar={() => setSidebarOpen((open) => !open)}
        />

        <div
          className={cn(
            "relative flex min-w-0 flex-1 flex-col overflow-hidden bg-[#eef0f3] dark:bg-[#111113]",
            !sidebarOpen && "shell-sidebar-closed",
          )}
        >
          <MobileProductNavigation pathname={pathname} />
          <main className="min-h-0 w-full flex-1 overflow-y-auto lg:overflow-hidden">{children}</main>
        </div>
      </div>
    </div>
  );
}

function MobileProductNavigation({ pathname }: { pathname: string }) {
  const settingsActive = pathname === "/settings" || pathname.startsWith("/settings/");

  return (
    <nav
      aria-label="Product navigation"
      className="flex h-[49px] shrink-0 items-center gap-1 border-b border-black/[0.08] bg-white px-4 py-2 text-sm dark:border-white/10 dark:bg-[#111113] lg:hidden"
    >
      {[
        { href: "/chat", label: "Chat" },
        { href: "/routines", label: "Routines" },
        { href: "/artifacts", label: "Artifacts" },
      ].map(({ href, label }) => {
        const active = pathname === href || pathname.startsWith(`${href}/`);
        return (
          <Link
            key={href}
            href={href}
            aria-current={active ? "page" : undefined}
            className={cn(
              "rounded-[8px] px-3 py-1.5 font-medium transition",
              active
                ? "bg-[#eef0f3] text-black dark:bg-white/12 dark:text-white"
                : "text-black/55 hover:bg-black/[0.04] dark:text-white/55 dark:hover:bg-white/10",
            )}
          >
            {label}
          </Link>
        );
      })}

      <Link
        href="/settings/integrations"
        aria-label="Workspace settings"
        title="Workspace settings"
        aria-current={settingsActive ? "page" : undefined}
        className={cn(
          "ml-auto inline-flex size-8 items-center justify-center rounded-[8px] transition",
          settingsActive
            ? "bg-[#eef0f3] text-black dark:bg-white/12 dark:text-white"
            : "text-black/55 hover:bg-black/[0.04] dark:text-white/55 dark:hover:bg-white/10",
        )}
      >
        <HugeiconsIcon
          icon={Settings02Icon}
          size={16}
          color="currentColor"
          strokeWidth={1.5}
          aria-hidden="true"
        />
      </Link>
    </nav>
  );
}
