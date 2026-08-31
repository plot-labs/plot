import Image from "next/image";
import Link from "next/link";
import type { ReactNode } from "react";

import { isSafeHttpUrl } from "@/lib/safe-url";
import { cn } from "@/lib/utils";

type PublicChangelogLayoutProps = {
  workspaceName: string;
  workspaceSlug: string;
  logoUrl: string | null;
  children: ReactNode;
};

export function PublicChangelogLayout({
  workspaceName,
  workspaceSlug,
  logoUrl,
  children,
}: PublicChangelogLayoutProps) {
  const safeLogoUrl = isSafeHttpUrl(logoUrl) ? logoUrl : null;

  return (
    <main className="min-h-dvh bg-[#faf9f6] px-6 py-8 text-[#171512] sm:px-10 lg:px-16">
      <nav className="mx-auto flex max-w-5xl items-center justify-between gap-4">
        <Link href="/" className="flex items-center gap-2" aria-label="Plot home">
          <Image src="/plot-logo.png" alt="" width={24} height={24} className="size-6" />
          <span className="font-display text-2xl leading-none">Plot</span>
        </Link>
        <Link
          href={`/changelog/${workspaceSlug}`}
          className="flex min-w-0 items-center gap-2 text-sm text-black/55 transition-colors hover:text-black"
        >
          {safeLogoUrl ? (
            <Image
              src={safeLogoUrl}
              alt=""
              width={24}
              height={24}
              className="size-6 rounded-full border border-black/10 object-cover"
            />
          ) : (
            <span
              aria-hidden="true"
              className={cn(
                "flex size-6 items-center justify-center rounded-full border border-black/10",
                "bg-white text-[10px] font-semibold uppercase text-black/55",
              )}
            >
              {workspaceName.slice(0, 1)}
            </span>
          )}
          <span className="truncate">{workspaceName}</span>
        </Link>
      </nav>

      <div className="mx-auto max-w-3xl py-16 sm:py-20">{children}</div>

      <footer className="mx-auto flex max-w-3xl flex-wrap gap-x-5 gap-y-2 border-t border-black/10 pt-8 text-sm text-black/50">
        <Link href="/privacy" className="transition-colors hover:text-black">
          Privacy
        </Link>
        <Link href="/terms" className="transition-colors hover:text-black">
          Terms
        </Link>
        <a href="mailto:hello@useplot.xyz" className="transition-colors hover:text-black">
          Contact
        </a>
      </footer>
    </main>
  );
}
