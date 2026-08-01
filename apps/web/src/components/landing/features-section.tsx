"use client";

import {
  FileCode2,
  GitPullRequest,
  MessageSquare,
  ScrollText,
  Sparkles,
  Tags,
} from "lucide-react";
import { useId } from "react";

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
    title: "Connect one release source",
    description:
      "Connect one GitHub repository. Plot watches published releases and resolves the exact base and head range when a release arrives.",
    visual: "blocks",
  },
  {
    number: "02",
    title: "Review the factual story",
    description:
      "Plot prepares a changelog draft with saved citations. Inspect the source behind each claim, edit the wording, and keep review in human hands.",
    visual: "signals",
  },
  {
    number: "03",
    title: "Hand off when it is ready",
    description:
      "Copy the reviewed draft or download it as Markdown. Plot prepares the handoff; you publish on the channel your team already uses.",
    visual: "pack",
  },
  {
    number: "04",
    title: "Coming next",
    description:
      "Docs impact suggestions, customer-update variants, voice and style rules, and additional shipped-work sources are planned after the release review loop.",
    visual: "style",
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
    {
      name: "PRs",
      Icon: GitPullRequest,
      className: "left-[154px] top-[86px] -rotate-12 z-20",
    },
    {
      name: "Release",
      Icon: MessageSquare,
      className: "left-[234px] top-[58px] rotate-4 z-40",
    },
    {
      name: "Range",
      Icon: Tags,
      className: "left-[314px] top-[86px] rotate-12 z-20",
    },
    {
      name: "Draft",
      Icon: ScrollText,
      className: "left-[194px] top-[126px] rotate-3 z-10",
    },
    {
      name: "Cites",
      Icon: FileCode2,
      className: "left-[278px] top-[126px] -rotate-5 z-10",
    },
  ];

  return (
    <FeatureShell>
      <VisualStage>
        <div className="absolute inset-0">
          <div className="absolute left-[314px] top-[82px] h-[118px] w-[330px] -translate-x-1/2 rounded-full bg-foreground/[0.05] blur-2xl" />
          {sources.map((source) => (
            <div
              className={`absolute grid size-[76px] place-items-center rounded-[18px] border border-white/20 bg-black/80 shadow-[inset_0_1px_1px_rgba(255,255,255,0.25),inset_0_-1px_1px_rgba(0,0,0,0.1),0_8px_24px_rgba(0,0,0,0.12),0_2px_6px_rgba(0,0,0,0.08)] ${source.className}`}
              key={source.name}
            >
              <source.Icon
                aria-label={source.name}
                className="size-8 text-background"
              />
            </div>
          ))}
        </div>
      </VisualStage>
    </FeatureShell>
  );
}

function SignalsVisual() {
  const signalRows = [
    { label: "Release boundary", score: "91" },
    { label: "Cited claim", score: "84" },
    { label: "Needs review", score: "78" },
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
            Review queue
            </span>
            <span className="rounded-full bg-foreground/5 px-2 py-1 font-mono text-[9px] text-muted-foreground">
              draft
            </span>
          </div>
          <div className="space-y-2">
            {signalRows.map((signal) => (
              <div
                className="rounded-md border border-foreground/10 bg-background p-2.5"
                key={signal.label}
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
              </div>
            ))}
          </div>
        </div>
      </VisualStage>
    </FeatureShell>
  );
}

function StyleMemoryVisual() {
  const rules = [
    { label: "Source", score: 82, width: "62%" },
    { label: "Excerpt", score: 85, width: "70%" },
    { label: "Status", score: 88, width: "78%" },
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
          {["Source", "Excerpt", "State"].map((label) => (
            <div
              className="rounded-md border border-foreground/10 bg-background/90 px-3 py-3.5 text-center font-mono text-[10px] uppercase text-muted-foreground"
              key={label}
            >
              {label}
            </div>
          ))}
        </div>

        <div className="absolute left-[210px] top-[36px] h-[178px] w-[150px] overflow-hidden rounded-lg border border-foreground/15 bg-background p-3 shadow-[0_24px_70px_rgb(18_17_15_/_0.08)]">
          <div className="mb-2.5 flex items-center justify-between gap-3">
            <div className="font-mono text-[10px] uppercase text-foreground">
            Claim review
            </div>
            <div className="rounded-full bg-foreground/5 px-2 py-0.5 font-mono text-[8px] uppercase text-muted-foreground">
              saved
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

        <div
          className="absolute left-[394px] top-[42px] h-[166px] w-[144px] rounded-lg border border-foreground/15 bg-background/95 p-3.5 shadow-[0_24px_70px_rgb(18_17_15_/_0.08)]"
        >
          <div className="mb-2.5 flex items-center gap-2 font-mono text-[10px] uppercase text-muted-foreground">
            <Sparkles className="size-3.5 text-foreground" />
            Review check
          </div>
          <p className="mb-3 text-[12px] font-medium leading-snug text-foreground">
            Ready to review.
          </p>
          <div className="grid grid-cols-3 gap-1">
            {["Range", "Citation", "Edit"].map((label, index) => (
              <div
                className="rounded-md border border-foreground/10 bg-foreground/[0.025] px-1 py-1.5 text-center font-mono text-[8px] uppercase text-muted-foreground"
                key={label}
              >
                <div className="mb-1 text-foreground">{84 - index * 6}</div>
                {label}
              </div>
            ))}
          </div>
        </div>
      </VisualStage>
    </FeatureShell>
  );
}

function PackVisual() {
  const channels = ["Changelog", "Copy", "Download"];

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
            Review-ready draft
          </div>
          <p className="mb-4 text-sm font-medium leading-snug text-foreground">
            Source-cited changelog.
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
            Handoff
          </div>
          <div className="space-y-2">
            {channels.map((channel) => (
              <div
                className="rounded-md border border-foreground/10 bg-background p-2.5"
                key={channel}
              >
                <div className="mb-1.5 font-mono text-[10px] uppercase text-foreground">
                  {channel}
                </div>
                <div className="space-y-1.5">
                  <div className="h-1.5 w-full rounded-full bg-foreground/18" />
                  <div className="h-1.5 w-2/3 rounded-full bg-foreground/10" />
                </div>
              </div>
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
  return (
    <article
      className={`landing-reveal min-h-[430px] border-foreground/10 bg-background/45 p-5 sm:p-6 lg:p-6 ${
        index % 2 === 0 ? "lg:border-r" : ""
      } ${index < 2 ? "lg:border-b" : ""}`}
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
  return (
    <section id="features" className="relative py-24 lg:py-32">
      <div className="mx-auto max-w-[1400px] px-6 lg:px-12">
        <div className="mx-auto mb-16 max-w-4xl text-center lg:mb-20">
          <span className="mb-6 inline-flex items-center gap-3 font-mono text-sm text-muted-foreground">
            <span className="h-px w-8 bg-foreground/30" />
            Current product
            <span className="h-px w-8 bg-foreground/30" />
          </span>
          <h2 className="landing-reveal text-5xl font-display tracking-tight lg:text-7xl">
            From a shipped release
            <br />
            <span className="text-muted-foreground">to a changelog you can approve.</span>
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
