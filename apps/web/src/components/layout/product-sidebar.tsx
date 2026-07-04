"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  FileText,
  FolderOpen,
  MessageSquareText,
  Mic2,
  PackageOpen,
  Plus,
  Search,
  Settings,
} from "lucide-react";

import { getProductShellData } from "@/lib/api-client";
import { cn } from "@/lib/utils";

const navItems = [
  { href: "/sessions", label: "Sessions", icon: MessageSquareText },
  { href: "/sources", label: "Sources", icon: FolderOpen },
  { href: "/packs", label: "Packs", icon: PackageOpen },
  { href: "/voice", label: "Voice", icon: Mic2 },
  { href: "/settings", label: "Settings", icon: Settings },
];

export function ProductSidebar() {
  const pathname = usePathname();
  const { sessions } = getProductShellData();

  return (
    <aside className="flex h-full w-[280px] shrink-0 flex-col border-r border-black/10 bg-[#ede8df] text-[#1c1a17] dark:border-white/10 dark:bg-[#2b2b2d] dark:text-[#f4f1ea]">
      <div className="flex items-center gap-2 border-b border-black/10 px-4 py-4 dark:border-white/10">
        <div className="flex size-8 items-center justify-center rounded-md bg-[#1c1a17] text-sm font-semibold text-[#f8f5ef] dark:bg-[#f4f1ea] dark:text-[#19191a]">
          P
        </div>
        <div>
          <div className="text-sm font-semibold">Plot</div>
          <div className="text-xs text-black/50 dark:text-white/50">Dev workspace</div>
        </div>
      </div>

      <div className="space-y-1 px-3 py-3">
        <button className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm text-black/70 transition hover:bg-black/5 dark:text-white/70 dark:hover:bg-white/10">
          <Plus className="size-4" />
          New session
        </button>
        <button className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm text-black/70 transition hover:bg-black/5 dark:text-white/70 dark:hover:bg-white/10">
          <Search className="size-4" />
          Search
        </button>
      </div>

      <nav className="space-y-1 px-3 pb-3">
        {navItems.map((item) => {
          const Icon = item.icon;
          const active = pathname === item.href || pathname.startsWith(`${item.href}/`);

          return (
            <Link
              key={item.href}
              href={item.href}
              aria-current={active ? "page" : undefined}
              className={cn(
                "flex items-center gap-2 rounded-md px-3 py-2 text-sm transition",
                active
                  ? "bg-white text-[#171511] shadow-sm dark:bg-white/10 dark:text-white"
                  : "text-black/65 hover:bg-black/5 dark:text-white/65 dark:hover:bg-white/10",
              )}
            >
              <Icon className="size-4" />
              {item.label}
            </Link>
          );
        })}
      </nav>

      <div className="px-3 pt-2 text-[11px] font-medium uppercase text-black/40 dark:text-white/40">
        Recent sessions
      </div>

      <div className="min-h-0 flex-1 space-y-1 overflow-y-auto px-3 py-2">
        {sessions.map((session) => (
          <Link
            key={session.id}
            href="/sessions"
            className="group block rounded-md px-3 py-2 text-sm transition hover:bg-black/5 dark:hover:bg-white/10"
          >
            <div className="flex items-center gap-2">
              <FileText className="size-3.5 text-black/45 dark:text-white/45" />
              <span className="truncate text-black/75 dark:text-white/75">{session.title}</span>
              <span className="ml-auto text-xs text-black/40 dark:text-white/40">{session.updatedAt}</span>
            </div>
            <div className="mt-1 truncate pl-5 text-xs text-black/45 dark:text-white/45">
              {session.subtitle}
            </div>
          </Link>
        ))}
      </div>
    </aside>
  );
}
