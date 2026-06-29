"use client";

import { motion } from "framer-motion";
import {
  FileText,
  GitPullRequest,
  Mail,
  Radio,
  Sparkles,
} from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";

type CapabilityVisualType = "blocks" | "signals" | "brief" | "pack";

type CapabilityFeature = {
  number: string;
  title: string;
  description: string;
  visual: CapabilityVisualType;
};

const features: CapabilityFeature[] = [
  {
    number: "01",
    title: "Writing Blocks",
    description:
      "Every connection, upload, paste, URL, and forwarded email becomes a normalized block Plot can reason with.",
    visual: "blocks",
  },
  {
    number: "02",
    title: "Signal Detection",
    description:
      "Plot extracts product changes, decisions, customer pains, questions, quotes, and follow-up seeds from each block.",
    visual: "signals",
  },
  {
    number: "03",
    title: "Brief and Angle Engine",
    description:
      "Weekly creator briefs and launch briefs turn scattered context into the next market-facing point of view.",
    visual: "brief",
  },
  {
    number: "04",
    title: "Source-backed Content Packs",
    description:
      "Generate LinkedIn posts, newsletter intros, X threads, launch notes, and sales briefs with claim evidence attached.",
    visual: "pack",
  },
];

const visualStageClass =
  "absolute left-1/2 top-1/2 h-[250px] w-[560px] -translate-x-1/2 -translate-y-1/2 scale-[0.54] sm:scale-100";

function FeatureShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="relative h-[340px] overflow-hidden rounded-xl border border-foreground/10 bg-background/80 sm:h-[250px]">
      <div className="absolute inset-0 bg-[linear-gradient(90deg,rgb(18_17_15_/_0.045)_1px,transparent_1px),linear-gradient(180deg,rgb(18_17_15_/_0.045)_1px,transparent_1px)] bg-[size:28px_28px]" />
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_50%_45%,transparent_0%,rgb(250_249_246_/_0.68)_72%)]" />
      <div className="relative h-full">{children}</div>
    </div>
  );
}

function VisualStage({ children }: { children: React.ReactNode }) {
  return (
    <div className={visualStageClass}>
      <svg
        aria-hidden="true"
        className="absolute inset-0 overflow-visible"
        viewBox="0 0 560 250"
      >
        <defs>
          <filter id="line-soften">
            <feGaussianBlur stdDeviation="0.15" />
          </filter>
        </defs>
      </svg>
      {children}
    </div>
  );
}

function FlowLayer({ children }: { children: React.ReactNode }) {
  return (
    <svg
      aria-hidden="true"
      className="absolute inset-0 overflow-visible"
      viewBox="0 0 560 250"
    >
      {children}
    </svg>
  );
}

function FlowPath({
  d,
  opacity = 0.18,
}: {
  d: string;
  dot?: [number, number];
  opacity?: number;
}) {
  const pathId = `flow-${useId().replace(/:/g, "")}`;
  const animationDelay =
    [...d].reduce((total, char) => total + char.charCodeAt(0), 0) % 6;

  return (
    <>
      <path
        id={pathId}
        d={d}
        fill="none"
        stroke={`rgba(18, 17, 15, ${opacity})`}
        strokeWidth="1"
        vectorEffect="non-scaling-stroke"
      />
      <circle fill="rgba(18, 17, 15, 0.42)" r="2.4">
        <animateMotion
          begin={`${animationDelay * 0.24}s`}
          dur="3.2s"
          repeatCount="indefinite"
        >
          <mpath href={`#${pathId}`} />
        </animateMotion>
      </circle>
    </>
  );
}

