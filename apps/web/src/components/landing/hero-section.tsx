"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { ArrowRight } from "lucide-react";
import { HeroTerminal } from "./hero-terminal";

const words = ["Docs", "Changelogs", "Customer updates", "Launch posts"];

export function HeroSection() {
  const isVisible = true;
  const [wordIndex, setWordIndex] = useState(0);

  useEffect(() => {
    const interval = setInterval(() => {
      setWordIndex((prev) => (prev + 1) % words.length);
    }, 2500);
    return () => clearInterval(interval);
  }, []);

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
        <div
          className={`mb-8 transition-all duration-700 ${
            isVisible ? "translate-y-0 opacity-100" : "translate-y-4 opacity-0"
          }`}
        >
          <span className="inline-flex items-center gap-3 font-mono text-sm text-muted-foreground">
            <span className="h-px w-8 bg-foreground/30" />
            Ship fast. Write less. Stay in your team&apos;s voice.
          </span>
        </div>

        <div className="mb-12">
          <h1
            className={`text-6xl font-display leading-[0.9] tracking-tight transition-all duration-1000 md:text-8xl lg:text-[8rem] xl:text-[8.75rem] ${
              isVisible ? "translate-y-0 opacity-100" : "translate-y-8 opacity-0"
            }`}
          >
            <span className="block">AI made code</span>
            <span className="block">ship faster.</span>
            <span className="block">
              <span className="relative inline-block align-baseline">
                <span className="inline-flex whitespace-nowrap" key={wordIndex}>
                  {words[wordIndex].split("").map((char, i) =>
                    char === " " ? (
                      <span aria-hidden="true" className="inline-block w-[0.28em]" key={`${wordIndex}-${i}`} />
                    ) : (
                      <span
                        className="inline-block animate-char-in"
                        key={`${wordIndex}-${i}`}
                        style={{
                          animationDelay: `${i * 40}ms`,
                        }}
                      >
                        {char}
                      </span>
                    ),
                  )}
                </span>
                <span className="absolute -bottom-2 left-0 right-0 h-3 bg-foreground/10" />
              </span>{" "}
              lag.
            </span>
          </h1>
        </div>

        <div className="max-w-xl">
          <p
            className={`max-w-xl text-xl leading-relaxed text-muted-foreground transition-all delay-200 duration-700 lg:text-2xl ${
              isVisible ? "translate-y-0 opacity-100" : "translate-y-4 opacity-0"
            }`}
          >
            Pick a shipping window, set the cadence, and give Plot your team
            voice. Plot prepares source-backed docs updates, release notes,
            customer updates, and launch drafts so your team writes less before
            anything goes out.
          </p>

          <div
            className={`mt-9 flex flex-col items-start gap-4 transition-all delay-300 duration-700 sm:flex-row ${
              isVisible ? "translate-y-0 opacity-100" : "translate-y-4 opacity-0"
            }`}
          >
            <Button
              className="h-14 rounded-full bg-foreground px-8 text-base text-background hover:bg-foreground/90 group"
              size="lg"
            >
              Join waitlist
              <ArrowRight className="ml-2 size-4 transition-transform group-hover:translate-x-1" />
            </Button>
            <Button
              className="h-14 rounded-full border-foreground/20 px-8 text-base hover:bg-foreground/5"
              size="lg"
              variant="outline"
            >
              See how it works
            </Button>
          </div>
        </div>
        </div>
      </div>

    </section>
  );
}
