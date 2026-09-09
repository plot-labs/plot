import { ArrowDown, ArrowRight } from "lucide-react";
import { ProductPreview } from "./product-preview";

export function HeroSection() {
  return (
    <section className="border-b border-foreground/10 pt-32 pb-16 lg:pt-40 lg:pb-24">
      <div className="mx-auto max-w-[1400px] px-6 lg:px-12">
        <div className="mx-auto mb-14 max-w-4xl text-center lg:mb-16">
          <p className="mb-6 font-mono text-[11px] uppercase tracking-[0.12em] text-muted-foreground">Shipped work → customer awareness</p>
          <h1 className="font-display text-5xl leading-[1.02] tracking-tight sm:text-7xl lg:text-[88px]">Make every release <br />land.</h1>
          <p className="mx-auto mt-6 max-w-2xl text-base leading-7 text-muted-foreground sm:text-lg">Plot identifies the shipped changes customers need to know, turns them into a customer-ready changelog, and keeps you in control of publication. Supporting evidence stays available when you review.</p>
          <div className="mt-8 flex flex-wrap items-center justify-center gap-5">
            <a href="#waitlist" className="inline-flex min-h-12 items-center gap-3 rounded-lg bg-foreground px-6 text-sm font-medium text-background hover:bg-foreground/85 focus-visible:outline-2 focus-visible:outline-offset-4">Join waitlist <ArrowRight aria-hidden="true" className="size-4" /></a>
            <a href="#product-preview" className="inline-flex min-h-12 items-center gap-2 text-sm underline-offset-4 hover:underline">Explore the workspace <ArrowDown aria-hidden="true" className="size-4" /></a>
          </div>
        </div>
        <ProductPreview />
        <dl className="mt-8 grid border-y border-foreground/15 sm:grid-cols-3">
          {[
            ["Outcome", "Customers know what changed"],
            ["Judgment", "Only meaningful changes"],
            ["Final control", "You approve publication"],
          ].map(([term, description]) => (
            <div key={term} className="border-b border-foreground/10 py-5 last:border-b-0 sm:border-r sm:border-b-0 sm:px-6 sm:first:pl-0 sm:last:border-r-0 sm:last:pr-0">
              <dt className="font-mono text-[10px] uppercase tracking-[0.12em] text-muted-foreground">{term}</dt>
              <dd className="mt-2 text-sm font-medium">{description}</dd>
            </div>
          ))}
        </dl>
      </div>
    </section>
  );
}
