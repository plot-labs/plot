"use client";

import { useEffect, useState } from "react";

import { UserAvatar } from "@/components/user-avatar";

type AccountProfile = {
  user: { id: string; displayName: string; email: string };
};

export function AccountSettings() {
  const [account, setAccount] = useState<AccountProfile | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [confirmingDeletion, setConfirmingDeletion] = useState(false);
  const [confirmation, setConfirmation] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [deletionError, setDeletionError] = useState<string | null>(null);

  async function deleteAccount() {
    if (confirmation !== "DELETE" || deleting) return;
    setDeleting(true);
    setDeletionError(null);
    try {
      const response = await fetch("/api/plot/account", { method: "DELETE", credentials: "include" });
      if (!response.ok) {
        const body: unknown = await response.json().catch(() => null);
        const code = body && typeof body === "object" && "error" in body ? body.error : null;
        if (code === "ACCOUNT_SUBSCRIPTION_ACTIVE") throw new Error("Cancel your workspace subscription and wait until the billing period ends before deleting your account.");
        if (code === "ACCOUNT_WORKSPACE_HAS_MEMBERS") throw new Error("Remove other members from your workspaces before deleting your account.");
        throw new Error("Account deletion could not be completed. Please try again.");
      }
      window.localStorage.removeItem("plot.workspaceId");
      try {
        await fetch("/api/auth/sign-out", { method: "POST", credentials: "include" });
      } finally {
        window.location.assign("/sign-in");
      }
    } catch (failure) {
      setDeletionError(failure instanceof Error ? failure.message : "Account deletion could not be completed. Please try again.");
      setDeleting(false);
    }
  }

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

  return (
    <div className="h-full overflow-y-auto bg-[#f4f6f8] px-5 py-8 dark:bg-[#101112] sm:px-8 sm:py-10 lg:px-10">
      <div className="mx-auto max-w-[760px] pb-16">
        <header className="max-w-[620px]">
          <h1 className="font-display text-[32px] font-normal leading-[1.08] tracking-[-0.025em] text-black/90 dark:text-white/92 sm:text-[36px]">
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
                <UserAvatar userId={account.user.id} size={48} />
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-black/80 dark:text-white/84">{account.user.displayName}</p>
                  <p className="mt-1 truncate text-sm text-black/48 dark:text-white/48">{account.user.email}</p>
                </div>
              </div>
            )}
          </div>
        </section>

        <section className="mt-8 overflow-hidden rounded-[14px] border border-red-200 bg-white dark:border-red-900/60 dark:bg-white/[0.045]" aria-labelledby="delete-account-heading">
          <div className="px-5 py-5 sm:px-6">
            <h2 id="delete-account-heading" className="text-[15px] font-semibold text-red-700 dark:text-red-300">Delete account</h2>
            <p className="mt-2 text-[13px] leading-5 text-black/55 dark:text-white/55">
              Permanently remove your sign-in identity and access to Plot. Your workspaces will be closed, but their content may be retained in historical records. End any active subscription and remove other workspace members first.
            </p>
            {!confirmingDeletion ? (
              <button type="button" onClick={() => setConfirmingDeletion(true)} disabled={isLoading || !!error}
                className="mt-4 rounded-lg border border-red-300 px-3 py-2 text-sm font-medium text-red-700 hover:bg-red-50 disabled:opacity-50 dark:border-red-800 dark:text-red-300 dark:hover:bg-red-950/30">
                Delete account
              </button>
            ) : (
              <div className="mt-4 max-w-sm">
                <label htmlFor="delete-account-confirmation" className="block text-sm text-black/70 dark:text-white/70">Type DELETE to confirm</label>
                <input id="delete-account-confirmation" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} disabled={deleting}
                  autoComplete="off" className="mt-2 w-full rounded-lg border border-black/15 bg-transparent px-3 py-2 text-sm dark:border-white/20" />
                {deletionError && <p role="alert" className="mt-2 text-sm text-red-700 dark:text-red-300">{deletionError}</p>}
                <div className="mt-3 flex gap-2">
                  <button type="button" onClick={deleteAccount} disabled={confirmation !== "DELETE" || deleting}
                    className="rounded-lg bg-red-700 px-3 py-2 text-sm font-medium text-white disabled:opacity-50">
                    {deleting ? "Deleting…" : "Delete my account"}
                  </button>
                  <button type="button" onClick={() => { setConfirmingDeletion(false); setConfirmation(""); setDeletionError(null); }} disabled={deleting}
                    className="rounded-lg px-3 py-2 text-sm text-black/65 dark:text-white/65">Cancel</button>
                </div>
              </div>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