function BlocksVisual() {
  const sources = [
    { label: "GitHub PR", icon: GitPullRequest },
    { label: "Launch spec", icon: FileText },
    { label: "Customer email", icon: Mail },
    { label: "RSS article", icon: Radio },
  ];

  return (
    <FeatureShell>
      <VisualStage>
        <FlowLayer>
          <FlowPath d="M204 88 H292 V100 H360" dot={[292, 100]} />
          <FlowPath d="M204 128 H360" dot={[292, 128]} opacity={0.2} />
          <FlowPath d="M204 168 H292 V150 H360" dot={[292, 150]} opacity={0.16} />
          <FlowPath d="M204 208 H292 V168 H360" dot={[292, 168]} opacity={0.12} />
        </FlowLayer>

        <div className="absolute left-6 top-[34px] w-[180px] rounded-lg border border-foreground/10 bg-background/90 p-3 shadow-sm">
          <div className="mb-3 font-mono text-[10px] uppercase text-muted-foreground">
            Input inbox
          </div>
          <div className="space-y-2">
            {sources.map((source, index) => (
              <motion.div
                animate={{ opacity: [0.62, 1, 0.62] }}
                className="flex items-center gap-2 rounded-md border border-foreground/10 bg-background px-3 py-2 text-xs text-foreground/75"
                key={source.label}
                transition={{
                  delay: index * 0.18,
                  duration: 2,
                  repeat: Infinity,
                  ease: "easeInOut",
                }}
              >
                <source.icon className="size-3.5 shrink-0 text-muted-foreground" />
                <span className="truncate">{source.label}</span>
              </motion.div>
            ))}
          </div>
        </div>

        <motion.div
          animate={{ y: [0, -4, 0] }}
          className="absolute left-[360px] top-[60px] w-[176px] rounded-lg border border-foreground/20 bg-background p-4 shadow-[0_24px_70px_rgb(18_17_15_/_0.08)]"
          transition={{ duration: 3, ease: "easeInOut", repeat: Infinity }}
        >
          <div className="mb-3 rounded-md bg-foreground px-3 py-3 text-center font-mono text-[10px] uppercase text-background">
            Writing Block
          </div>
          <div className="space-y-2">
            <div className="h-1.5 w-full rounded-full bg-foreground/12" />
            <div className="h-1.5 w-4/5 rounded-full bg-foreground/10" />
            <div className="h-1.5 w-3/5 rounded-full bg-foreground/10" />
          </div>
          <div className="mt-4 flex gap-2">
            {["ready", "source", "hash"].map((chip) => (
              <span
                className="rounded-full border border-foreground/10 px-2 py-1 font-mono text-[9px] uppercase text-muted-foreground"
                key={chip}
              >
                {chip}
              </span>
            ))}
          </div>
        </motion.div>
      </VisualStage>
    </FeatureShell>
  );
}

function SignalsVisual() {
  const signalRows = [
    { label: "Product Change", score: "91" },
    { label: "Customer Pain", score: "84" },
    { label: "Decision", score: "78" },
  ];

  return (
    <FeatureShell>
      <VisualStage>
        <FlowLayer>
          <FlowPath d="M200 125 H280 V89 H360" dot={[280, 89]} />
          <FlowPath d="M200 125 H280 V146 H360" dot={[280, 146]} opacity={0.2} />
          <FlowPath d="M200 125 H280 V203 H360" dot={[280, 203]} opacity={0.16} />
        </FlowLayer>

        <div className="absolute left-10 top-[82px] w-[160px] rounded-lg border border-foreground/15 bg-foreground p-4 text-background shadow-[0_20px_60px_rgb(18_17_15_/_0.16)]">
          <div className="mb-4 font-mono text-[10px] uppercase text-background/65">
            Block #482
          </div>
          <div className="space-y-2">
            <div className="h-1.5 w-full rounded-full bg-background/35" />
            <div className="h-1.5 w-3/4 rounded-full bg-background/25" />
            <div className="h-1.5 w-1/2 rounded-full bg-background/20" />
          </div>
        </div>

        <div className="absolute left-[360px] top-5 w-[178px] rounded-lg border border-foreground/10 bg-background/95 p-3 shadow-sm">
          <div className="mb-2.5 flex items-center justify-between">
            <span className="font-mono text-[10px] uppercase text-muted-foreground">
              Signal queue
            </span>
            <span className="rounded-full bg-foreground/5 px-2 py-1 font-mono text-[9px] text-muted-foreground">
              auto
            </span>
          </div>
          <div className="space-y-2">
            {signalRows.map((signal, index) => (
              <motion.div
                animate={{ x: [0, index === 0 ? -3 : 0, 0] }}
                className="rounded-md border border-foreground/10 bg-background p-2.5"
                key={signal.label}
                transition={{
                  delay: index * 0.12,
                  duration: 2.1,
                  repeat: Infinity,
                  ease: "easeInOut",
                }}
              >
                <div className="mb-1.5 flex items-center justify-between gap-3">
                  <span className="font-mono text-[10px] uppercase text-foreground">
                    {signal.label}
                  </span>
                  <span className="font-mono text-[10px] text-muted-foreground">
                    {signal.score}
                  </span>
                </div>
                <div className="h-1.5 rounded-full bg-foreground/10">
                  <div
                    className="h-full rounded-full bg-foreground/55"
                    style={{ width: `${Number(signal.score)}%` }}
                  />
                </div>
              </motion.div>
            ))}
          </div>
        </div>
      </VisualStage>
    </FeatureShell>
  );
}

