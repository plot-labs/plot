"use client";

import { useState } from "react";

const steps = [
  {
    number: "I",
    label: "Connect",
    title: "Connect one GitHub repository.",
    description:
      "Choose the repository Plot should monitor. The private beta keeps the customer path focused on one release source.",
  },
  {
    number: "II",
    label: "Release",
    title: "Let Plot resolve the exact range.",
    description:
      "When a published release arrives, Plot stores the release boundary, gathers the change evidence, and prepares the next changelog draft.",
  },
  {
    number: "III",
    label: "Review + handoff",
    title: "Inspect citations, then export Markdown.",
    description:
      "Edit the sentences that need your judgment, inspect their saved sources, then Copy or Download. Publishing stays on your existing channel.",
  },
];

const sourceRows = [
  { name: "Published release", detail: "A release boundary was received", meta: "GitHub" },
  { name: "Pull requests", detail: "Changes inside the exact range", meta: "GitHub" },
  { name: "Source snapshot", detail: "Citations saved for review", meta: "Plot" },
];

const signalRows = [
  { name: "Release boundary", detail: "Base and head are fixed", score: "91" },
  { name: "Cited sentence", detail: "Source is ready to inspect", score: "84" },
  { name: "Human review", detail: "Edit before handoff", score: "78" },
];

const planRows = [
  { name: "Changelog draft", detail: "Narrative with saved citations", meta: "review" },
  { name: "Sentence review", detail: "Edit and verify the important claims", meta: "human" },
  { name: "Markdown handoff", detail: "Copy or download when ready", meta: "outside" },
];

const lanes = [
  { title: "Source ledger", rows: sourceRows },
  { title: "Change signals", rows: signalRows },
  { title: "Update pack", rows: planRows },
];

export function HowItWorksSection() {
  const [activeStep, setActiveStep] = useState(0);
  return (
    <section
      className="relative overflow-hidden bg-foreground py-24 text-background lg:py-32"
      id="how-it-works"
    >
      <div className="pointer-events-none absolute inset-0 opacity-[0.035]">
        <div
          className="absolute inset-0"
          style={{
            backgroundImage:
              "linear-gradient(currentColor 1px, transparent 1px), linear-gradient(90deg, currentColor 1px, transparent 1px)",
            backgroundSize: "32px 32px",
          }}
        />
      </div>

      <div className="relative z-10 mx-auto max-w-[1400px] px-6 lg:px-12">
        <div className="mb-16 max-w-4xl lg:mb-20">
          <span className="mb-6 inline-flex items-center gap-3 font-mono text-sm text-background/50">
            <span className="h-px w-8 bg-background/30" />
            Process
          </span>
          <h2 className="landing-reveal font-display text-4xl tracking-tight lg:text-6xl">
            Let Plot prepare
            <br />
            <span className="text-background/50">the changelog your team approves.</span>
          </h2>
        </div>

        <div className="grid gap-12 lg:grid-cols-[0.88fr_1.12fr] lg:gap-20">
          <div className="space-y-0">
            {steps.map((step, index) => (
              <button
                className={`group w-full border-b border-background/10 py-7 text-left transition-all duration-500 ${
                  activeStep === index ? "opacity-100" : "opacity-40 hover:opacity-70"
                }`}
                key={step.number}
                onClick={() => setActiveStep(index)}
                type="button"
              >
                <div className="flex items-start gap-6">
                  <span className="w-10 font-display text-3xl text-background/30">
                    {step.number}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="mb-3 font-mono text-[10px] uppercase text-background/45">
                      {step.label}
                    </div>
                    <h3 className="mb-3 font-display text-2xl transition-transform duration-300 group-hover:translate-x-1 lg:text-3xl">
                      {step.title}
                    </h3>
                    <p className="max-w-2xl leading-relaxed text-background/60">
                      {step.description}
                    </p>
                  </div>
                </div>
              </button>
            ))}
          </div>

          <div className="self-start lg:sticky lg:top-32">
            <div className="border border-background/10 bg-background/[0.02]">
              <div className="flex items-center justify-between border-b border-background/10 px-5 py-4">
                <div>
                  <div className="font-mono text-[10px] uppercase text-background/45">
                    Workspace
                  </div>
                  <div className="mt-1 text-sm text-background/80">
                    Release review
                  </div>
                </div>
                  <div className="font-mono text-[10px] uppercase text-background/45">
                  Publish outside Plot
                </div>
              </div>

              <div className="grid divide-y divide-background/10 lg:grid-cols-3 lg:divide-x lg:divide-y-0">
                {lanes.map((lane, laneIndex) => (
                  <div
                    className={`min-h-[300px] p-4 transition-colors duration-500 ${
                      activeStep === laneIndex ? "bg-background/[0.055]" : ""
                    }`}
                    key={lane.title}
                  >
                    <div className="mb-4 flex items-center justify-between">
                      <div className="font-mono text-[10px] uppercase text-background/45">
                        {lane.title}
                      </div>
                      <div
                        className={`h-1.5 w-1.5 rounded-full ${
                          activeStep === laneIndex ? "bg-background" : "bg-background/20"
                        }`}
                      />
                    </div>

                    <div className="space-y-3">
                      {lane.rows.map((row) => (
                        <div
                          className="border border-background/10 bg-foreground/70 p-3"
                          key={row.name}
                        >
                          <div className="mb-2 flex items-center justify-between gap-3">
                            <div className="font-mono text-[10px] uppercase text-background/72">
                              {row.name}
                            </div>
                            <div className="font-mono text-[9px] uppercase text-background/36">
                              {"score" in row ? row.score : row.meta}
                            </div>
                          </div>
                          <p className="text-sm leading-snug text-background/62">
                            {row.detail}
                          </p>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
