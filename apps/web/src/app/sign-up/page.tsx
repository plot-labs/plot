"use client";

import { useState } from "react";
import Link from "next/link";
import { LoaderCircle, LockKeyhole } from "lucide-react";

import { AuthShell } from "@/components/auth/auth-shell";
import { GitHubMark } from "@/components/auth/github-mark";
import { PasswordSignUpForm } from "@/components/auth/password-sign-in-form";

export default function SignUpPage() {
  const [loading, setLoading] = useState(false);

  function signUpWithGitHub() {
    setLoading(true);
    window.location.assign("/api/auth/sign-in/github?callbackURL=%2Fauth%2Fcomplete");
  }

  return (
    <AuthShell>
      <div>
        <div className="text-center">
          <p className="font-mono text-[10px] uppercase tracking-[0.16em] text-black/40">
            Get started
          </p>
          <h1 className="mt-3 font-display text-[42px] leading-[1.02] tracking-[-0.02em] sm:text-5xl">
            Create your account
          </h1>
          <p className="mx-auto mt-4 max-w-sm text-sm leading-6 text-black/55">
            Start turning what you ship into what you publish.
          </p>
        </div>

        <div className="mt-8">
          <button
            type="button"
            onClick={signUpWithGitHub}
            disabled={loading}
            className="flex h-11 w-full items-center justify-center gap-2 rounded-full border border-black/12 bg-white text-base font-medium tracking-[-0.015em] shadow-[0_2px_5px_rgba(0,0,0,0.06)] transition-[background-color,border-color,box-shadow,transform] hover:-translate-y-0.5 hover:border-black/20 hover:bg-white hover:shadow-[0_5px_10px_rgba(0,0,0,0.1)] focus-visible:outline-2 focus-visible:outline-offset-3 focus-visible:outline-[#191919] disabled:pointer-events-none disabled:opacity-50"
          >
            {loading ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
            ) : (
              <GitHubMark className="size-4" />
            )}
            {loading ? "Connecting…" : "GitHub"}
          </button>

          <div className="my-5 flex items-center gap-3 text-xs uppercase tracking-[0.12em] text-black/35">
            <span className="h-px flex-1 bg-black/10" />
            <span>Or</span>
            <span className="h-px flex-1 bg-black/10" />
          </div>

          <PasswordSignUpForm />

          <div className="mt-4 flex items-center justify-center gap-2 text-center text-xs text-black/45">
            <LockKeyhole className="size-3.5" aria-hidden="true" />
            <span>Use your approved Plot email to create an account.</span>
          </div>

          <p className="mt-6 text-center text-sm text-black/50">
            Already have an account?{" "}
            <Link className="font-medium text-black/70 underline underline-offset-4 hover:text-black" href="/sign-in">
              Log in
            </Link>
          </p>
        </div>
      </div>
    </AuthShell>
  );
}
