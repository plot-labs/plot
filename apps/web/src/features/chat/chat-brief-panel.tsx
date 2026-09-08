"use client";

import { useEffect, useState } from "react";

import type { ContentBrief, ConfirmedFactInput, ContentType } from "@plot/api-client";

export type ChatBriefDraft = {
  purpose: string;
  audience: string;
  availability: string;
  pricing: string;
  userAction: string;
	confirmedFactBody: string;
	ctaDestinationId: string;
	ctaDestinationLabel: string;
	ctaDestinationUrl: string;
	ctaDestinationConfirmed: boolean;
};

export const emptyChatBriefDraft = (): ChatBriefDraft => ({
  purpose: "",
  audience: "",
  availability: "",
  pricing: "",
  userAction: "",
	confirmedFactBody: "",
	ctaDestinationId: "",
	ctaDestinationLabel: "",
	ctaDestinationUrl: "",
	ctaDestinationConfirmed: false,
});

export function toContentBrief(draft: ChatBriefDraft): ContentBrief | undefined {
  const confirmedFacts: ConfirmedFactInput[] = [];
  const factBody = draft.confirmedFactBody.trim();
	if (factBody) {
    confirmedFacts.push({ body: factBody, kind: "AVAILABILITY" });
	}
	const destinationLabel = draft.ctaDestinationLabel.trim();
	const destinationUrl = draft.ctaDestinationUrl.trim();
	const destinations = draft.ctaDestinationConfirmed && destinationLabel && isAbsoluteHttpsUrl(destinationUrl)
	  ? [{ id: draft.ctaDestinationId || fallbackDestinationId(destinationLabel, destinationUrl), label: destinationLabel, url: destinationUrl }]
	  : [];
	const brief: ContentBrief = {
    purpose: draft.purpose.trim() || null,
    audience: draft.audience.trim() || null,
    availability: draft.availability.trim() || null,
    pricing: draft.pricing.trim() || null,
    userAction: draft.userAction.trim() || null,
		confirmedFacts,
		destinations,
  };
  if (
    !brief.purpose &&
    !brief.audience &&
    !brief.availability &&
    !brief.pricing &&
    !brief.userAction &&
			confirmedFacts.length === 0
			&& destinations.length === 0
  ) {
    return undefined;
  }
  return brief;
}

type ChatBriefPanelProps = {
  value: ChatBriefDraft;
  onChange: (next: ChatBriefDraft) => void;
  contentType?: ContentType;
};

export function ChatBriefPanel({ value, onChange, contentType = "CHANGELOG" }: ChatBriefPanelProps) {
  const isLaunch = contentType === "LAUNCH_ANNOUNCEMENT";
  const [open, setOpen] = useState(isLaunch);

  useEffect(() => {
    if (isLaunch) queueMicrotask(() => setOpen(true));
  }, [isLaunch]);

  return (
    <div className="mt-3 rounded-xl border border-black/[0.08] bg-black/[0.02] px-3 py-2.5 dark:border-white/10 dark:bg-white/[0.03]">
      <button
        type="button"
        className="flex w-full items-center justify-between text-left text-xs font-medium text-black/70 dark:text-white/70"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <span>{isLaunch ? "Audience, purpose & public conditions" : "Public conditions & brief"}</span>
        <span className="text-black/40 dark:text-white/40">{open ? "Hide" : "Add"}</span>
      </button>
      {open ? (
        <div className="mt-3 grid gap-2.5">
		  <BriefField
            label={isLaunch ? "Purpose (recommended)" : "Purpose"}
            value={value.purpose}
            onChange={(purpose) => onChange({ ...value, purpose })}
            placeholder="e.g. Announce public beta"
		  />
		  <CtaDestinationFields value={value} onChange={onChange} />
          <BriefField
            label={isLaunch ? "Audience (recommended)" : "Audience"}
            value={value.audience}
            onChange={(audience) => onChange({ ...value, audience })}
            placeholder="e.g. Founders evaluating Plot"
          />
          <BriefField
            label="Availability / public conditions"
            value={value.availability}
            onChange={(availability) => onChange({ ...value, availability })}
            placeholder="e.g. Public beta starts Monday"
          />
          <BriefField
            label="Pricing"
            value={value.pricing}
            onChange={(pricing) => onChange({ ...value, pricing })}
            placeholder="e.g. Free during beta"
          />
          <BriefField
            label="CTA / next step"
            value={value.userAction}
            onChange={(userAction) => onChange({ ...value, userAction })}
            placeholder="e.g. Join the waitlist"
          />
          <BriefField
            label="Confirmed fact (becomes USER_CONFIRMED evidence)"
            value={value.confirmedFactBody}
            onChange={(confirmedFactBody) => onChange({ ...value, confirmedFactBody })}
            placeholder="A fact you confirm is true for this run"
          />
        </div>
      ) : null}
    </div>
  );
}

