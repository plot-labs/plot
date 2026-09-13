"use client";

import { CircleAlert, LoaderCircle } from "lucide-react";
import { useState, type FormEvent } from "react";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

type MagicAuthMode = "sign-in" | "sign-up";
type MagicAuthStep = "email" | "code";

type MagicAuthResponse = {
  message?: string;
  status?: string;
  email?: string;
};

type MagicAuthFormProps = {
  mode: MagicAuthMode;
};

export function MagicAuthForm({ mode }: MagicAuthFormProps) {
  const isSignUp = mode === "sign-up";
  const [step, setStep] = useState<MagicAuthStep>("email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function requestCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (loading) return;

    const normalizedEmail = email.trim().toLowerCase();
    if (!normalizedEmail) {
      setError("Email is required");
      setMessage(null);
      return;
    }
    if (!EMAIL_PATTERN.test(normalizedEmail)) {
      setError("Enter a valid email address");
      setMessage(null);
      return;
    }

    setLoading(true);
    setError(null);
    setMessage(null);
    try {
      const response = await fetch(`/api/auth/${isSignUp ? "sign-up" : "sign-in"}`, {
        method: "POST",
        credentials: "include",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ email: normalizedEmail }),
      });
      const payload = await response.json().catch(() => null) as MagicAuthResponse | null;
      if (!response.ok) {
        setError(payload?.message?.trim() || "The sign-in email could not be sent. Please try again.");
        return;
      }

      setEmail(payload?.email ?? normalizedEmail);
      setCode("");
      setStep("code");
      setMessage("Check your inbox for a six-digit sign-in code.");
    } catch {
      setError("Unable to reach Plot. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  async function verifyCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (loading) return;

    const normalizedCode = code.trim();
    if (!/^\d{6}$/.test(normalizedCode)) {
      setError("Enter the six-digit code from your email.");
      setMessage(null);
      return;
    }

    setLoading(true);
    setError(null);
    setMessage(null);
    try {
      const response = await fetch("/api/auth/magic-auth/verify", {
        method: "POST",
        credentials: "include",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ code: normalizedCode }),
      });
      const payload = await response.json().catch(() => null) as MagicAuthResponse | null;
      if (!response.ok) {
        setError(payload?.message?.trim() || "That sign-in code is invalid or expired.");
        return;
      }
      if (payload?.status === "authenticated") {
        window.location.assign("/auth/complete");
        return;
      }
      setError("Sign-in could not be completed. Please try again.");
    } catch {
      setError("Unable to reach Plot. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  async function resendCode() {
    if (loading) return;
    setLoading(true);
    setError(null);
    setMessage(null);
    try {
      const response = await fetch("/api/auth/magic-auth/resend", {
        method: "POST",
        credentials: "include",
        headers: { Accept: "application/json" },
      });
      const payload = await response.json().catch(() => null) as MagicAuthResponse | null;
      if (!response.ok) {
        setError(payload?.message?.trim() || "The sign-in email could not be sent. Please try again.");
        return;
      }
      setMessage("A new sign-in code is on its way.");
    } catch {
      setError("Unable to reach Plot. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  if (step === "code") {
    return (
      <div>
        <p className="text-center text-sm leading-6 text-black/55">
          We sent a six-digit code to <span className="font-medium text-black/75">{email}</span>.
        </p>
        <form onSubmit={verifyCode} className="mt-6 grid gap-3" noValidate aria-busy={loading}>
          <label htmlFor="magic-auth-code" className="grid gap-1.5">
            <span className="text-sm font-medium leading-none text-black/75">Sign-in code</span>
            <input
              id="magic-auth-code"
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
              disabled={loading}
              required
              aria-describedby="magic-auth-message"
              aria-invalid={Boolean(error)}
              className="h-11 w-full rounded-xl border border-black/12 bg-white px-4 text-center text-lg tracking-[0.28em] text-black outline-none transition-colors placeholder:text-black/25 focus:border-black/30 focus:ring-2 focus:ring-black/[0.06] aria-invalid:border-red-500/40 aria-invalid:ring-2 aria-invalid:ring-red-500/10 disabled:pointer-events-none disabled:opacity-50"
            />
          </label>

          <div id="magic-auth-message" aria-live="polite">
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
            disabled={loading}
            className="flex h-11 w-full items-center justify-center gap-2 rounded-full bg-[#191919] px-6 text-base font-medium tracking-[-0.015em] text-white shadow-[0_2px_5px_rgba(0,0,0,0.1)] transition-[background-color,box-shadow,transform] hover:-translate-y-0.5 hover:bg-black hover:shadow-[0_5px_10px_rgba(0,0,0,0.16)] focus-visible:outline-2 focus-visible:outline-offset-3 focus-visible:outline-[#191919] disabled:pointer-events-none disabled:opacity-50"
          >
            {loading ? <LoaderCircle className="size-4 animate-spin" aria-hidden="true" /> : null}
            {loading ? "Checking…" : "Continue to Plot"}
          </button>
        </form>

        <div className="mt-4 grid gap-2">
          <button
            type="button"
            onClick={() => void resendCode()}
            disabled={loading}
            className="flex h-10 w-full items-center justify-center rounded-full border border-black/12 bg-white px-4 text-sm font-medium text-black/65 transition-colors hover:border-black/20 hover:text-black focus-visible:outline-2 focus-visible:outline-offset-3 focus-visible:outline-[#191919] disabled:pointer-events-none disabled:opacity-50"
          >
            {loading ? "Sending…" : "Resend code"}
          </button>
          <button
            type="button"
            onClick={() => {
              setStep("email");
              setCode("");
              setError(null);
              setMessage(null);
            }}
            disabled={loading}
            className="text-sm text-black/45 underline underline-offset-4 hover:text-black/70 disabled:pointer-events-none disabled:opacity-50"
          >
            Use a different email
          </button>
        </div>
      </div>
    );
  }

  return (
    <form onSubmit={requestCode} noValidate aria-busy={loading}>
      <div className="grid gap-1.5">
        <label htmlFor="magic-auth-email" className="text-sm font-medium leading-none text-black/75">
          Email
        </label>
        <input
          id="magic-auth-email"
          name="email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => {
            setEmail(event.target.value);
            setError(null);
          }}
          placeholder="jane@company.com"
          disabled={loading}
          maxLength={320}
          required
          aria-describedby="magic-auth-email-error"
          aria-invalid={Boolean(error)}
          className="h-11 w-full rounded-xl border border-black/12 bg-white px-4 text-sm text-black outline-none transition-colors placeholder:text-black/35 focus:border-black/30 focus:ring-2 focus:ring-black/[0.06] aria-invalid:border-red-500/40 aria-invalid:ring-2 aria-invalid:ring-red-500/10 disabled:pointer-events-none disabled:opacity-50"
        />
        <div id="magic-auth-email-error" aria-live="polite">
          {error ? (
            <div role="alert" className="mt-1 flex items-start gap-2.5 rounded-xl border border-red-500/20 bg-red-500/8 px-3.5 py-2.5 text-sm text-red-700">
              <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
              <p>{error}</p>
            </div>
          ) : null}
        </div>
      </div>

      <button
        type="submit"
        disabled={loading}
        className="mt-4 flex h-11 w-full items-center justify-center gap-2 rounded-full bg-[#191919] px-6 text-base font-medium tracking-[-0.015em] text-white shadow-[0_2px_5px_rgba(0,0,0,0.1)] transition-[background-color,box-shadow,transform] hover:-translate-y-0.5 hover:bg-black hover:shadow-[0_5px_10px_rgba(0,0,0,0.16)] focus-visible:outline-2 focus-visible:outline-offset-3 focus-visible:outline-[#191919] disabled:pointer-events-none disabled:opacity-50"
      >
        {loading ? <LoaderCircle className="size-4 animate-spin" aria-hidden="true" /> : null}
        {loading ? "Sending…" : "Email me a sign-in code"}
      </button>
    </form>
  );
}

export function MagicSignInForm() {
  return <MagicAuthForm mode="sign-in" />;
}

export function MagicSignUpForm() {
  return <MagicAuthForm mode="sign-up" />;
}
