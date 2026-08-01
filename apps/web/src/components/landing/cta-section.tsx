"use client";

import { AnimatedPlotSignal } from "./animated-plot-signal";
import { WaitlistForm } from "./waitlist-form";

export function CtaSection() {
  return (
    <section
      className="relative py-24 lg:py-32 overflow-hidden"
      id="waitlist"
    >
      <div className="max-w-[1400px] mx-auto px-6 lg:px-12">
        <div className="landing-reveal relative border border-foreground">
          <div className="absolute inset-0 bg-[radial-gradient(600px_circle_at_50%_50%,rgba(0,0,0,0.15),transparent_40%)] opacity-10 pointer-events-none" />
          
          <div className="relative z-10 px-8 lg:px-16 py-16 lg:py-24">
            <div className="flex flex-col lg:flex-row items-center justify-between gap-12">
              {/* Left content */}
              <div className="flex-1">
	                <h2 className="text-4xl lg:text-7xl font-display tracking-tight mb-8 leading-[0.95]">
	                  Ship fast.
	                  <br />
	                  Write less.
	                </h2>

	                <p className="mb-10 max-w-xl text-xl leading-relaxed text-muted-foreground">
                  Connect one GitHub repository. When a published release arrives,
                  Plot prepares a source-cited changelog you can review, copy, or
                  download and publish on your existing channel.
                </p>

                <WaitlistForm />
              </div>

              {/* Right animation */}
              <div className="hidden lg:flex items-center justify-center w-[500px] h-[500px] -mr-16">
                <AnimatedPlotSignal />
              </div>
            </div>
          </div>

        </div>
      </div>
    </section>
  );
}
