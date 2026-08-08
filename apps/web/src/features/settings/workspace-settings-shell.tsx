"use client";

import { PlugSocketIcon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { useEffect, useState } from "react";

import { cn } from "@/lib/utils";

type Account = {
  workspaces: Array<{ id: string; name: string }>;
  defaultWorkspaceId: string;
};

export function WorkspaceSettingsShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const [workspaceName, setWorkspaceName] = useState("Workspace");

  useEffect(() => {
    let cancelled = false;

    fetch("/api/plot/me", { cache: "no-store", headers: { Accept: "application/json" } })
      .then((response) => response.ok ? response.json() as Promise<Account> : null)
      .then((account) => {
        if (cancelled || !account) return;
        const selectedId = window.localStorage.getItem("plot.workspaceId") ?? account.defaultWorkspaceId;
        const selectedWorkspace = account.workspaces.find((workspace) => workspace.id === selectedId)
          ?? account.workspaces[0];
        if (selectedWorkspace) setWorkspaceName(selectedWorkspace.name);
      })
      .catch(() => undefined);

    return () => { cancelled = true; };
  }, []);

  const integrationsActive = pathname === "/settings/integrations"
    || pathname.startsWith("/settings/integrations/");

  return (
    <div className="grid h-full min-h-0 grid-rows-[auto_minmax(0,1fr)] bg-[#f7f8fa] dark:bg-[#111113] lg:grid-cols-[220px_minmax(0,1fr)] lg:grid-rows-1">
      <aside className="border-b border-black/[0.08] bg-[#f3f4f6] px-4 py-4 dark:border-white/10 dark:bg-[#18191d] lg:border-r lg:border-b-0 lg:px-4 lg:py-7">
        <header className="flex items-center gap-3 px-2 lg:block">
          <span className="flex size-8 shrink-0 items-center justify-center rounded-[9px] bg-[#ef3f2c] font-serif text-[15px] font-semibold leading-none text-white lg:mb-3">
            {workspaceName.slice(0, 1).toUpperCase()}
          </span>
          <div className="min-w-0">
            <p className="truncate text-[14px] font-semibold text-black/82 dark:text-white/86">{workspaceName}</p>
            <h1 className="mt-0.5 text-[12px] text-black/42 dark:text-white/42">Workspace settings</h1>
          </div>
        </header>

        <nav aria-label="Workspace settings" className="mt-4 lg:mt-7">
          <Link
            href="/settings/integrations"
            aria-current={integrationsActive ? "page" : undefined}
            className={cn(
              "flex h-9 items-center gap-2 rounded-[8px] px-2.5 text-[13px] font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 dark:focus-visible:ring-white/25",
              integrationsActive
                ? "bg-white text-black/82 shadow-sm shadow-black/[0.03] dark:bg-white/10 dark:text-white/88"
                : "text-black/58 hover:bg-black/[0.04] hover:text-black/78 dark:text-white/58 dark:hover:bg-white/[0.07] dark:hover:text-white/80",
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
        </nav>
      </aside>

      <section className="min-h-0 min-w-0 overflow-hidden" aria-label={`${workspaceName} settings`}>
        {children}
      </section>
    </div>
  );
}
