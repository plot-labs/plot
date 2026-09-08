import { ArrowUpRight, Check } from "lucide-react";

const sourceRows = [
  {
    number: "01",
    title: "Search projects by name",
    detail: "Pull request #142 · included in v2.4",
  },
  {
    number: "02",
    title: "Pin favorite projects",
    detail: "Pull request #148 · included in v2.4",
  },
];

export function FeaturesSection() {
  return (
    <section id="features" className="scroll-mt-24 py-20 lg:py-28">
      <div className="mx-auto max-w-[1400px] px-6 lg:px-12">
        <div className="mb-12 max-w-3xl">
          <p className="mb-4 font-mono text-xs uppercase tracking-wider text-muted-foreground">Trace the sentence</p>
          <h2 className="font-display text-4xl tracking-tight sm:text-5xl lg:text-6xl">Every claim keeps <br />its receipt.</h2>
          <p className="mt-6 max-w-2xl text-lg leading-8 text-muted-foreground">Plot keeps customer-facing wording connected to the release evidence behind it. Open a source before you approve the draft.</p>
        </div>
        <div className="grid border-y border-foreground/15 lg:grid-cols-[1.15fr_0.85fr]">
          <article className="py-10 lg:border-r lg:border-foreground/15 lg:py-14 lg:pr-16">
            <div className="mb-10 flex items-center justify-between gap-4">
              <div>
                <p className="font-mono text-[10px] uppercase tracking-[0.12em] text-muted-foreground">Customer-facing draft</p>
                <h3 className="mt-2 font-display text-3xl tracking-tight sm:text-4xl">Find projects faster.</h3>
              </div>
              <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground"><Check aria-hidden="true" className="size-4" />2 sources</span>
            </div>
            <div className="max-w-2xl space-y-7 text-xl leading-9 text-foreground/80 sm:text-2xl sm:leading-10">
              <p>Search your project list by name to get to the right project without scrolling.<sup className="ml-1 font-mono text-[11px] text-muted-foreground">01</sup></p>
              <p>Pin the projects you return to most. They stay at the top of your list, ready when you are.<sup className="ml-1 font-mono text-[11px] text-muted-foreground">02</sup></p>
            </div>
          </article>

          <aside aria-label="Example source trail" className="border-t border-foreground/15 py-10 lg:border-t-0 lg:py-14 lg:pl-16">
            <div className="mb-8 flex items-center justify-between">
              <p className="font-mono text-[10px] uppercase tracking-[0.12em] text-muted-foreground">Source trail · v2.4</p>
              <a
                href="#product-preview"
                className="inline-flex items-center gap-1 text-xs text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
              >
                Open preview
                <ArrowUpRight aria-hidden="true" className="size-3.5" />
              </a>
            </div>
            <ol>
              {sourceRows.map((source) => (
                <li key={source.number} className="grid grid-cols-[32px_1fr] gap-4 border-t border-foreground/10 py-5 last:border-b">
                  <span className="font-mono text-xs text-muted-foreground">{source.number}</span>
                  <div>
                    <p className="text-sm font-medium">{source.title}</p>
                    <p className="mt-1 text-sm text-muted-foreground">{source.detail}</p>
                  </div>
                </li>
              ))}
            </ol>
            <p className="mt-8 text-sm leading-6 text-muted-foreground">Example data from the same product preview above. The source link stays available while the wording changes.</p>
          </aside>
        </div>
      </div>
    </section>
  );
}
