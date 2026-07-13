"use client";

import { useState } from "react";
import { ArrowRight, Check } from "lucide-react";

import { Button } from "@/components/ui/button";
import { WAITLIST_PAIN_CHANNELS, WAITLIST_ROLES } from "@/lib/waitlist";

type FormState = "idle" | "loading" | "success" | "error";

export function WaitlistForm() {
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("");
  const [painChannel, setPainChannel] = useState("");
  const [state, setState] = useState<FormState>("idle");
  const [message, setMessage] = useState("");

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setState("loading");
    setMessage("");

    const formData = new FormData(event.currentTarget);

    try {
      const response = await fetch("/api/waitlist", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          email,
          role: role || undefined,
          painChannel: painChannel || undefined,
          website: String(formData.get("website") ?? ""),
        }),
      });

      const data = (await response.json()) as { error?: string; duplicate?: boolean };

      if (!response.ok) {
        setState("error");
        setMessage(data.error ?? "Something went wrong. Try again.");
        return;
      }

      setState("success");
      setMessage(
        data.duplicate
          ? "You're already on the waitlist. We'll be in touch."
          : "You're on the list. We'll reach out as early access opens.",
      );
      setEmail("");
      setRole("");
      setPainChannel("");
    } catch {
      setState("error");
      setMessage("Network error. Try again.");
    }
  }

  if (state === "success") {
    return (
      <div className="rounded-2xl border border-foreground/10 bg-background/80 p-6">
        <div className="mb-3 flex items-center gap-3">
          <span className="flex size-9 items-center justify-center rounded-full bg-foreground text-background">
            <Check className="size-4" />
          </span>
          <p className="font-medium text-foreground">You&apos;re on the waitlist</p>
        </div>
        <p className="text-sm leading-relaxed text-muted-foreground">{message}</p>
      </div>
    );
  }

  return (
    <form className="space-y-4" onSubmit={handleSubmit}>
      <div className="flex flex-col gap-3 sm:flex-row">
        <label className="sr-only" htmlFor="waitlist-email">
          Email
        </label>
        <input
          autoComplete="email"
          className="h-14 flex-1 rounded-full border border-foreground/15 bg-background px-5 text-base text-foreground outline-none transition-colors placeholder:text-muted-foreground focus:border-foreground/40"
          id="waitlist-email"
          name="email"
          onChange={(event) => setEmail(event.target.value)}
          placeholder="you@company.com"
          required
          type="email"
          value={email}
        />

        <label className="sr-only" htmlFor="waitlist-role">
          Role
        </label>
        <select
          className="h-14 rounded-full border border-foreground/15 bg-background px-5 text-base text-foreground outline-none transition-colors focus:border-foreground/40 sm:min-w-[180px]"
          id="waitlist-role"
          name="role"
          onChange={(event) => setRole(event.target.value)}
          value={role}
        >
          {WAITLIST_ROLES.map((item) => (
            <option key={item.value || "default"} value={item.value}>
              {item.label}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label
          className="mb-2 block text-sm font-medium text-foreground"
          htmlFor="waitlist-pain-channel"
        >
          Which post-shipping update is most painful?
        </label>
        <select
          className="h-14 w-full rounded-full border border-foreground/15 bg-background px-5 text-base text-foreground outline-none transition-colors focus:border-foreground/40"
          id="waitlist-pain-channel"
          name="painChannel"
          onChange={(event) => setPainChannel(event.target.value)}
          required
          value={painChannel}
        >
          <option disabled value="">
            Select a channel
          </option>
          {WAITLIST_PAIN_CHANNELS.map((item) => (
            <option key={item.value} value={item.value}>
              {item.label}
            </option>
          ))}
        </select>
      </div>

      <input
        aria-hidden="true"
        autoComplete="off"
        className="hidden"
        name="website"
        tabIndex={-1}
        type="text"
      />

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <Button
          className="h-14 rounded-full bg-foreground px-8 text-base text-background hover:bg-foreground/90 group"
          disabled={state === "loading"}
          size="lg"
          type="submit"
        >
          {state === "loading" ? "Joining..." : "Join waitlist"}
          <ArrowRight className="ml-2 size-4 transition-transform group-hover:translate-x-1" />
        </Button>

        <Button
          asChild
          className="h-14 rounded-full border-foreground/20 px-8 text-base hover:bg-foreground/5"
          size="lg"
          type="button"
          variant="outline"
        >
          <a href="mailto:hello@useplot.xyz">Talk to us</a>
        </Button>
      </div>

      {state === "error" ? (
        <p className="text-sm text-destructive" role="alert">
          {message}
        </p>
      ) : null}

      <p className="text-sm font-mono text-muted-foreground">
        Source-backed packs. You publish outside Plot.
      </p>
    </form>
  );
}
