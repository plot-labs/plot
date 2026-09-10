import Image from "next/image";
import Link from "next/link";
import type { ReactNode } from "react";

import { AnimatedDitherArtwork } from "@/components/auth/animated-dither-artwork";

type AuthShellProps = {
  children: ReactNode;
};

export function AuthShell({ children }: AuthShellProps) {
  return (
    <main className="min-h-dvh bg-[#f7f7f4] text-[#171512] lg:grid lg:grid-cols-2">
      <div className="relative hidden min-h-dvh p-8 lg:flex">
        <div className="relative isolate flex-1 overflow-hidden rounded-2xl border border-black/10 bg-[#eee9da]">
          <div className="absolute inset-0">
            <AnimatedDitherArtwork className="h-full w-full" />
          </div>
          <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-[#171512]/90 via-[#171512]/15 to-transparent" />
          <div className="pointer-events-none absolute inset-x-8 bottom-8 z-10 max-w-md text-white">
            <p className="font-sans text-[11px] uppercase tracking-[0.16em] text-white/65">
              Shipped work → customer awareness
            </p>
            <h2 className="mt-4 font-display text-5xl leading-[0.98] tracking-[-0.02em]">
              Make every release land.
            </h2>
            <p className="mt-5 max-w-sm text-sm leading-6 text-white/70">
              Plot turns shipped work into a customer-ready update — with the
              evidence and judgment still in your hands.
            </p>
          </div>
        </div>
      </div>

      <section className="flex min-h-dvh flex-col items-center justify-between bg-[#fafaf8] px-6 py-6 sm:px-10 lg:px-14 lg:py-8">
        <Link
          href="/"
          aria-label="Plot home"
          className="flex w-full max-w-md items-center gap-2 self-start"
        >
          <Image
            src="/plot-logo.png"
            alt=""
            width={28}
            height={28}
            className="size-7 object-contain"
          />
          <span className="font-display text-[27px] leading-none">Plot</span>
        </Link>

        <div className="flex w-full max-w-md flex-1 items-center justify-center py-12">
          <div className="w-full">{children}</div>
        </div>

        <footer className="w-full max-w-md px-2 text-center text-xs leading-5 text-black/45">
          By continuing, you agree to our{" "}
          <Link className="underline underline-offset-4 hover:text-black" href="/terms">
            Terms
          </Link>{" "}
          and{" "}
          <Link className="underline underline-offset-4 hover:text-black" href="/privacy">
            Privacy Policy
          </Link>
          .
        </footer>
      </section>
    </main>
  );
}
