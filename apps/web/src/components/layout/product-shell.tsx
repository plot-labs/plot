"use client";

import { Moon, PanelRight, Sun } from "lucide-react";
import type { ReactNode } from "react";
import { useState } from "react";

import { ProductSidebar } from "@/components/layout/product-sidebar";

export function ProductShell({ children }: { children: ReactNode }) {
  const [darkMode, setDarkMode] = useState(false);

  return (
    <div className={darkMode ? "dark" : undefined}>
      <div className="flex min-h-screen bg-[#f8f5ef] text-[#171511] dark:bg-[#141414] dark:text-[#f4f1ea]">
        <ProductSidebar />

        <div className="flex min-w-0 flex-1 flex-col">
          <header className="flex h-12 items-center justify-between border-b border-black/10 bg-[#fbfaf6]/90 px-4 backdrop-blur dark:border-white/10 dark:bg-[#181818]/90">
            <div className="text-sm font-medium">Plot workspace</div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setDarkMode((value) => !value)}
                className="inline-flex size-8 items-center justify-center rounded-md text-black/60 transition hover:bg-black/5 dark:text-white/70 dark:hover:bg-white/10"
                aria-label="Toggle light and dark view"
              >
                {darkMode ? <Sun className="size-4" /> : <Moon className="size-4" />}
              </button>
              <div className="inline-flex items-center gap-2 rounded-md border border-black/10 px-2.5 py-1.5 text-xs text-black/55 dark:border-white/10 dark:text-white/55">
                <PanelRight className="size-3.5" />
                Dev
              </div>
            </div>
          </header>

          <main className="min-h-0 w-full flex-1 overflow-hidden">{children}</main>
        </div>
      </div>
    </div>
  );
}