function CtaDestinationFields({
	value,
	onChange,
}: {
	value: ChatBriefDraft;
	onChange: (next: ChatBriefDraft) => void;
}) {
	const hasInput = Boolean(value.ctaDestinationLabel.trim() || value.ctaDestinationUrl.trim());
	const valid = Boolean(value.ctaDestinationLabel.trim() && isAbsoluteHttpsUrl(value.ctaDestinationUrl));
	return (
		<div className="grid gap-2 rounded-lg border border-dashed border-black/15 p-2.5 dark:border-white/15">
			<p className="text-[11px] text-black/55 dark:text-white/55">Confirmed CTA destination (optional)</p>
			<BriefField
				label="Button label"
				value={value.ctaDestinationLabel}
				onChange={(ctaDestinationLabel) => onChange({ ...value, ctaDestinationLabel, ctaDestinationConfirmed: false })}
				placeholder="e.g. Join the beta"
			/>
			<BriefField
				label="HTTPS URL"
				value={value.ctaDestinationUrl}
				onChange={(ctaDestinationUrl) => onChange({ ...value, ctaDestinationUrl, ctaDestinationConfirmed: false })}
				placeholder="https://example.com/join"
			/>
			{hasInput && !valid ? <p role="alert" className="text-[11px] text-rose-700 dark:text-rose-300">Enter a label and an absolute HTTPS URL.</p> : null}
			<button
				type="button"
				disabled={!valid}
				onClick={() => onChange({
					...value,
					ctaDestinationId: value.ctaDestinationId || fallbackDestinationId(value.ctaDestinationLabel, value.ctaDestinationUrl),
					ctaDestinationConfirmed: true,
				})}
				className="min-h-8 justify-self-start rounded-lg border border-black/15 px-2.5 text-xs font-medium text-black/70 transition hover:bg-black/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 disabled:cursor-not-allowed disabled:opacity-40 dark:border-white/15 dark:text-white/70 dark:hover:bg-white/[0.06]"
			>
				{value.ctaDestinationConfirmed ? "Destination confirmed" : "Confirm destination"}
			</button>
		</div>
	);
}

function isAbsoluteHttpsUrl(value: string): boolean {
	try {
		const url = new URL(value.trim());
		return url.protocol === "https:" && Boolean(url.hostname) && !url.username && !url.password && (url.port === "" || url.port === "443");
	} catch {
		return false;
	}
}

function fallbackDestinationId(label: string, url: string): string {
	if (typeof globalThis.crypto?.randomUUID === "function") return globalThis.crypto.randomUUID();
	const seed = `${label}:${url}`.split("").reduce((hash, character) => ((hash * 31) + character.charCodeAt(0)) >>> 0, 0);
	return `00000000-0000-4000-8000-${seed.toString(16).padStart(12, "0")}`;
}

function BriefField({
  label,
  value,
  onChange,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
}) {
  return (
    <label className="grid gap-1 text-[11px] text-black/55 dark:text-white/55">
      <span>{label}</span>
      <input
        type="text"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        className="rounded-lg border border-black/10 bg-white px-2.5 py-1.5 text-[13px] text-black/85 outline-none focus:border-black/25 dark:border-white/12 dark:bg-[#16171a] dark:text-white/88 dark:focus:border-white/30"
      />
    </label>
  );
}
