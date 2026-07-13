"use client";

import { Check, MessageSquareText, Quote, SlidersHorizontal } from "lucide-react";
import { useEffect, useRef, useState } from "react";

const styleRules = [
  {
    label: "Voice",
    detail: "Keep the team's tone intact even when the draft starts from an AI-assisted diff.",
  },
  {
    label: "Terminology",
    detail: "Use product words the team already uses. Flag vague or borrowed jargon.",
  },
  {
    label: "Source",
    detail: "Tie each important statement back to a PR, issue, release, or commit group via citations.",
  },
  {
    label: "Brand guardrails",
    detail: "Avoid timelines and guarantees the brand would not stand behind.",
  },
];

const channels = [
  {
    name: "Release note",
    tone: "concise, customer-facing",
    sample: "We removed two setup steps from onboarding and kept migration guidance in the first run.",
  },
  {
    name: "Help doc",
    tone: "instructional, precise",
    sample: "Use the migration checklist before inviting teammates, especially when importing legacy roles.",
  },
  {
    name: "Launch draft",
    tone: "context-rich, evidence-backed",
    sample: "The strongest proof is reduced setup time, backed by beta calls and PR #482.",
  },
];

export function StyleSection() {
  const [isVisible, setIsVisible] = useState(false);
  const [activeChannel, setActiveChannel] = useState(0);
  const sectionRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) setIsVisible(true);
      },
      { threshold: 0.16 },
    );

    if (sectionRef.current) observer.observe(sectionRef.current);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const interval = setInterval(() => {
      setActiveChannel((prev) => (prev + 1) % channels.length);
    }, 3200);
    return () => clearInterval(interval);
  }, []);

  const active = channels[activeChannel];

  return (
    <section
      className="relative overflow-hidden border-y border-foreground/10 py-24 lg:py-32"
      id="style"
      ref={sectionRef}
    >
      <div className="pointer-events-none absolute inset-0 bg-[linear-gradient(90deg,rgb(18_17_15_/_0.035)_1px,transparent_1px),linear-gradient(180deg,rgb(18_17_15_/_0.035)_1px,transparent_1px)] bg-[size:36px_36px]" />
      <div className="relative mx-auto max-w-[1400px] px-6 lg:px-12">
        <div className="grid gap-14 lg:grid-cols-[0.86fr_1.14fr] lg:gap-20 lg:items-start">
          <div
            className={`transition-all duration-700 ${
              isVisible ? "translate-y-0 opacity-100" : "translate-y-6 opacity-0"
            }`}
          >
              <span className="mb-6 inline-flex items-center gap-3 font-mono text-sm text-muted-foreground">
                <span className="h-px w-8 bg-foreground/30" />
              Voice & Style
            </span>
            <h2 className="mb-8 font-display text-4xl tracking-tight lg:text-6xl">
              Keep every update
              <br />
              in your team&apos;s voice.
            </h2>
            <p className="mb-10 max-w-xl text-xl leading-relaxed text-muted-foreground">
              AI can make code move faster, but it does not know how your team
              explains change. Plot keeps voice, source citations, implementation
              caveats, and draft output in one inspectable pack.
            </p>

            <div className="grid gap-px overflow-hidden rounded-xl border border-foreground/10 bg-foreground/10 sm:grid-cols-3">
              {[
                ["Voice", "tone and terminology"],
                ["Citations", "sources and caveats"],
                ["Formats", "docs, changelog, launch"],
              ].map(([label, value]) => (
                <div className="bg-background p-5" key={label}>
                  <div className="mb-2 font-mono text-[10px] uppercase text-muted-foreground">
                    {label}
                  </div>
                  <div className="text-sm leading-snug text-foreground/75">{value}</div>
                </div>
              ))}
            </div>
          </div>

          <div
            className={`transition-all delay-150 duration-700 ${
              isVisible ? "translate-y-0 opacity-100" : "translate-y-8 opacity-0"
            }`}
          >
            <div className="overflow-hidden rounded-[24px] border border-foreground/10 bg-background/80 shadow-[0_30px_110px_rgb(18_17_15_/_0.06)]">
              <div className="flex flex-col gap-5 border-b border-foreground/10 px-5 py-5 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <div className="font-mono text-[10px] uppercase text-muted-foreground">
                    Voice, source, and citations
                  </div>
                  <div className="mt-1 font-medium text-foreground">
                    One inspectable pack
                  </div>
                </div>
                <div className="flex items-center gap-2 font-mono text-[10px] uppercase text-muted-foreground">
                  <Check className="size-3.5 text-foreground" />
                  Sources attached
                </div>
              </div>

              <div className="grid lg:grid-cols-[0.92fr_1.08fr]">
                <div className="border-b border-foreground/10 p-5 lg:border-b-0 lg:border-r">
                  <div className="mb-4 flex items-center gap-2 font-mono text-[10px] uppercase text-muted-foreground">
                    <SlidersHorizontal className="size-3.5" />
                    Style rules
                  </div>
                  <div className="space-y-3">
                    {styleRules.map((rule) => (
                      <div
                        className="rounded-lg border border-foreground/10 bg-foreground/[0.025] p-3.5"
                        key={rule.label}
                      >
                        <div className="mb-1.5 font-mono text-[10px] uppercase text-foreground">
                          {rule.label}
                        </div>
                        <p className="text-sm leading-snug text-muted-foreground">
                          {rule.detail}
                        </p>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="p-5">
                  <div className="mb-4 flex items-center justify-between gap-4">
                    <div className="flex items-center gap-2 font-mono text-[10px] uppercase text-muted-foreground">
                      <MessageSquareText className="size-3.5" />
                      Output draft
                    </div>
                    <div className="font-mono text-[10px] uppercase text-muted-foreground">
                      {active.tone}
                    </div>
                  </div>

                  <div className="mb-4 grid grid-cols-3 gap-2">
                    {channels.map((channel, index) => (
                      <button
                        className={`rounded-full border px-3 py-2 font-mono text-[9px] uppercase transition-colors ${
                          activeChannel === index
                            ? "border-foreground bg-foreground text-background"
                            : "border-foreground/10 text-muted-foreground hover:border-foreground/30"
                        }`}
                        key={channel.name}
                        onClick={() => setActiveChannel(index)}
                        type="button"
                      >
                        {channel.name}
                      </button>
                    ))}
                  </div>

                  <div className="relative min-h-[260px] rounded-xl border border-foreground/10 bg-background p-5">
                    <Quote className="mb-5 size-7 text-foreground/28" />
                    <p className="mb-8 text-2xl font-display leading-tight text-foreground lg:text-3xl">
                      {active.sample}
                    </p>
                    <div className="space-y-2 border-t border-foreground/10 pt-4">
                      {["Matches team voice", "Sources attached", "Ready to copy outside Plot"].map(
                        (check) => (
                          <div
                            className="flex items-center justify-between gap-3 text-sm"
                            key={check}
                          >
                            <span className="text-muted-foreground">{check}</span>
                            <Check className="size-4 text-foreground" />
                          </div>
                        ),
                      )}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
