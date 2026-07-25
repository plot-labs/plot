import Image from "next/image";
import Link from "next/link";

type LegalSection = {
  title: string;
  paragraphs?: string[];
  items?: string[];
};

type LegalPageProps = {
  eyebrow: string;
  title: string;
  updatedAt: string;
  introduction: string;
  sections: LegalSection[];
};

export function LegalPage({
  eyebrow,
  title,
  updatedAt,
  introduction,
  sections,
}: LegalPageProps) {
  return (
    <main className="min-h-dvh bg-[#faf9f6] px-6 py-8 text-[#171512] sm:px-10 lg:px-16">
      <nav className="mx-auto flex max-w-5xl items-center justify-between">
        <Link href="/" className="flex items-center gap-2" aria-label="Plot home">
          <Image src="/plot-logo.png" alt="" width={24} height={24} className="size-6" />
          <span className="font-display text-2xl leading-none">Plot</span>
        </Link>
        <Link
          href="/"
          className="text-sm text-black/55 transition-colors hover:text-black"
        >
          Back to Plot
        </Link>
      </nav>

      <article className="mx-auto max-w-3xl py-20 sm:py-28">
        <header className="border-b border-black/10 pb-12">
          <p className="font-mono text-xs uppercase tracking-[0.18em] text-black/45">
            {eyebrow}
          </p>
          <h1 className="mt-4 font-display text-5xl tracking-[-0.03em] sm:text-6xl">
            {title}
          </h1>
          <p className="mt-5 text-sm text-black/45">Last updated {updatedAt}</p>
          <p className="mt-8 max-w-2xl text-lg leading-8 text-black/65">{introduction}</p>
        </header>

        <div className="space-y-12 py-12">
          {sections.map((section) => (
            <section key={section.title}>
              <h2 className="font-display text-2xl">{section.title}</h2>
              {section.paragraphs?.map((paragraph) => (
                <p key={paragraph} className="mt-4 leading-7 text-black/65">
                  {paragraph}
                </p>
              ))}
              {section.items && (
                <ul className="mt-4 list-disc space-y-3 pl-5 leading-7 text-black/65">
                  {section.items.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              )}
            </section>
          ))}
        </div>

        <footer className="flex flex-wrap gap-x-5 gap-y-2 border-t border-black/10 pt-8 text-sm text-black/50">
          <Link href="/privacy" className="transition-colors hover:text-black">
            Privacy
          </Link>
          <Link href="/terms" className="transition-colors hover:text-black">
            Terms
          </Link>
          <a
            href="mailto:hello@useplot.xyz"
            className="transition-colors hover:text-black"
          >
            Contact
          </a>
        </footer>
      </article>
    </main>
  );
}
