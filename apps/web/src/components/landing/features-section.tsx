"use client";

import { icons as logoIcons } from "@iconify-json/logos";
import { motion } from "framer-motion";
import { Sparkles } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";

type CapabilityVisualType = "blocks" | "signals" | "style" | "pack";

type CapabilityFeature = {
  number: string;
  title: string;
  description: string;
  visual: CapabilityVisualType;
};

const features: CapabilityFeature[] = [
  {
    number: "01",
    title: "AI-accelerated Shipping Context",
    description:
      "Bring in the places where AI-assisted product work now moves fastest: PRDs, RFCs, GitHub, Linear, Slack decisions, customer feedback, and more.",
    visual: "blocks",
  },
  {
    number: "02",
    title: "Docs and Content Lag Detection",
    description:
      "Plot spots shipped changes, decisions, tradeoffs, customer pains, docs gaps, and release risks before content quietly falls behind the code.",
    visual: "signals",
  },
  {
    number: "03",
    title: "Brand Voice for Product Content",
    description:
      "Accepted docs, release notes, and launch content become a voice profile so rushed updates still sound like your company, not a generic AI draft.",
    visual: "style",
  },
  {
    number: "04",
    title: "Source-backed Content Packs",
    description:
      "Generate changelog entries, release notes, docs updates, launch briefs, and sales handoff content that catches up to shipping velocity with evidence and brand voice attached.",
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

function BrandIcon({
  icon,
  name,
  monochrome,
}: {
  icon: string;
  name: string;
  monochrome?: boolean;
}) {
  const iconData = logoIcons.icons[icon];
  const width = iconData?.width ?? 256;
  const height = iconData?.height ?? 256;
  const body = monochrome
    ? iconData?.body.replace(/fill="[^"]+"/g, 'fill="currentColor"')
    : iconData?.body;

  if (!iconData) return null;

  return (
    <svg
      aria-label={name}
      className={`size-8 ${monochrome ? "text-background" : ""}`}
      role="img"
      viewBox={`0 0 ${width} ${height}`}
      dangerouslySetInnerHTML={{ __html: body ?? "" }}
    />
  );
}

function BlocksVisual() {
  const sources = [
    { name: "Gmail", icon: "google-gmail", className: "left-[154px] top-[86px] -rotate-12 z-20" },
    { name: "GitHub", icon: "github-icon", className: "left-[234px] top-[58px] rotate-4 z-40", monochrome: true },
    { name: "Slack", icon: "slack-icon", className: "left-[314px] top-[86px] rotate-12 z-20" },
    { name: "Notion", icon: "notion-icon", className: "left-[194px] top-[126px] rotate-3 z-10" },
    { name: "Linear", icon: "linear-icon", className: "left-[278px] top-[126px] -rotate-5 z-10", monochrome: true },
  ];

  return (
    <FeatureShell>
      <VisualStage>
        <div className="absolute inset-0">
          <div className="absolute left-[314px] top-[82px] h-[118px] w-[330px] -translate-x-1/2 rounded-full bg-foreground/[0.05] blur-2xl" />
          {sources.map((source, index) => (
            <motion.div
              animate={{
                y: [0, index < 3 ? -6 : 4, 0],
                rotate: index % 2 === 0 ? [-8, -4, -8] : [6, 3, 6],
              }}
              className={`absolute grid size-[76px] place-items-center rounded-[18px] border border-white/20 bg-black/80 shadow-[inset_0_1px_1px_rgba(255,255,255,0.25),inset_0_-1px_1px_rgba(0,0,0,0.1),0_8px_24px_rgba(0,0,0,0.12),0_2px_6px_rgba(0,0,0,0.08)] ${source.className}`}
              key={source.name}
              transition={{
                delay: index * 0.14,
                duration: 3,
                repeat: Infinity,
                ease: "easeInOut",
              }}
            >
              <BrandIcon
                icon={source.icon}
                name={source.name}
                monochrome={source.monochrome}
              />
            </motion.div>
          ))}
        </div>
      </VisualStage>
    </FeatureShell>
  );
}

function SignalsVisual() {
  const signalRows = [
    { label: "AI-coded Change", score: "91" },
    { label: "Docs Gap", score: "84" },
    { label: "Customer Impact", score: "78" },
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
            Source #482
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
              Change queue
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

function StyleMemoryVisual() {
  const rules = [
    { label: "Cadence", score: 82, width: "62%" },
    { label: "Vocabulary", score: 85, width: "70%" },
    { label: "Proof", score: 88, width: "78%" },
  ];

  return (
    <FeatureShell>
      <VisualStage>
        <FlowLayer>
          <FlowPath d="M174 72 H210" opacity={0.18} />
          <FlowPath d="M174 125 H210" opacity={0.2} />
          <FlowPath d="M174 178 H192 V145 H210" opacity={0.14} />
          <FlowPath d="M360 126 H394" opacity={0.2} />
        </FlowLayer>

        <div className="absolute left-8 top-[47px] w-[140px] space-y-3">
          {["Samples", "Rules", "Channels"].map((label, index) => (
            <motion.div
              animate={{ opacity: [0.55, 0.94, 0.55] }}
              className="rounded-md border border-foreground/10 bg-background/90 px-3 py-3.5 text-center font-mono text-[10px] uppercase text-muted-foreground"
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

        <div className="absolute left-[210px] top-[36px] h-[178px] w-[150px] overflow-hidden rounded-lg border border-foreground/15 bg-background p-3 shadow-[0_24px_70px_rgb(18_17_15_/_0.08)]">
          <div className="mb-2.5 flex items-center justify-between gap-3">
            <div className="font-mono text-[10px] uppercase text-foreground">
              Brand voice
            </div>
            <div className="rounded-full bg-foreground/5 px-2 py-0.5 font-mono text-[8px] uppercase text-muted-foreground">
              locked
            </div>
          </div>
          <div className="space-y-1">
            {rules.map((rule) => (
              <div
                className="rounded-md border border-foreground/10 bg-foreground/[0.025] px-2.5 py-1.5"
                key={rule.label}
              >
                <div className="mb-0.5 flex items-center justify-between gap-2 font-mono text-[8px] uppercase text-muted-foreground">
                  <span>{rule.label}</span>
                  <span>{rule.score}</span>
                </div>
                <div className="h-1 rounded-full bg-foreground/8">
                  <div
                    className="h-full rounded-full bg-foreground/45"
                    style={{ width: rule.width }}
                  />
                </div>
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
          className="absolute left-[394px] top-[42px] h-[166px] w-[144px] rounded-lg border border-foreground/15 bg-background/95 p-3.5 shadow-[0_24px_70px_rgb(18_17_15_/_0.08)]"
          transition={{ duration: 2.8, repeat: Infinity, ease: "easeInOut" }}
        >
          <div className="mb-2.5 flex items-center gap-2 font-mono text-[10px] uppercase text-muted-foreground">
            <Sparkles className="size-3.5 text-foreground" />
            Voice check
          </div>
          <p className="mb-3 text-[12px] font-medium leading-snug text-foreground">
            Same brand voice, tuned for the output.
          </p>
          <div className="grid grid-cols-3 gap-1">
            {["Terms", "Caveat", "Proof"].map((label, index) => (
              <div
                className="rounded-md border border-foreground/10 bg-foreground/[0.025] px-1 py-1.5 text-center font-mono text-[8px] uppercase text-muted-foreground"
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
  const channels = ["Changelog", "Release note", "Help doc"];

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
            Release narrative
          </div>
          <p className="mb-4 text-sm font-medium leading-snug text-foreground">
            Source-backed product update.
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
            Release pack
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
  if (type === "style") return <StyleMemoryVisual />;
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
            <span className="text-muted-foreground">to content that keeps up.</span>
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
