"use client";

import type { ReactNode } from "react";
import { useEffect, useState } from "react";
import { PanelLeftOpen } from "lucide-react";

import { ProductSidebar } from "@/components/layout/product-sidebar";
import { cn } from "@/lib/utils";

export type ProductTheme = "system" | "light" | "dark";

export function ProductShell({ children }: { children: ReactNode }) {
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
      <div className="flex min-h-screen bg-[#eef0f3] text-[#18181b] dark:bg-[#202126] dark:text-[#f4f4f5]">
        {sidebarOpen && (
          <ProductSidebar
            theme={theme}
            onThemeChange={setTheme}
            onToggleSidebar={() => setSidebarOpen(false)}
          />
        )}

        <div
          className={cn(
            "relative flex min-w-0 flex-1 flex-col overflow-hidden bg-white dark:bg-[#111113]",
            !sidebarOpen && "shell-sidebar-closed",
            sidebarOpen &&
              "lg:rounded-l-[22px] lg:border-l lg:border-black/[0.08] lg:shadow-[-10px_0_24px_rgb(15_23_42_/_0.04)] lg:dark:border-white/10 lg:dark:shadow-black/25",
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
          <main className="min-h-0 w-full flex-1 overflow-hidden">{children}</main>
        </div>
      </div>
    </div>
  );
}
