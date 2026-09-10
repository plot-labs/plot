export function HowItWorksSection() {
  const steps = [
    ["01", "Assess what matters", "Plot starts from a published GitHub release and identifies the changes customers need to know."],
    ["02", "Shape the customer update", "Open the artifact beside the conversation, refine the customer-facing wording, and check supporting evidence when needed."],
    ["03", "Approve the final update", "Publish to your public changelog or export Markdown when the release note is ready."],
  ];

  return (
    <section id="how-it-works" className="scroll-mt-24 border-y border-foreground/10 bg-[#eeefeb] py-20 lg:py-28">
      <div className="mx-auto grid max-w-[1400px] gap-10 px-6 lg:grid-cols-2 lg:gap-24 lg:px-12">
        <div><p className="mb-5 font-sans text-xs uppercase tracking-wider text-muted-foreground">One release boundary. One review.</p><h2 className="font-display text-4xl leading-tight sm:text-5xl lg:text-6xl">From shipped work <br />to approved words.</h2></div>
        <ol className="border-t border-foreground/15">
          {steps.map(([number, title, description]) => (
            <li key={number} className="grid grid-cols-[40px_1fr] gap-4 border-b border-foreground/15 py-6">
              <span className="font-sans text-xs text-muted-foreground">{number}</span>
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
