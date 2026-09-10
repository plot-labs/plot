"use client";

import { CircleAlert, Eye, EyeOff, LoaderCircle } from "lucide-react";
import { useState, type FormEvent } from "react";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";

type PasswordAuthMode = "sign-in" | "sign-up";

type PasswordAuthError = {
  message?: string;
};

type PasswordAuthFormProps = {
  mode: PasswordAuthMode;
};

function PasswordAuthForm({ mode }: PasswordAuthFormProps) {
  const isSignUp = mode === "sign-up";
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<{
    email: string | null;
    password: string | null;
  }>({ email: null, password: null });
  const [error, setError] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (loading) return;

    const normalizedEmail = email.trim();
    const emailError = !normalizedEmail
      ? "Email is required"
      : !EMAIL_PATTERN.test(normalizedEmail)
        ? "Enter a valid email address"
        : null;
    const passwordError = !password.trim()
      ? "Password is required"
      : isSignUp && password.length < 10
        ? "Password must be at least 10 characters"
        : null;

    setFieldErrors({ email: emailError, password: passwordError });
    if (emailError || passwordError) {
      setError(null);
      return;
    }

    setError(null);
    setLoading(true);
    try {
      const response = await fetch(`/api/auth/${isSignUp ? "sign-up" : "sign-in"}/password`, {
        method: "POST",
        credentials: "include",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ email: normalizedEmail, password }),
      });

      if (!response.ok) {
        const payload = await response.json().catch(() => null) as PasswordAuthError | null;
        setError(
          payload?.message?.trim() ||
            (!isSignUp && response.status === 401
              ? INVALID_CREDENTIALS_MESSAGE
              : isSignUp
                ? "Sign-up failed. Please try again."
                : "Sign-in failed. Please try again."),
        );
        return;
      }

      window.location.assign("/auth/complete");
    } catch {
      setError("Unable to reach Plot. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate aria-busy={loading}>
      <div className="grid gap-3">
        <div className="grid gap-1.5">
          <label htmlFor="email" className="text-sm font-medium leading-none text-black/75">
            Email
          </label>
          <input
            id="email"
            name="email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(event) => {
              setEmail(event.target.value);
              setFieldErrors((current) => ({ ...current, email: null }));
              setError(null);
            }}
            placeholder="jane@company.com"
            disabled={loading}
            maxLength={320}
            required
            aria-describedby="email-error"
            aria-invalid={Boolean(fieldErrors.email || error)}
            className="h-11 w-full rounded-xl border border-black/12 bg-white px-4 text-sm text-black outline-none transition-colors placeholder:text-black/35 focus:border-black/30 focus:ring-2 focus:ring-black/[0.06] aria-invalid:border-red-500/40 aria-invalid:ring-2 aria-invalid:ring-red-500/10 disabled:pointer-events-none disabled:opacity-50"
          />
          <div id="email-error" aria-live="polite">
            {fieldErrors.email ? <p className="text-sm text-red-600">{fieldErrors.email}</p> : null}
          </div>
        </div>

        <div className="grid gap-1.5">
          <label htmlFor="password" className="text-sm font-medium leading-none text-black/75">
            Password
          </label>
          <div className="relative">
            <input
              id="password"
              name="password"
              type={showPassword ? "text" : "password"}
              autoComplete={isSignUp ? "new-password" : "current-password"}
              value={password}
              onChange={(event) => {
                setPassword(event.target.value);
                setFieldErrors((current) => ({ ...current, password: null }));
                setError(null);
              }}
              placeholder={isSignUp ? "At least 10 characters" : "Your password"}
              disabled={loading}
              maxLength={128}
              minLength={isSignUp ? 10 : undefined}
              required
              aria-describedby="password-error"
              aria-invalid={Boolean(fieldErrors.password || error)}
              className="h-11 w-full rounded-xl border border-black/12 bg-white px-4 pr-11 text-sm text-black outline-none transition-colors placeholder:text-black/35 focus:border-black/30 focus:ring-2 focus:ring-black/[0.06] aria-invalid:border-red-500/40 aria-invalid:ring-2 aria-invalid:ring-red-500/10 disabled:pointer-events-none disabled:opacity-50"
            />
            <button
              type="button"
              aria-label={showPassword ? "Hide password" : "Show password"}
              onClick={() => setShowPassword((current) => !current)}
              disabled={loading}
              className="absolute top-1/2 right-3 -translate-y-1/2 rounded-md p-1 text-black/40 transition-colors hover:text-black/75 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#191919] disabled:pointer-events-none disabled:opacity-50"
            >
              {showPassword ? (
                <EyeOff className="size-4" aria-hidden="true" />
              ) : (
                <Eye className="size-4" aria-hidden="true" />
              )}
            </button>
          </div>
          <div id="password-error" aria-live="polite">
            {fieldErrors.password ? <p className="text-sm text-red-600">{fieldErrors.password}</p> : null}
          </div>
        </div>
      </div>

      {error ? (
        <div
          role="alert"
          className="mt-4 flex items-start gap-2.5 rounded-xl border border-red-500/20 bg-red-500/8 px-3.5 py-2.5 text-sm text-red-700"
        >
          <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          <p>{error}</p>
        </div>
      ) : null}

      <button
        type="submit"
        disabled={loading}
        className="mt-4 flex h-11 w-full items-center justify-center gap-2 rounded-full bg-[#191919] px-6 text-base font-medium tracking-[-0.015em] text-white shadow-[0_2px_5px_rgba(0,0,0,0.1)] transition-[background-color,box-shadow,transform] hover:-translate-y-0.5 hover:bg-black hover:shadow-[0_5px_10px_rgba(0,0,0,0.16)] focus-visible:outline-2 focus-visible:outline-offset-3 focus-visible:outline-[#191919] disabled:pointer-events-none disabled:opacity-50"
      >
        {loading ? <LoaderCircle className="size-4 animate-spin" aria-hidden="true" /> : null}
        {loading ? (isSignUp ? "Creating account…" : "Signing in…") : isSignUp ? "Create account" : "Log in"}
      </button>
    </form>
  );
}

export function PasswordSignInForm() {
  return <PasswordAuthForm mode="sign-in" />;
}

export function PasswordSignUpForm() {
  return <PasswordAuthForm mode="sign-up" />;
}
