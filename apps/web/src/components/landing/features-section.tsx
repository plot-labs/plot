import { FileCheck2, GitPullRequest, Send } from "lucide-react";

const features = [
  { number: "01", Icon: GitPullRequest, title: "Start with what shipped.", description: "Connect a GitHub repository. Plot uses the published release range to collect the changes behind your update." },
  { number: "02", Icon: FileCheck2, title: "Check the story.", description: "Read the draft alongside its sources. Inspect the evidence behind a claim, then edit the wording for your customers." },
  { number: "03", Icon: Send, title: "Put it in their hands.", description: "Publish to your public changelog or export Markdown. You decide when the draft is ready to go live." },
];

export function FeaturesSection() {
  return (
    <section id="features" className="scroll-mt-24 py-20 lg:py-28">
      <div className="mx-auto max-w-[1400px] px-6 lg:px-12">
        <div className="mb-12 max-w-2xl">
          <p className="mb-4 font-mono text-xs uppercase tracking-wider text-muted-foreground">From release to reader</p>
          <h2 className="font-display text-4xl tracking-tight sm:text-5xl lg:text-6xl">A shorter path to<br />“here’s what’s new.”</h2>
        </div>
        <div className="grid border-y border-foreground/15 md:grid-cols-3">
          {features.map(({number, Icon, title, description}) => (
            <article key={number} className="border-b border-foreground/10 py-8 last:border-b-0 md:border-r md:border-b-0 md:px-7 md:first:pl-0 md:last:border-r-0 md:last:pr-0">
              <div className="mb-8 flex items-center justify-between"><span className="font-mono text-xs text-muted-foreground">{number}</span><Icon aria-hidden="true" className="size-5 text-muted-foreground" /></div>
              <h3 className="mb-4 font-display text-3xl tracking-tight">{title}</h3>
              <p className="max-w-sm text-base leading-7 text-muted-foreground">{description}</p>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
