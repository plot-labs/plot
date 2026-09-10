import { WaitlistForm } from "./waitlist-form";

export function CtaSection() {
  return (
    <section id="waitlist" className="scroll-mt-24 py-20 lg:py-28">
      <div className="mx-auto grid max-w-[1200px] items-start gap-12 px-6 lg:grid-cols-[1fr_1.15fr] lg:gap-24 lg:px-12">
        <div>
          <p className="mb-5 font-sans text-xs uppercase tracking-wider text-muted-foreground">
            Get early access
          </p>
          <h2 className="font-display text-5xl leading-[1.02] tracking-tight lg:text-7xl">
            Make the next release <br />clear to customers.
          </h2>
          <p className="mt-6 max-w-sm text-lg leading-8 text-muted-foreground">
            Bring your next published release to Plot. Focus the update on what
            matters, refine the wording, and publish when it is right.
          </p>
          <p className="mt-8 text-sm text-muted-foreground">
            Join the waitlist. We’ll be in touch when early access opens.
          </p>
        </div>
        <div className="min-w-0 border-t border-foreground/15 pt-6 lg:pt-8">
          <WaitlistForm />
        </div>
      </div>
    </section>
  );
}
