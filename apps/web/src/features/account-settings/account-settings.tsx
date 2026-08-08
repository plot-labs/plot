"use client";

import { useEffect, useState } from "react";

type AccountProfile = {
  user: { displayName: string; email: string };
};

export function AccountSettings() {
  const [account, setAccount] = useState<AccountProfile | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/plot/me", { cache: "no-store", headers: { Accept: "application/json" } })
      .then((response) => response.ok ? response.json() as Promise<AccountProfile> : Promise.reject(new Error("account unavailable")))
      .then((value) => {
        if (!cancelled) setAccount(value);
      })
      .catch(() => {
        if (!cancelled) setError("Account details could not be loaded.");
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => { cancelled = true; };
  }, []);

  const mark = (account?.user.displayName || account?.user.email || "A").slice(0, 1).toUpperCase();

  return (
    <div className="h-full overflow-y-auto bg-[#f4f6f8] px-5 py-8 dark:bg-[#101112] sm:px-8 sm:py-10 lg:px-10">
      <div className="mx-auto max-w-[760px] pb-16">
        <header className="max-w-[620px]">
          <h1 className="font-serif text-[32px] font-normal leading-[1.08] tracking-[-0.025em] text-black/90 dark:text-white/92 sm:text-[36px]">
            Account
          </h1>
          <p className="mt-2 text-[14px] leading-6 text-black/52 dark:text-white/50">
            Manage the account you use to sign in to Plot.
          </p>
        </header>

        <section className="mt-8 overflow-hidden rounded-[14px] border border-black/[0.09] bg-white shadow-[0_1px_2px_rgb(15_23_42_/_0.025)] dark:border-white/10 dark:bg-white/[0.045]" aria-labelledby="account-profile-heading">
          <div className="border-b border-black/[0.07] px-5 py-5 dark:border-white/[0.08] sm:px-6">
            <h2 id="account-profile-heading" className="text-[15px] font-semibold text-black/82 dark:text-white/86">Profile</h2>
            <p className="mt-1 text-[13px] leading-5 text-black/48 dark:text-white/48">Your sign-in identity and contact details.</p>
          </div>
          <div className="px-5 py-6 sm:px-6">
            {isLoading && <p role="status" className="text-sm text-black/45 dark:text-white/45">Loading account details…</p>}
            {error && <p role="alert" className="text-sm text-red-700 dark:text-red-300">{error}</p>}
            {!isLoading && !error && account && (
              <div className="flex items-center gap-4">
                <div className="flex size-12 shrink-0 items-center justify-center rounded-full border border-black/10 bg-white text-sm font-semibold text-black/65 dark:border-white/10 dark:bg-white/10 dark:text-white/75">
                  {mark}
                </div>
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-black/80 dark:text-white/84">{account.user.displayName}</p>
                  <p className="mt-1 truncate text-sm text-black/48 dark:text-white/48">{account.user.email}</p>
                </div>
              </div>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
