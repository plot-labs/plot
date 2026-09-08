export function HowItWorksSection() {
  return (
    <section id="how-it-works" className="scroll-mt-24 border-y border-foreground/10 bg-[#eeefeb] py-20 lg:py-28">
      <div className="mx-auto grid max-w-[1400px] gap-10 px-6 lg:grid-cols-2 lg:gap-24 lg:px-12">
        <div><p className="mb-5 font-mono text-xs uppercase tracking-wider text-muted-foreground">A draft is the beginning</p><h2 className="font-display text-4xl leading-tight sm:text-5xl lg:text-6xl">Keep the context.<br />Make the final call.</h2></div>
        <div className="max-w-xl space-y-6 text-lg leading-8 text-muted-foreground"><p>Connect a GitHub repository. When a published release arrives, Plot gathers the changes and prepares a changelog for review.</p><p>Open the artifact beside your conversation. Check its sources, refine the wording, and publish to your public changelog or export Markdown when you’re ready.</p><p className="text-sm font-medium text-foreground">Nothing goes live until you publish.</p></div>
      </div>
    </section>
  );
}
