"use client";

import type { ReactNode } from "react";
import { useEffect, useState } from "react";

import { ProductSidebar } from "@/components/layout/product-sidebar";

export type ProductTheme = "system" | "light" | "dark";

export function ProductShell({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<ProductTheme>("light");
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
        <ProductSidebar theme={theme} onThemeChange={setTheme} />

        <div className="relative flex min-w-0 flex-1 flex-col overflow-hidden bg-white dark:bg-[#111113] lg:rounded-l-[22px] lg:border-l lg:border-black/[0.08] lg:shadow-[-10px_0_24px_rgb(15_23_42_/_0.04)] lg:dark:border-white/10 lg:dark:shadow-black/25">
          <main className="min-h-0 w-full flex-1 overflow-hidden">{children}</main>
        </div>
      </div>
    </div>
  );
}