function BriefVisual() {
  return (
    <FeatureShell>
      <VisualStage>
        <FlowLayer>
          <FlowPath d="M160 72 H194 V92 H230" dot={[194, 92]} />
          <FlowPath d="M160 116 H230" dot={[194, 116]} opacity={0.2} />
          <FlowPath d="M160 160 H194 V146 H230" dot={[194, 146]} opacity={0.14} />
          <FlowPath d="M356 125 H392" dot={[374, 125]} opacity={0.18} />
        </FlowLayer>

        <div className="absolute left-6 top-[48px] w-[136px] space-y-3">
          {["Signals", "Memory", "Audience"].map((label, index) => (
            <motion.div
              animate={{ opacity: [0.55, 0.94, 0.55] }}
              className="rounded-md border border-foreground/10 bg-background/90 px-3 py-3 text-center font-mono text-[10px] uppercase text-muted-foreground"
              key={label}
              transition={{
                delay: index * 0.18,
                duration: 2.2,
                repeat: Infinity,
                ease: "easeInOut",
              }}
            >
              {label}
            </motion.div>
          ))}
        </div>

        <div className="absolute left-[230px] top-[38px] h-[176px] w-[126px] rounded-lg border border-foreground/15 bg-background p-3.5 shadow-[0_24px_70px_rgb(18_17_15_/_0.08)]">
          <div className="mb-3 font-mono text-[10px] uppercase text-foreground">
            Brief
          </div>
          <div className="mb-4 space-y-1.5">
            <div className="h-1.5 w-full rounded-full bg-foreground/35" />
            <div className="h-1.5 w-4/5 rounded-full bg-foreground/18" />
            <div className="h-1.5 w-3/5 rounded-full bg-foreground/15" />
          </div>
          <div className="space-y-1.5">
            {[1, 2].map((item) => (
              <div
                className="rounded-md border border-foreground/10 bg-foreground/[0.025] p-2"
                key={item}
              >
                <div className="mb-1 h-1.5 w-3/4 rounded-full bg-foreground/12" />
                <div className="h-1.5 w-1/2 rounded-full bg-foreground/8" />
              </div>
            ))}
          </div>
        </div>

        <motion.div
          animate={{
            borderColor: [
              "rgb(18 17 15 / 0.16)",
              "rgb(18 17 15 / 0.55)",
              "rgb(18 17 15 / 0.16)",
            ],
          }}
          className="absolute left-[392px] top-[38px] h-[176px] w-[150px] rounded-lg border border-foreground/15 bg-background/95 p-3.5 shadow-[0_24px_70px_rgb(18_17_15_/_0.08)]"
          transition={{ duration: 2.8, repeat: Infinity, ease: "easeInOut" }}
        >
          <div className="mb-2.5 flex items-center gap-2 font-mono text-[10px] uppercase text-muted-foreground">
            <Sparkles className="size-3.5 text-foreground" />
            Market POV
          </div>
          <p className="mb-3 text-[13px] font-medium leading-snug text-foreground">
            Turn internal decisions into external narratives.
          </p>
          <div className="grid grid-cols-3 gap-1">
            {["Fit", "Proof", "Now"].map((label, index) => (
              <div
                className="rounded-md border border-foreground/10 bg-foreground/[0.025] px-2 py-1.5 text-center font-mono text-[8px] uppercase text-muted-foreground"
                key={label}
              >
                <div className="mb-1 text-foreground">{84 - index * 6}</div>
                {label}
              </div>
            ))}
          </div>
        </motion.div>
      </VisualStage>
    </FeatureShell>
  );
}

