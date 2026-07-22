"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

type BootstrapAccountResponse = {
  workspaceId: string;
};

export default function AuthCompletePage() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/plot/account/bootstrap", { method: "POST", credentials: "include", headers: { Accept: "application/json" } })
      .then(async (response) => {
        if (!response.ok) throw new Error(response.status === 409 ? "ACCOUNT_LINK_REQUIRED" : "ACCESS_DENIED");
        const account = await response.json() as BootstrapAccountResponse;
        if (!account.workspaceId) throw new Error("ACCESS_DENIED");
        window.localStorage.setItem("plot.workspaceId", account.workspaceId);
        if (!cancelled) router.replace("/sessions");
      })
      .catch(() => {
        if (!cancelled) setError("Access denied");
      });
    return () => { cancelled = true; };
  }, [router]);

  return (
    <main className="flex min-h-screen items-center justify-center bg-[#eef0f3] px-6">
      <div className="rounded-2xl bg-white px-8 py-7 text-center shadow-lg">
        {error ? <p role="alert" className="text-sm text-red-600">{error}</p> : <p className="text-sm text-black/60">Finishing sign-in…</p>}
      </div>
    </main>
  );
}
