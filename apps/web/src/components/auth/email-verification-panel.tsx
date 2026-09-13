"use client";

import { CircleAlert, LoaderCircle } from "lucide-react";
import { useState, type FormEvent } from "react";

type EmailVerificationPanelProps = {
  email: string | null;
};

type VerificationResponse = {
  error?: string;
  message?: string;
  status?: string;
};

export function EmailVerificationPanel({ email }: EmailVerificationPanelProps) {
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState<"verify" | "resend" | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function verify(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const normalizedCode = code.trim();
    if (!/^\d{6}$/.test(normalizedCode)) {
      setError("Enter the six-digit code from your email.");
      setMessage(null);
      return;
    }

    setBusy("verify");
    setError(null);
    setMessage(null);
    try {
      const response = await fetch("/api/auth/verify-email", {
        method: "POST",
        credentials: "include",
        headers: { Accept: "application/json", "Content-Type": "application/json" },
        body: JSON.stringify({ code: normalizedCode }),
      });
      const payload = await response.json().catch(() => null) as VerificationResponse | null;
      if (!response.ok) {
        setError(payload?.message ?? "Verification code is invalid or expired.");
        return;
      }
      if (payload?.status === "authenticated") {
        window.location.assign("/auth/complete");
        return;
      }
      setError("Verification could not be completed. Please try again.");
    } catch {
      setError("Unable to reach Plot. Please try again.");
    } finally {
      setBusy(null);
    }
  }

  async function resend() {
    if (busy) return;
    setBusy("resend");
    setError(null);
    setMessage(null);
    try {
      const response = await fetch("/api/auth/verify-email/resend", {
        method: "POST",
        credentials: "include",
        headers: { Accept: "application/json" },
      });
      const payload = await response.json().catch(() => null) as VerificationResponse | null;
      if (!response.ok) {
        setError(payload?.message ?? "The verification email could not be sent.");
        return;
      }
      setMessage("A new verification code is on its way.");
    } catch {
      setError("Unable to reach Plot. Please try again.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <div>
      <p className="text-center text-sm leading-6 text-black/55">
        We sent a six-digit code to <span className="font-medium text-black/75">{email ?? "your email"}</span>.
      </p>
      <form onSubmit={verify} className="mt-6 grid gap-3" noValidate aria-busy={busy === "verify"}>
        <label htmlFor="verification-code" className="grid gap-1.5">
          <span className="text-sm font-medium leading-none text-black/75">Verification code</span>
          <input
            id="verification-code"
            name="code"
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            value={code}
            onChange={(event) => {
              setCode(event.target.value.replace(/\D/g, "").slice(0, 6));
              setError(null);
            }}
            maxLength={6}
            placeholder="123456"
            disabled={Boolean(busy)}
            required
            aria-describedby="verification-message"
            aria-invalid={Boolean(error)}
            className="h-11 w-full rounded-xl border border-black/12 bg-white px-4 text-center text-lg tracking-[0.28em] text-black outline-none transition-colors placeholder:text-black/25 focus:border-black/30 focus:ring-2 focus:ring-black/[0.06] aria-invalid:border-red-500/40 aria-invalid:ring-2 aria-invalid:ring-red-500/10 disabled:pointer-events-none disabled:opacity-50"
          />
        </label>

        <div id="verification-message" aria-live="polite">
          {error ? (
            <div role="alert" className="flex items-start gap-2.5 rounded-xl border border-red-500/20 bg-red-500/8 px-3.5 py-2.5 text-sm text-red-700">
              <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
              <p>{error}</p>
            </div>
          ) : null}
          {message ? <p className="text-sm text-emerald-700">{message}</p> : null}
        </div>

        <button
          type="submit"
          disabled={Boolean(busy)}
          className="flex h-11 w-full items-center justify-center gap-2 rounded-full bg-[#191919] px-6 text-base font-medium tracking-[-0.015em] text-white shadow-[0_2px_5px_rgba(0,0,0,0.1)] transition-[background-color,box-shadow,transform] hover:-translate-y-0.5 hover:bg-black hover:shadow-[0_5px_10px_rgba(0,0,0,0.16)] focus-visible:outline-2 focus-visible:outline-offset-3 focus-visible:outline-[#191919] disabled:pointer-events-none disabled:opacity-50"
        >
          {busy === "verify" ? <LoaderCircle className="size-4 animate-spin" aria-hidden="true" /> : null}
          {busy === "verify" ? "Verifying…" : "Verify email"}
        </button>
      </form>

      <button
        type="button"
        onClick={() => void resend()}
        disabled={Boolean(busy)}
        className="mt-4 flex h-10 w-full items-center justify-center rounded-full border border-black/12 bg-white px-4 text-sm font-medium text-black/65 transition-colors hover:border-black/20 hover:text-black focus-visible:outline-2 focus-visible:outline-offset-3 focus-visible:outline-[#191919] disabled:pointer-events-none disabled:opacity-50"
      >
        {busy === "resend" ? "Sending…" : "Resend code"}
      </button>
    </div>
  );
}
