"use client";

import Image from "next/image";
import { useEffect, useMemo, useState } from "react";

const terminalLines = [
  "$ plot agent run release-pack --watch=week-24",
  "source/pr-482       -> selected",
  "source/release-note -> docs delta",
  "voice/profile       -> product-led",
  "",
  "$ plot review checks",
  "style match         92%",
  "claims attached     7",
  "source map          ready",
  "",
  "$ plot draft pack --approval=required",
  "changelog           ready",
  "docs update         typing...",
];

function TypedTerminalLines() {
  const fullText = useMemo(() => terminalLines.join("\n"), []);
  const [visibleText, setVisibleText] = useState("");

  useEffect(() => {
    let index = 0;
    let timeoutId: number;

    const tick = () => {
      setVisibleText(fullText.slice(0, index));
      index += 1;

      if (index <= fullText.length) {
        const currentChar = fullText[index - 1];
        timeoutId = window.setTimeout(tick, currentChar === "\n" ? 210 : 28);
      } else {
        timeoutId = window.setTimeout(() => {
          index = 0;
          setVisibleText("");
          tick();
        }, 2400);
      }
    };

    tick();
    return () => window.clearTimeout(timeoutId);
  }, [fullText]);

  return (
    <pre className="whitespace-pre-wrap font-mono text-[9px] leading-[1.58] text-[#e8e0c8] xl:text-[10px]">
      {visibleText}
      <span className="inline-block h-3 w-1.5 translate-y-0.5 bg-[#e8e0c8] hero-terminal-cursor" />
    </pre>
  );
}

export function HeroTerminal() {
  return (
    <div className="relative h-full w-full">
      <div className="absolute left-[2%] top-[8%] aspect-[796/662] w-[96%] overflow-hidden drop-shadow-[0_44px_96px_rgb(18_17_15_/_0.22)]">
        <Image
          src="/mac-classic-frame.png"
          alt=""
          aria-hidden="true"
          fill
          priority
          sizes="760px"
          className="object-cover object-top"
        />

        <div className="absolute left-[17.65%] top-[22.05%] flex h-[51.2%] w-[64.7%] flex-col overflow-hidden rounded-[10px] border border-black/80 bg-[#11100c] shadow-[inset_0_0_0_1px_rgb(232_224_200_/_0.12),inset_0_0_42px_rgb(0_0_0_/_0.72)]">
          <div className="flex h-7 shrink-0 items-center justify-between border-b border-[#e8e0c8]/12 bg-[#201d16] px-3">
            <div className="flex items-center gap-2">
              <span className="size-2 border border-[#e8e0c8]/45 bg-[#e8e0c8]/15" />
              <span className="size-2 border border-[#e8e0c8]/35 bg-transparent" />
            </div>
            <div className="font-mono text-[8px] uppercase text-[#e8e0c8]/58">
              Plot Console
            </div>
            <div className="h-2 w-5 border border-[#e8e0c8]/30" />
          </div>

          <div className="relative min-h-0 flex-1 px-4 py-3">
            <div className="pointer-events-none absolute inset-0 bg-[linear-gradient(rgb(232_224_200_/_0.045)_1px,transparent_1px)] bg-[size:100%_6px]" />
            <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_52%_42%,transparent_0%,rgb(0_0_0_/_0.18)_68%)]" />

            <div className="relative grid h-full grid-cols-[1fr_82px] gap-3 xl:grid-cols-[1fr_96px]">
              <div className="min-w-0">
                <div className="mb-2 flex items-center gap-2 font-mono text-[8px] uppercase text-[#e8e0c8]/42">
                  <span className="h-px w-5 bg-[#e8e0c8]/25" />
                  Source mapped
                </div>
                <TypedTerminalLines />
              </div>

              <div className="space-y-1.5 pt-6">
                {[
                  ["Voice", "92%"],
                  ["Claims", "7"],
                  ["Pack", "3"],
                ].map(([label, value]) => (
                  <div
                    className="border border-[#e8e0c8]/12 bg-[#e8e0c8]/[0.035] px-2.5 py-1.5"
                    key={label}
                  >
                    <div className="font-mono text-[8px] uppercase text-[#e8e0c8]/40">
                      {label}
                    </div>
                    <div className="mt-1 font-display text-xl leading-none text-[#e8e0c8]">
                      {value}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>

        <Image
          src="/mac-classic-glare.png"
          alt=""
          aria-hidden="true"
          fill
          sizes="760px"
          className="pointer-events-none object-cover object-top opacity-55"
        />
      </div>

      <style jsx>{`
        .hero-terminal-cursor {
          animation: heroTerminalCursor 0.9s steps(1) infinite;
        }

        @keyframes heroTerminalCursor {
          50% {
            opacity: 0;
          }
        }
      `}</style>
    </div>
  );
}
