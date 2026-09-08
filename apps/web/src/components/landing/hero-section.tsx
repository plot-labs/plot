import { ArrowDown, ArrowRight } from "lucide-react";
import { ProductPreview } from "./product-preview";

export function HeroSection() {
  return (
    <section className="border-b border-foreground/10 pt-32 pb-16 lg:pt-40 lg:pb-24">
      <div className="mx-auto max-w-[1400px] px-6 lg:px-12">
        <div className="mx-auto mb-14 max-w-3xl text-center lg:mb-16">
          <p className="mb-6 font-mono text-[11px] uppercase tracking-[0.12em] text-muted-foreground">From shipped work to published words</p>
          <h1 className="font-display text-5xl leading-[1.02] tracking-tight sm:text-7xl lg:text-[88px]">Shipped work.<br />A story you can publish.</h1>
          <p className="mx-auto mt-6 max-w-xl text-base leading-7 text-muted-foreground sm:text-lg">Turn your GitHub releases into clear, source-cited changelogs. Work with Plot in chat. Review and refine the draft right beside it.</p>
          <div className="mt-8 flex flex-wrap items-center justify-center gap-5">
            <a href="#waitlist" className="inline-flex min-h-12 items-center gap-3 rounded-lg bg-foreground px-6 text-sm font-medium text-background hover:bg-foreground/85 focus-visible:outline-2 focus-visible:outline-offset-4">Join waitlist <ArrowRight aria-hidden="true" className="size-4" /></a>
            <a href="#product-preview" className="inline-flex min-h-12 items-center gap-2 text-sm underline-offset-4 hover:underline">Explore the workspace <ArrowDown aria-hidden="true" className="size-4" /></a>
          </div>
        </div>
        <ProductPreview />
      </div>
    </section>
  );
}
