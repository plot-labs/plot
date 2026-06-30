"use client";

import { Check, MessageSquareText, Quote, SlidersHorizontal } from "lucide-react";
import { useEffect, useRef, useState } from "react";

const styleRules = [
  {
    label: "Cadence",
    detail: "Short opening line, then one proof sentence before the ask.",
  },
  {
    label: "Vocabulary",
    detail: "Use product words the team already uses. Flag borrowed jargon.",
  },
  {
    label: "Point of view",
    detail: "Keep the founder's stance intact across LinkedIn, newsletter, and launch notes.",
  },
  {
    label: "Do-not-say list",
    detail: "Avoid claims, hype words, and phrases the brand would not publish.",
  },
];

const channels = [
  {
    name: "LinkedIn",
    tone: "direct, founder-led",
    sample: "We changed the onboarding story because setup time kept showing up in calls.",
  },
  {
    name: "Newsletter",
    tone: "slower, more reflective",
    sample: "This week, the useful signal was not a feature. It was the objection customers repeated.",
  },
  {
    name: "Launch note",
    tone: "specific, source-backed",
    sample: "The new flow removes two setup steps and keeps migration language close to the product proof.",
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
              Style Memory
            </span>
            <h2 className="mb-8 font-display text-4xl tracking-tight lg:text-6xl">
              Keep one brand voice
              <br />
              across every draft.
            </h2>
            <p className="mb-10 max-w-xl text-xl leading-relaxed text-muted-foreground">
              Plot inherits Tyquill&apos;s writing-style layer: accepted samples,
              brand rules, and channel history become a reusable voice profile
              before any content pack is drafted.
            </p>

            <div className="grid gap-px overflow-hidden rounded-xl border border-foreground/10 bg-foreground/10 sm:grid-cols-3">
              {[
                ["Samples", "accepted posts and notes"],
                ["Profile", "cadence, vocabulary, POV"],
                ["Adapters", "channel-specific delivery"],
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
                    Voice profile
                  </div>
                  <div className="mt-1 font-medium text-foreground">
                    One style source of truth
                  </div>
                </div>
                <div className="flex items-center gap-2 font-mono text-[10px] uppercase text-muted-foreground">
                  <Check className="size-3.5 text-foreground" />
                  Review required
                </div>
              </div>

              <div className="grid lg:grid-cols-[0.92fr_1.08fr]">
                <div className="border-b border-foreground/10 p-5 lg:border-b-0 lg:border-r">
                  <div className="mb-4 flex items-center gap-2 font-mono text-[10px] uppercase text-muted-foreground">
                    <SlidersHorizontal className="size-3.5" />
                    Brand rules
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
                      Channel draft
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
                      {["Matches cadence", "Keeps approved vocabulary", "Claims require source"].map(
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
