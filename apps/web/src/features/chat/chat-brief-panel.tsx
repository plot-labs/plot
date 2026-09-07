"use client";

import { useState } from "react";

import type { ContentBrief, ConfirmedFactInput } from "@plot/api-client";

export type ChatBriefDraft = {
  availability: string;
  pricing: string;
  userAction: string;
  confirmedFactBody: string;
};

export const emptyChatBriefDraft = (): ChatBriefDraft => ({
  availability: "",
  pricing: "",
  userAction: "",
  confirmedFactBody: "",
});

export function toContentBrief(draft: ChatBriefDraft): ContentBrief | undefined {
  const confirmedFacts: ConfirmedFactInput[] = [];
  const factBody = draft.confirmedFactBody.trim();
  if (factBody) {
    confirmedFacts.push({ body: factBody, kind: "AVAILABILITY" });
  }
  const brief: ContentBrief = {
    availability: draft.availability.trim() || null,
    pricing: draft.pricing.trim() || null,
    userAction: draft.userAction.trim() || null,
    confirmedFacts,
  };
  if (
    !brief.availability &&
    !brief.pricing &&
    !brief.userAction &&
    confirmedFacts.length === 0
  ) {
    return undefined;
  }
  return brief;
}

type ChatBriefPanelProps = {
  value: ChatBriefDraft;
  onChange: (next: ChatBriefDraft) => void;
};

export function ChatBriefPanel({ value, onChange }: ChatBriefPanelProps) {
  const [open, setOpen] = useState(false);

  return (
    <div className="mt-3 rounded-xl border border-black/[0.08] bg-black/[0.02] px-3 py-2.5 dark:border-white/10 dark:bg-white/[0.03]">
      <button
        type="button"
        className="flex w-full items-center justify-between text-left text-xs font-medium text-black/70 dark:text-white/70"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <span>Public conditions & brief</span>
        <span className="text-black/40 dark:text-white/40">{open ? "Hide" : "Add"}</span>
      </button>
      {open ? (
        <div className="mt-3 grid gap-2.5">
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
