"use client";

import { ArrowRight } from "lucide-react";
import { HeroTerminal } from "./hero-terminal";

export function HeroSection() {
  return (
    <section className="relative flex min-h-screen flex-col justify-center overflow-hidden">
      <div className="pointer-events-none absolute right-16 top-[45%] hidden h-[440px] w-[530px] -translate-y-1/2 opacity-65 lg:block xl:right-20 xl:h-[500px] xl:w-[600px]">
        <HeroTerminal />
      </div>

      <div className="pointer-events-none absolute inset-0 overflow-hidden opacity-30">
        {[...Array(8)].map((_, i) => (
          <div
            className="absolute h-px bg-foreground/10"
            key={`h-${i}`}
            style={{
              left: 0,
              right: 0,
              top: `${12.5 * (i + 1)}%`,
            }}
          />
        ))}
        {[...Array(12)].map((_, i) => (
          <div
            className="absolute w-px bg-foreground/10"
            key={`v-${i}`}
            style={{
              bottom: 0,
              left: `${8.33 * (i + 1)}%`,
              top: 0,
            }}
          />
        ))}
      </div>

      <div className="relative z-10 mx-auto w-full max-w-[1400px] px-6 py-32 lg:px-12 lg:py-40">
        <div className="hero-copy-lock max-w-[920px] text-left">
        <div className="mb-8">
          <span className="inline-flex items-center gap-3 font-mono text-sm text-muted-foreground">
            <span className="h-px w-8 bg-foreground/30" />
            Shipped work. Review-ready changelog.
          </span>
        </div>

        <div className="mb-12">
          <h1 className="text-6xl font-display leading-[0.9] tracking-tight md:text-8xl lg:text-[8rem] xl:text-[8.75rem]">
            <span className="block">From shipped</span>
            <span className="block">release to</span>
            <span className="block">
              <span className="relative inline-block align-baseline">
                <span className="inline-flex whitespace-nowrap">review-ready</span>
                <span className="absolute -bottom-2 left-0 right-0 h-3 bg-foreground/10" />
              </span>{" "}changelog.
            </span>
          </h1>
        </div>

        <div className="max-w-xl">
          <p className="max-w-xl text-xl leading-relaxed text-muted-foreground lg:text-2xl">
            Connect one GitHub repository and let Plot resolve each published
            release range into a source-cited changelog you can edit, copy, or
            download. Publishing stays outside Plot.
          </p>

          <div className="mt-9 flex flex-col items-start gap-4 sm:flex-row">
            <a
              className="group inline-flex h-14 items-center justify-center gap-2 rounded-full bg-foreground px-8 text-base font-medium text-background transition-colors hover:bg-foreground/90"
              href="#waitlist"
            >
              Join waitlist
              <ArrowRight className="ml-2 size-4 transition-transform group-hover:translate-x-1" />
            </a>
            <a
              className="inline-flex h-14 items-center justify-center rounded-full border border-foreground/20 bg-background px-8 text-base font-medium transition-colors hover:bg-foreground/5"
              href="#how-it-works"
            >
              See how it works
            </a>
          </div>
        </div>
        </div>
      </div>

    </section>
  );
}
