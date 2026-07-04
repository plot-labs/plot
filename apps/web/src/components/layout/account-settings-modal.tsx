"use client";

import Image from "next/image";
import { MoreHorizontal, Plus, ShieldCheck, UserRound, X } from "lucide-react";
import { useEffect } from "react";

type AccountSettingsModalProps = {
  open: boolean;
  onClose: () => void;
};

export function AccountSettingsModal({ open, onClose }: AccountSettingsModalProps) {
  useEffect(() => {
    if (!open) {
      return;
    }

    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onClose();
      }
    }

    document.addEventListener("keydown", closeOnEscape);

    return () => {
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [onClose, open]);

  if (!open) {
    return null;
  }

  return (
    <div className="fixed inset-0 z-[80] flex items-center justify-center bg-black/65 px-6 py-8">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="account-settings-title"
        className="relative flex h-[min(760px,calc(100vh-64px))] w-full max-w-[980px] overflow-hidden rounded-[10px] border border-black/15 bg-white text-[#2f3237] shadow-[0_24px_80px_rgb(0_0_0_/_0.32)] dark:border-white/12 dark:bg-[#18191d] dark:text-white/88"
      >
        <aside className="w-[242px] shrink-0 border-r border-black/[0.08] bg-[#f4f5f6] px-6 py-7 dark:border-white/10 dark:bg-[#202126]">
          <h2 id="account-settings-title" className="text-[26px] font-semibold leading-none tracking-normal">
            Account
          </h2>
          <p className="mt-3 text-[13px] text-black/50 dark:text-white/48">Manage your account info.</p>

          <nav className="mt-7 space-y-2">
            <button
              type="button"
              className="flex w-full items-center gap-3 rounded-[8px] bg-black/[0.08] px-3 py-2 text-left text-[13px] font-medium dark:bg-white/12"
            >
              <UserRound className="size-4 text-black/60 dark:text-white/65" />
              Profile
            </button>
            <button
              type="button"
              className="flex w-full items-center gap-3 rounded-[8px] px-3 py-2 text-left text-[13px] font-medium text-black/48 transition hover:bg-black/[0.05] dark:text-white/48 dark:hover:bg-white/10"
            >
              <ShieldCheck className="size-4" />
              Security
            </button>
          </nav>
        </aside>

        <section className="min-w-0 flex-1 bg-white px-8 py-8 dark:bg-[#18191d]">
          <button
            type="button"
            onClick={onClose}
            aria-label="Close account settings"
            className="absolute right-3 top-3 inline-flex size-11 items-center justify-center rounded-[9px] border border-black/20 bg-white text-black/55 transition hover:bg-black/[0.04] hover:text-black/80 dark:border-white/18 dark:bg-[#18191d] dark:text-white/55 dark:hover:bg-white/10 dark:hover:text-white"
          >
            <X className="size-5" />
          </button>

          <div className="mt-1 border-t border-black/[0.08] dark:border-white/10">
            <div className="grid grid-cols-[190px_minmax(0,1fr)_150px] items-center gap-6 border-b border-black/[0.08] py-6 text-[13px] dark:border-white/10">
              <div className="font-medium text-black/72 dark:text-white/74">Profile</div>
              <div className="flex items-center gap-5">
                <div className="flex size-12 items-center justify-center rounded-full border border-black/10 bg-white dark:border-white/12 dark:bg-white/8">
                  <Image src="/plot-icon.svg" alt="" width={28} height={28} className="size-7 dark:invert" />
                </div>
                <div className="font-semibold">Seung-u Byeon</div>
              </div>
              <button type="button" className="justify-self-start text-[13px] font-semibold text-black/70 dark:text-white/72">
                Update profile
              </button>
            </div>

            <div className="grid grid-cols-[190px_minmax(0,1fr)_32px] items-start gap-6 border-b border-black/[0.08] py-6 text-[13px] dark:border-white/10">
              <div className="font-medium text-black/72 dark:text-white/74">Email addresses</div>
              <div className="space-y-5">
                <div className="flex items-center gap-2">
                  <span>qusseun@gmail.com</span>
                  <span className="rounded-[6px] bg-black/[0.06] px-2 py-1 text-[11px] font-medium text-black/50 dark:bg-white/10 dark:text-white/50">
                    Primary
                  </span>
                </div>
                <button
                  type="button"
                  className="flex items-center gap-3 text-[13px] font-medium text-black/70 transition hover:text-black dark:text-white/70 dark:hover:text-white"
                >
                  <Plus className="size-4" />
                  Add email address
                </button>
              </div>
              <button
                type="button"
                aria-label="Email address actions"
                className="mt-0.5 inline-flex size-7 items-center justify-center rounded-[7px] text-black/35 transition hover:bg-black/[0.05] hover:text-black/65 dark:text-white/35 dark:hover:bg-white/10 dark:hover:text-white/70"
              >
                <MoreHorizontal className="size-4" />
              </button>
            </div>

            <div className="grid grid-cols-[190px_minmax(0,1fr)_32px] items-start gap-6 py-6 text-[13px]">
              <div className="font-medium text-black/72 dark:text-white/74">Connected accounts</div>
              <div className="space-y-5">
                <div className="flex items-center gap-3">
                  <span className="flex size-5 items-center justify-center text-[17px] font-semibold text-[#4285f4]">
                    G
                  </span>
                  <span>Google - qusseun@gmail.com</span>
                </div>
                <button
                  type="button"
                  className="flex items-center gap-3 text-[13px] font-medium text-black/70 transition hover:text-black dark:text-white/70 dark:hover:text-white"
                >
                  <Plus className="size-4" />
                  Connect account
                </button>
              </div>
              <button
                type="button"
                aria-label="Connected account actions"
                className="mt-0.5 inline-flex size-7 items-center justify-center rounded-[7px] text-black/35 transition hover:bg-black/[0.05] hover:text-black/65 dark:text-white/35 dark:hover:bg-white/10 dark:hover:text-white/70"
              >
                <MoreHorizontal className="size-4" />
              </button>
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}
