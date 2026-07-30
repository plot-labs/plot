"use client";

import Image from "next/image";
import Link from "next/link";
import { useState } from "react";
import { LoaderCircle, LockKeyhole } from "lucide-react";

import { AnimatedDitherArtwork } from "@/components/auth/animated-dither-artwork";
import { authClient } from "@/lib/auth-client";

function GitHubMark({ className = "" }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 16 16"
      fill="currentColor"
      className={className}
      aria-hidden="true"
    >
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.6-.18-3.28-.8-3.28-3.56 0-.88.31-1.6.82-2.17-.08-.2-.36-1.03.08-2.14 0 0 .67-.21 2.2.82A7.65 7.65 0 0 1 8 4.69c.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.11.16 1.94.08 2.14.51.57.82 1.29.82 2.17 0 2.77-1.69 3.38-3.29 3.56.29.25.54.74.54 1.5 0 1.08-.01 1.95-.01 2.22 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
    </svg>
  );
}

export default function SignInPage() {
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function signInWithGitHub() {
    setLoading(true);
    setError(null);
    try {
      const result = await authClient.signIn.social({
        provider: "github",
        callbackURL: "/auth/complete",
      });
      if (result.error) setError("Access denied");
    } catch {
      setError("GitHub sign-in could not start. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="grid min-h-dvh bg-white p-4 text-[#111111] lg:grid-cols-2">
      <AnimatedDitherArtwork className="min-h-[36dvh] rounded-[13px] border border-black/10 lg:min-h-[calc(100dvh-2rem)]" />

      <section className="relative flex min-h-[64dvh] flex-col px-6 pb-6 pt-24 lg:min-h-[calc(100dvh-2rem)] lg:px-12">
        <div className="absolute right-4 top-4 flex items-center gap-2 lg:right-8 lg:top-2">
          <Image
            src="/plot-logo.png"
            alt=""
            width={24}
            height={24}
            className="size-6 object-contain"
          />
          <span className="font-display text-[27px] leading-none">Plot</span>
        </div>

        <div className="flex flex-1 items-center justify-center">
          <div className="w-full max-w-[420px]">
            <h1 className="font-display text-[42px] leading-[1.08] tracking-[-0.02em] sm:text-[48px]">
              Sign in to Plot
            </h1>
            <p className="mt-3 text-[15px] leading-6 text-black/55">
              Use your approved GitHub account to continue.
            </p>

            <button
              type="button"
              onClick={signInWithGitHub}
              disabled={loading}
              className="mt-8 flex h-14 w-full items-center justify-center gap-2.5 rounded-full border border-[#e2e2e2] bg-[#fafafa] text-[17px] font-semibold text-[#191919] shadow-[0_3px_6px_rgba(0,0,0,0.08)] transition-[background-color,border-color,box-shadow,transform] hover:-translate-y-0.5 hover:border-[#d7d7d7] hover:bg-white hover:shadow-[0_5px_10px_rgba(0,0,0,0.1)] focus-visible:outline-2 focus-visible:outline-offset-3 focus-visible:outline-[#191919] disabled:translate-y-0 disabled:cursor-wait disabled:opacity-55"
            >
              {loading ? (
                <LoaderCircle className="size-6 animate-spin" aria-hidden="true" />
              ) : (
                <GitHubMark className="size-6" />
              )}
              {loading ? "Connecting…" : "GitHub"}
            </button>

            <div className="mt-3 flex items-center justify-center gap-2 text-xs text-black/50">
              <LockKeyhole className="size-3.5" aria-hidden="true" />
              <span>Access is limited to approved accounts.</span>
            </div>

            {error && (
              <p role="alert" className="mt-4 text-center text-sm text-red-600">
                {error}
              </p>
            )}
          </div>
        </div>

        <footer className="flex shrink-0 items-center justify-center gap-5 pt-8 text-xs text-black/45">
          <Link href="/privacy" className="transition-colors hover:text-black">
            Privacy
          </Link>
          <Link href="/terms" className="transition-colors hover:text-black">
            Terms
          </Link>
        </footer>
      </section>
    </main>
  );
}
