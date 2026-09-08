"use client";

import { useEffect, useState } from "react";
import { LoaderCircle } from "lucide-react";

import { plotApiClient, type ContentProfile } from "@/lib/api-client";

export function WorkspaceContentProfile() {
  const [profile, setProfile] = useState<ContentProfile | null>(null);
  const [productSummary, setProductSummary] = useState("");
  const [primaryAudience, setPrimaryAudience] = useState("");
  const [customerTerms, setCustomerTerms] = useState("");
  const [tone, setTone] = useState("");
  const [defaultLocale, setDefaultLocale] = useState("en");
  const [bannedPhrasesText, setBannedPhrasesText] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reloadNonce, setReloadNonce] = useState(0);

  useEffect(() => {
    function handleWorkspaceChanged() {
      setProfile(null);
      setMessage(null);
      setError(null);
      setIsLoading(true);
      setReloadNonce((value) => value + 1);
    }
    window.addEventListener("plot:workspace-changed", handleWorkspaceChanged);
    return () => window.removeEventListener("plot:workspace-changed", handleWorkspaceChanged);
  }, []);

  useEffect(() => {
    let cancelled = false;
    void plotApiClient.getContentProfile()
      .then((next) => {
        if (cancelled) return;
        setProfile(next);
        setProductSummary(next.productSummary);
        setPrimaryAudience(next.primaryAudience);
        setCustomerTerms(next.customerTerms);
        setTone(next.tone);
        setDefaultLocale(next.defaultLocale || "en");
        setBannedPhrasesText(next.bannedPhrases.join("\n"));
        setIsLoading(false);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : "Content profile could not be loaded.");
        setIsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [reloadNonce]);

  async function save() {
    setIsSaving(true);
    setMessage(null);
    setError(null);
    try {
      const next = await plotApiClient.updateContentProfile({
        productSummary,
        primaryAudience,
        customerTerms,
        tone,
        defaultLocale,
        bannedPhrases: bannedPhrasesText
          .split("\n")
          .map((line) => line.trim())
          .filter(Boolean),
      });
      setProfile(next);
      setMessage("Content profile saved. New drafts will use this revision.");
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Content profile could not be saved.");
    } finally {
      setIsSaving(false);
    }
  }

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-black/45 dark:text-white/45">
        <LoaderCircle className="mr-2 size-4 animate-spin" /> Loading content profile…
      </div>
    );
  }

  return (
    <section className="mx-auto max-w-2xl px-6 py-10">
      <header className="mb-8">
        <h1 className="font-display text-3xl text-black/90 dark:text-white/92">Content</h1>
        <p className="mt-2 text-sm leading-6 text-black/55 dark:text-white/55">
          Product context and voice for drafts. Saving creates a new revision; runs already in progress keep the revision they started with.
        </p>
        {profile?.revisionNumber != null ? (
          <p className="mt-2 text-xs text-black/42 dark:text-white/42">Current revision {profile.revisionNumber}</p>
        ) : null}
      </header>

      <div className="space-y-5 rounded-xl border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/[0.04]">
        <Field label="Product summary" value={productSummary} onChange={setProductSummary} multiline />
        <Field label="Primary audience" value={primaryAudience} onChange={setPrimaryAudience} />
        <Field label="Customer terms" value={customerTerms} onChange={setCustomerTerms} hint="Words customers use for your product." />
        <Field label="Tone" value={tone} onChange={setTone} />
        <Field label="Default locale" value={defaultLocale} onChange={setDefaultLocale} />
        <Field
          label="Banned phrases"
          value={bannedPhrasesText}
          onChange={setBannedPhrasesText}
          multiline
          hint="One phrase per line. Style guidance only — not evidence."
        />
        <div className="flex items-center gap-3 pt-2">
          <button
            type="button"
            onClick={() => void save()}
            disabled={isSaving}
            className="rounded-lg bg-black px-4 py-2 text-sm font-medium text-white disabled:opacity-60 dark:bg-white dark:text-black"
          >
            {isSaving ? "Saving…" : "Save profile"}
          </button>
          {message ? <p className="text-sm text-emerald-700 dark:text-emerald-300">{message}</p> : null}
          {error ? <p role="alert" className="text-sm text-rose-700 dark:text-rose-300">{error}</p> : null}
        </div>
      </div>
    </section>
  );
}

function Field({
  label,
  value,
  onChange,
  multiline = false,
  hint,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  multiline?: boolean;
  hint?: string;
}) {
  const id = label.toLowerCase().replace(/\s+/g, "-");
  return (
    <label className="block text-sm" htmlFor={id}>
      <span className="font-medium text-black/75 dark:text-white/80">{label}</span>
      {hint ? <span className="mt-0.5 block text-xs text-black/45 dark:text-white/45">{hint}</span> : null}
      {multiline ? (
        <textarea
          id={id}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          rows={4}
          className="mt-1.5 w-full rounded-lg border border-black/12 bg-transparent px-3 py-2 text-sm dark:border-white/15"
        />
      ) : (
        <input
          id={id}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          className="mt-1.5 w-full rounded-lg border border-black/12 bg-transparent px-3 py-2 text-sm dark:border-white/15"
        />
      )}
    </label>
  );
}