function PackVisual() {
  const channels = ["LinkedIn", "Newsletter", "X thread"];

  return (
    <FeatureShell>
      <VisualStage>
        <FlowLayer>
          <FlowPath d="M232 139 H292 V97 H350" dot={[292, 97]} opacity={0.2} />
          <FlowPath d="M232 139 H292 V166 H350" dot={[292, 166]} opacity={0.16} />
          <FlowPath d="M232 139 H292 V235 H350" dot={[292, 235]} opacity={0.12} />
        </FlowLayer>

        <div className="absolute left-[42px] top-[70px] w-[190px] rounded-lg border border-foreground/15 bg-background p-4 shadow-[0_18px_54px_rgb(18_17_15_/_0.08)]">
          <div className="mb-3 font-mono text-[10px] uppercase text-muted-foreground">
            Launch narrative
          </div>
          <p className="mb-4 text-sm font-medium leading-snug text-foreground">
            Source-backed launch story.
          </p>
          <div className="flex gap-1.5">
            {["Brief", "3 sources"].map((chip) => (
              <span
                className="rounded-full border border-foreground/10 px-2 py-1 font-mono text-[8px] uppercase text-muted-foreground"
                key={chip}
              >
                {chip}
              </span>
            ))}
          </div>
        </div>

        <div className="absolute left-[350px] top-[28px] w-[182px] rounded-lg border border-foreground/10 bg-background/95 p-3 shadow-sm">
          <div className="mb-2.5 flex items-center gap-2 font-mono text-[10px] uppercase text-muted-foreground">
            <Sparkles className="size-3.5 text-foreground" />
            Content pack
          </div>
          <div className="space-y-2">
            {channels.map((channel, index) => (
              <motion.div
                animate={{ x: [0, index === 1 ? 4 : 2, 0] }}
                className="rounded-md border border-foreground/10 bg-background p-2.5"
                key={channel}
                transition={{
                  delay: index * 0.16,
                  duration: 2.1,
                  repeat: Infinity,
                  ease: "easeInOut",
                }}
              >
                <div className="mb-1.5 font-mono text-[10px] uppercase text-foreground">
                  {channel}
                </div>
                <div className="space-y-1.5">
                  <div className="h-1.5 w-full rounded-full bg-foreground/18" />
                  <div className="h-1.5 w-2/3 rounded-full bg-foreground/10" />
                </div>
              </motion.div>
            ))}
          </div>
        </div>
      </VisualStage>
    </FeatureShell>
  );
}

function CapabilityVisual({ type }: { type: CapabilityVisualType }) {
  if (type === "blocks") return <BlocksVisual />;
  if (type === "signals") return <SignalsVisual />;
  if (type === "brief") return <BriefVisual />;
  return <PackVisual />;
}

function FeatureCard({
  feature,
  index,
}: {
  feature: CapabilityFeature;
  index: number;
}) {
  const [isVisible, setIsVisible] = useState(false);
  const cardRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) setIsVisible(true);
      },
      { threshold: 0.18 },
    );

    if (cardRef.current) observer.observe(cardRef.current);
    return () => observer.disconnect();
  }, []);

  return (
    <article
      ref={cardRef}
      className={`min-h-[430px] border-foreground/10 bg-background/45 p-5 transition-all duration-700 sm:p-6 lg:p-6 ${
        index % 2 === 0 ? "lg:border-r" : ""
      } ${index < 2 ? "lg:border-b" : ""} ${
        isVisible ? "translate-y-0 opacity-100" : "translate-y-8 opacity-0"
      }`}
      style={{ transitionDelay: `${index * 90}ms` }}
    >
      <div className="mb-5 flex items-start justify-between gap-5">
        <div>
          <div className="mb-4 font-mono text-xs text-muted-foreground">
            {feature.number}
          </div>
          <h3 className="text-3xl font-display tracking-tight text-foreground lg:text-4xl">
            {feature.title}
          </h3>
        </div>
        <span className="mt-1 rounded-full border border-foreground/10 px-3 py-1 font-mono text-[10px] uppercase text-muted-foreground">
          {feature.visual}
        </span>
      </div>

      <p className="mb-6 max-w-xl text-base leading-relaxed text-muted-foreground lg:text-lg">
        {feature.description}
      </p>

      <CapabilityVisual type={feature.visual} />
    </article>
  );
}

export function FeaturesSection() {
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

  return (
    <section id="features" ref={sectionRef} className="relative py-24 lg:py-32">
      <div className="mx-auto max-w-[1400px] px-6 lg:px-12">
        <div className="mx-auto mb-16 max-w-4xl text-center lg:mb-20">
          <span className="mb-6 inline-flex items-center gap-3 font-mono text-sm text-muted-foreground">
            <span className="h-px w-8 bg-foreground/30" />
            Capabilities
            <span className="h-px w-8 bg-foreground/30" />
          </span>
          <h2
            className={`text-5xl font-display tracking-tight transition-all duration-700 lg:text-7xl ${
              isVisible ? "translate-y-0 opacity-100" : "translate-y-4 opacity-0"
            }`}
          >
            From raw context
            <br />
            <span className="text-muted-foreground">to what to say next.</span>
          </h2>
        </div>

        <div className="overflow-hidden rounded-[24px] border border-foreground/10 bg-background/70 shadow-[0_30px_110px_rgb(18_17_15_/_0.06)]">
          <div className="grid lg:grid-cols-2">
            {features.map((feature, index) => (
              <FeatureCard key={feature.number} feature={feature} index={index} />
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
