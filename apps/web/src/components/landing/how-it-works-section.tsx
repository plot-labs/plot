"use client";

import { useEffect, useRef, useState } from "react";

const steps = [
  {
    number: "I",
    label: "Configure",
    title: "Set the window and voice.",
    description:
      "Choose the shipping window, release cadence, and shipped-work sources. Add the examples and rules that define how your team writes.",
  },
  {
    number: "II",
    label: "Agent drafts",
    title: "Let Plot prepare the pack.",
    description:
      "The update agent imports shipped changes, proposes what matters, attaches source citations, and drafts each channel in the right style.",
  },
  {
    number: "III",
    label: "Handoff",
    title: "Inspect, then publish outside Plot.",
    description:
      "Humans and coding agents see the same pack, citations, caveats, and style guidance. You edit, copy, and publish on your own channels.",
  },
];

const sourceRows = [
  { name: "PR #482", detail: "Onboarding import flow changed", meta: "Repo" },
  { name: "Issue thread", detail: "Migration guardrail requested", meta: "Tracker" },
  { name: "Release note", detail: "Role setup shipped", meta: "Release" },
];

const signalRows = [
  { name: "Agent-picked change", detail: "New onboarding path is live", score: "91" },
  { name: "Docs gap", detail: "Role setup needs migration language", score: "84" },
  { name: "Launch decision", detail: "Lead with reduced setup time", score: "78" },
];

const planRows = [
  { name: "Release brief", detail: "Narrative, citations, and owners", meta: "7 cites" },
  { name: "Docs update", detail: "Steps, caveats, and linked sources", meta: "help" },
  { name: "Launch draft", detail: "Customer impact with source chips", meta: "draft" },
];

const lanes = [
  { title: "Source ledger", rows: sourceRows },
  { title: "Change signals", rows: signalRows },
  { title: "Update pack", rows: planRows },
];

export function HowItWorksSection() {
  const [activeStep, setActiveStep] = useState(0);
  const [isVisible, setIsVisible] = useState(false);
  const sectionRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) setIsVisible(true);
      },
      { threshold: 0.1 },
    );

    if (sectionRef.current) observer.observe(sectionRef.current);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const interval = setInterval(() => {
      setActiveStep((prev) => (prev + 1) % steps.length);
    }, 5000);
    return () => clearInterval(interval);
  }, []);

  return (
    <section
      className="relative overflow-hidden bg-foreground py-24 text-background lg:py-32"
      id="how-it-works"
      ref={sectionRef}
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
          <h2
            className={`font-display text-4xl tracking-tight transition-all duration-700 lg:text-6xl ${
              isVisible ? "translate-y-0 opacity-100" : "translate-y-4 opacity-0"
            }`}
          >
            Let the agent prepare
            <br />
            <span className="text-background/50">updates your team approves.</span>
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
                    {activeStep === index ? (
                      <div className="mt-4 h-px overflow-hidden bg-background/20">
                        <div className="h-full w-0 bg-background how-progress" />
                      </div>
                    ) : null}
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
                    Onboarding release / week 24
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

      <style jsx>{`
        @keyframes howProgress {
          from {
            width: 0%;
          }
          to {
            width: 100%;
          }
        }

        .how-progress {
          animation: howProgress 5s linear forwards;
        }
      `}</style>
    </section>
  );
}
