"use client";

import { GitBranch, ShieldCheck, UserRound, X } from "lucide-react";
import { useEffect, useState } from "react";

import { cn } from "@/lib/utils";

type AccountSettingsModalProps = {
  open: boolean;
  onClose: () => void;
  user?: { displayName: string; email: string };
};

type AccountTab = "profile" | "security";

export function AccountSettingsModal({ open, onClose, user }: AccountSettingsModalProps) {
  const [activeTab, setActiveTab] = useState<AccountTab>("profile");

  useEffect(() => {
    if (!open) return;
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [onClose, open]);

  if (!open) return null;
  const name = user?.displayName ?? "Plot user";
  const email = user?.email ?? "";

  return (
    <div className="fixed inset-0 z-[80] flex items-center justify-center bg-black/65 px-6 py-8">
      <div role="dialog" aria-modal="true" aria-labelledby="account-settings-title" className="relative flex h-[min(620px,calc(100vh-64px))] w-full max-w-[820px] overflow-hidden rounded-[10px] border border-black/15 bg-white text-[#2f3237] shadow-[0_24px_80px_rgb(0_0_0_/_0.32)] dark:border-white/12 dark:bg-[#18191d] dark:text-white/88">
        <aside className="w-[220px] shrink-0 border-r border-black/[0.08] bg-[#f4f5f6] px-6 py-7 dark:border-white/10 dark:bg-[#202126]">
          <h2 id="account-settings-title" className="text-[26px] font-semibold leading-none">Account</h2>
          <p className="mt-3 text-[13px] text-black/50 dark:text-white/48">Manage your Plot account.</p>
          <nav className="mt-7 space-y-2">
            <TabButton active={activeTab === "profile"} onClick={() => setActiveTab("profile")} icon={<UserRound className="size-4" />}>Profile</TabButton>
            <TabButton active={activeTab === "security"} onClick={() => setActiveTab("security")} icon={<ShieldCheck className="size-4" />}>Security</TabButton>
          </nav>
        </aside>
        <section className="min-w-0 flex-1 bg-white px-8 py-8 dark:bg-[#18191d]">
          <button type="button" onClick={onClose} aria-label="Close account settings" className="absolute right-5 top-5 inline-flex size-8 items-center justify-center rounded-[8px] text-black/48 hover:bg-black/[0.05] dark:text-white/48 dark:hover:bg-white/10"><X className="size-5" /></button>
          {activeTab === "profile" ? (
            <div className="mt-8 border-t border-black/[0.08] dark:border-white/10">
              <div className="grid grid-cols-[160px_minmax(0,1fr)] items-center gap-6 border-b border-black/[0.08] py-6 text-[13px] dark:border-white/10">
                <div className="font-medium text-black/72 dark:text-white/74">Profile</div>
                <div><div className="font-semibold">{name}</div><div className="mt-1 text-black/50 dark:text-white/50">{email}</div></div>
              </div>
              <div className="grid grid-cols-[160px_minmax(0,1fr)] items-center gap-6 py-6 text-[13px]">
                <div className="font-medium text-black/72 dark:text-white/74">Connected account</div>
                <div className="flex items-center gap-3"><GitBranch className="size-5" /><span>GitHub</span></div>
              </div>
            </div>
          ) : (
            <div className="mt-8 border-t border-black/[0.08] dark:border-white/10">
              <div className="grid grid-cols-[160px_minmax(0,1fr)] items-start gap-6 border-b border-black/[0.08] py-6 text-[13px] dark:border-white/10">
                <div className="font-medium text-black/72 dark:text-white/74">Sessions</div>
                <p className="text-black/55 dark:text-white/55">Your browser sessions are protected by HttpOnly cookies and managed by Better Auth.</p>
              </div>
              <div className="grid grid-cols-[160px_minmax(0,1fr)] items-center gap-6 py-6 text-[13px]">
                <div className="font-medium text-black/72 dark:text-white/74">Sign out</div>
                <button type="button" onClick={async () => { await fetch("/api/auth/sign-out", { method: "POST", credentials: "include" }); window.location.assign("/sign-in"); }} className="justify-self-start font-semibold text-red-500">Sign out of this account</button>
              </div>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

function TabButton({ active, onClick, icon, children }: { active: boolean; onClick: () => void; icon: React.ReactNode; children: React.ReactNode }) {
  return <button type="button" onClick={onClick} className={cn("flex w-full items-center gap-3 rounded-[8px] px-3 py-2 text-left text-[13px] font-medium", active ? "bg-black/[0.08] text-black/82 dark:bg-white/12 dark:text-white/86" : "text-black/48 hover:bg-black/[0.05] dark:text-white/48 dark:hover:bg-white/10")}>{icon}{children}</button>;
}
