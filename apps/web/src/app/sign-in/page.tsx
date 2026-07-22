"use client";

import { useState } from "react";
import { authClient } from "@/lib/auth-client";

export default function SignInPage() {
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function signInWithGitHub() {
    setLoading(true);
    setError(null);
    const result = await authClient.signIn.social({
      provider: "github",
      callbackURL: "/auth/complete",
    });
    if (result.error) {
      setError("Access denied");
      setLoading(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-[#eef0f3] px-6 text-[#18181b]">
      <section className="w-full max-w-md rounded-3xl border border-black/10 bg-white p-10 shadow-xl">
        <p className="text-sm font-semibold uppercase tracking-[0.2em] text-black/45">Plot</p>
        <h1 className="mt-4 text-3xl font-semibold">Sign in to Plot</h1>
        <p className="mt-3 text-sm leading-6 text-black/55">Use your approved GitHub account to continue.</p>
        <button
          type="button"
          onClick={signInWithGitHub}
          disabled={loading}
          className="mt-8 flex h-12 w-full items-center justify-center rounded-xl bg-black text-sm font-semibold text-white transition hover:bg-black/80 disabled:opacity-50"
        >
          {loading ? "Redirecting…" : "Continue with GitHub"}
        </button>
        {error && <p role="alert" className="mt-4 text-sm text-red-600">{error}</p>}
      </section>
    </main>
  );
}
