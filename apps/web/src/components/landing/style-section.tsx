"use client";

import { Check, MessageSquareText, Quote, SlidersHorizontal } from "lucide-react";
import { useState } from "react";

const styleRules = [
  {
    label: "Docs impact",
    detail: "Suggest which documentation may need attention after the release review loop is proven.",
  },
  {
    label: "Customer update",
    detail: "Turn the approved changelog into a customer-facing variant when that workflow is available.",
  },
  {
    label: "Voice rules",
    detail: "Apply team terminology and approved examples after the core review path is working.",
  },
  {
    label: "More sources",
    detail: "Connect additional shipped-work sources only when design partners need them.",
  },
];

const channels = [
  {
    name: "Docs impact",
    tone: "planned surface",
    sample: "Plot will suggest documentation changes after the release review loop is proven.",
  },
  {
    name: "Customer update",
    tone: "planned surface",
    sample: "Plot will translate an approved changelog into a customer-facing update.",
  },
  {
    name: "Voice rules",
    tone: "planned surface",
    sample: "Plot will learn the team's terminology and approved style after the core workflow.",
  },
];

export function StyleSection() {
  const [activeChannel, setActiveChannel] = useState(0);

  const active = channels[activeChannel];

  return (
    <section
      className="relative overflow-hidden border-y border-foreground/10 py-24 lg:py-32"
      id="style"
    >
      <div className="pointer-events-none absolute inset-0 bg-[linear-gradient(90deg,rgb(18_17_15_/_0.035)_1px,transparent_1px),linear-gradient(180deg,rgb(18_17_15_/_0.035)_1px,transparent_1px)] bg-[size:36px_36px]" />
      <div className="relative mx-auto max-w-[1400px] px-6 lg:px-12">
        <div className="grid gap-14 lg:grid-cols-[0.86fr_1.14fr] lg:gap-20 lg:items-start">
          <div className="landing-reveal">
              <span className="mb-6 inline-flex items-center gap-3 font-mono text-sm text-muted-foreground">
              <span className="h-px w-8 bg-foreground/30" />
              Coming next
            </span>
            <h2 className="mb-8 font-display text-4xl tracking-tight lg:text-6xl">
              Extend the release loop
              <br />
              when it earns its place.
            </h2>
            <p className="mb-10 max-w-xl text-xl leading-relaxed text-muted-foreground">
              The private beta stays focused on a source-cited changelog that a
              human can review and hand off. Docs impact suggestions,
              customer-update variants, voice and style rules, and more sources
              come next.
            </p>

            <div className="grid gap-px overflow-hidden rounded-xl border border-foreground/10 bg-foreground/10 sm:grid-cols-3">
              {[
                ["Docs", "impact suggestions"],
                ["Customers", "approved variants"],
                ["Voice", "rules and examples"],
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

          <div className="landing-reveal">
            <div className="overflow-hidden rounded-[24px] border border-foreground/10 bg-background/80 shadow-[0_30px_110px_rgb(18_17_15_/_0.06)]">
              <div className="flex flex-col gap-5 border-b border-foreground/10 px-5 py-5 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <div className="font-mono text-[10px] uppercase text-muted-foreground">
                    Coming next
                  </div>
                  <div className="mt-1 font-medium text-foreground">
                    Planned surfaces
                  </div>
                </div>
                <div className="flex items-center gap-2 font-mono text-[10px] uppercase text-muted-foreground">
                  <Check className="size-3.5 text-foreground" />
                  After core loop
                </div>
              </div>

              <div className="grid lg:grid-cols-[0.92fr_1.08fr]">
                <div className="border-b border-foreground/10 p-5 lg:border-b-0 lg:border-r">
                  <div className="mb-4 flex items-center gap-2 font-mono text-[10px] uppercase text-muted-foreground">
                    <SlidersHorizontal className="size-3.5" />
                    Planned scope
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
                      Future variant
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
                      {["Planned after core loop", "Customer-reviewed", "Human handoff"].map(
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
