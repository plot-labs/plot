"use client";

import Image from "next/image";
import { MoreHorizontal, Plus, ShieldCheck, UserRound, X } from "lucide-react";
import { useEffect, useState } from "react";

import { cn } from "@/lib/utils";

type AccountSettingsModalProps = {
  open: boolean;
  onClose: () => void;
};

type AccountTab = "profile" | "security";

export function AccountSettingsModal({ open, onClose }: AccountSettingsModalProps) {
  const [activeTab, setActiveTab] = useState<AccountTab>("profile");

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
              onClick={() => setActiveTab("profile")}
              className={cn(
                "flex w-full items-center gap-3 rounded-[8px] px-3 py-2 text-left text-[13px] font-medium transition",
                activeTab === "profile"
                  ? "bg-black/[0.08] text-black/82 dark:bg-white/12 dark:text-white/86"
                  : "text-black/48 hover:bg-black/[0.05] dark:text-white/48 dark:hover:bg-white/10",
              )}
            >
              <UserRound className="size-4" />
              Profile
            </button>
            <button
              type="button"
              onClick={() => setActiveTab("security")}
              className={cn(
                "flex w-full items-center gap-3 rounded-[8px] px-3 py-2 text-left text-[13px] font-medium transition",
                activeTab === "security"
                  ? "bg-black/[0.08] text-black/82 dark:bg-white/12 dark:text-white/86"
                  : "text-black/48 hover:bg-black/[0.05] dark:text-white/48 dark:hover:bg-white/10",
              )}
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
            className="absolute right-5 top-5 inline-flex size-8 items-center justify-center rounded-[8px] text-black/48 transition hover:bg-black/[0.05] hover:text-black/80 dark:text-white/48 dark:hover:bg-white/10 dark:hover:text-white"
          >
            <X className="size-5" />
          </button>

          {activeTab === "profile" ? (
            <div className="mt-1 border-t border-black/[0.08] dark:border-white/10">
              <div className="grid grid-cols-[190px_minmax(0,1fr)_150px] items-center gap-6 border-b border-black/[0.08] py-6 text-[13px] dark:border-white/10">
                <div className="font-medium text-black/72 dark:text-white/74">Profile</div>
                <div className="flex items-center gap-5">
                  <div className="flex size-12 items-center justify-center rounded-full border border-black/10 bg-white dark:border-white/12 dark:bg-white/8">
                    <Image src="/plot-icon.svg" alt="" width={28} height={28} className="size-7 dark:invert" />
                  </div>
                  <div className="font-semibold">Seung-u Byeon</div>
                </div>
                <button
                  type="button"
                  className="justify-self-start text-[13px] font-semibold text-black/70 dark:text-white/72"
                >
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
          ) : (
            <div className="mt-1 border-t border-black/[0.08] dark:border-white/10">
              <div className="grid grid-cols-[190px_minmax(0,1fr)] items-center gap-6 border-b border-black/[0.08] py-6 text-[13px] dark:border-white/10">
                <div className="font-medium text-black/72 dark:text-white/74">Password</div>
                <button
                  type="button"
                  className="justify-self-start text-[13px] font-semibold text-black/70 transition hover:text-black dark:text-white/72 dark:hover:text-white"
                >
                  Set password
                </button>
              </div>

              <div className="grid grid-cols-[190px_minmax(0,1fr)] items-start gap-6 border-b border-black/[0.08] py-6 text-[13px] dark:border-white/10">
                <div className="font-medium text-black/72 dark:text-white/74">Active devices</div>
                <div className="space-y-6">
                  <DeviceSession
                    device="Macintosh"
                    browser="Chrome 149.0.0"
                    location="2001:e60:1218:88a0:21e4:fccf:7c0d:9e61 (Seoul, South Korea)"
                    time="Today at 11:45 PM"
                    current
                  />
                  <DeviceSession
                    device="Macintosh"
                    browser="Chrome 149.0.0"
                    location="59.25.130.176 (Daegu, South Korea)"
                    time="Last Tuesday at 11:47 AM"
                    showActions
                  />
                </div>
              </div>

              <div className="grid grid-cols-[190px_minmax(0,1fr)] items-center gap-6 py-6 text-[13px]">
                <div className="font-medium text-black/72 dark:text-white/74">Delete account</div>
                <button
                  type="button"
                  className="justify-self-start text-[13px] font-semibold text-red-500 transition hover:text-red-600 dark:text-red-300 dark:hover:text-red-200"
                >
                  Delete account
                </button>
              </div>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

type DeviceSessionProps = {
  device: string;
  browser: string;
  location: string;
  time: string;
  current?: boolean;
  showActions?: boolean;
};

function DeviceSession({ device, browser, location, time, current = false, showActions = false }: DeviceSessionProps) {
  return (
    <div className="grid grid-cols-[34px_minmax(0,1fr)_32px] gap-4">
      <div className="pt-0.5">
        <div className="flex size-7 items-center justify-center">
          <div className="h-4 w-6 rounded-[2px] bg-black shadow-[inset_0_0_0_1px_rgb(255_255_255_/_0.35)] dark:bg-white" />
        </div>
      </div>
      <div className="min-w-0 space-y-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-semibold text-black/78 dark:text-white/82">{device}</span>
          {current && (
            <span className="rounded-[6px] bg-black/[0.06] px-2 py-1 text-[11px] font-medium text-black/48 dark:bg-white/10 dark:text-white/50">
              This device
            </span>
          )}
        </div>
        <div className="text-black/50 dark:text-white/50">{browser}</div>
        <div className="max-w-[360px] leading-5 text-black/50 dark:text-white/50">{location}</div>
        <div className="text-black/50 dark:text-white/50">{time}</div>
      </div>
      {showActions ? (
        <button
          type="button"
          aria-label={`${device} device actions`}
          className="mt-0.5 inline-flex size-7 items-center justify-center rounded-[7px] text-black/35 transition hover:bg-black/[0.05] hover:text-black/65 dark:text-white/35 dark:hover:bg-white/10 dark:hover:text-white/70"
        >
          <MoreHorizontal className="size-4" />
        </button>
      ) : (
        <div />
      )}
    </div>
  );
}
