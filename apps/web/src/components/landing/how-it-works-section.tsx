export function HowItWorksSection() {
  const steps = [
    ["01", "Set the release boundary", "Plot starts from a published GitHub release, so the draft stays inside what actually shipped."],
    ["02", "Review claims with their sources", "Open the artifact beside the conversation. Check a claim against its pull request, then edit the customer-facing wording."],
    ["03", "Approve the final update", "Publish to your public changelog or export Markdown when the release note is ready."],
  ];

  return (
    <section id="how-it-works" className="scroll-mt-24 border-y border-foreground/10 bg-[#eeefeb] py-20 lg:py-28">
      <div className="mx-auto grid max-w-[1400px] gap-10 px-6 lg:grid-cols-2 lg:gap-24 lg:px-12">
        <div><p className="mb-5 font-mono text-xs uppercase tracking-wider text-muted-foreground">One release boundary. One review.</p><h2 className="font-display text-4xl leading-tight sm:text-5xl lg:text-6xl">From shipped work <br />to approved words.</h2></div>
        <ol className="border-t border-foreground/15">
          {steps.map(([number, title, description]) => (
            <li key={number} className="grid grid-cols-[40px_1fr] gap-4 border-b border-foreground/15 py-6">
              <span className="font-mono text-xs text-muted-foreground">{number}</span>
              <div>
                <h3 className="text-base font-medium">{title}</h3>
                <p className="mt-2 max-w-xl text-base leading-7 text-muted-foreground">{description}</p>
              </div>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}
