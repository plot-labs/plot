"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense } from "react";

import { AuthShell } from "@/components/auth/auth-shell";
import { EmailVerificationPanel } from "@/components/auth/email-verification-panel";

export default function VerifyEmailPage() {
  return (
    <Suspense fallback={<main className="min-h-dvh bg-[#f7f7f4]" />}>
      <VerifyEmailContent />
    </Suspense>
  );
}

function VerifyEmailContent() {
  const searchParams = useSearchParams();
  const email = searchParams.get("email");

  return (
    <AuthShell>
      <div>
        <div className="text-center">
          <p className="font-sans text-[10px] uppercase tracking-[0.16em] text-black/40">One more step</p>
          <h1 className="mt-3 font-display text-[42px] leading-[1.02] tracking-[-0.02em] sm:text-5xl">Verify your email</h1>
          <p className="mx-auto mt-4 max-w-sm text-sm leading-6 text-black/55">
            Confirm your email before you enter your Plot workspace.
          </p>
        </div>

        <div className="mt-8">
          <EmailVerificationPanel email={email} />
          <p className="mt-6 text-center text-sm text-black/50">
            <Link className="font-medium text-black/70 underline underline-offset-4 hover:text-black" href="/sign-in">
              Back to sign in
            </Link>
          </p>
        </div>
      </div>
    </AuthShell>
  );
}
