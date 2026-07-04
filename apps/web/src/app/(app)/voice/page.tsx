import { getVoiceWorkspace } from "@/lib/api-client";

export default function VoicePage() {
  const voice = getVoiceWorkspace();

  return (
    <div className="h-screen overflow-y-auto bg-[#f8fafc] p-8 dark:bg-[#18181b]">
      <div className="mx-auto max-w-5xl">
        <div>
          <div className="text-xs font-medium uppercase text-black/45 dark:text-white/45">Voice</div>
          <h1 className="mt-2 font-serif text-[38px] font-normal leading-tight tracking-normal text-black/90 dark:text-white/92">
            Writing guidance
          </h1>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-black/60 dark:text-white/60">
            Practical guidance for drafts created in this workspace.
          </p>
        </div>

        <div className="mt-8 grid gap-4 md:grid-cols-2">
          <GuidanceSection title="Preferred" items={voice.preferred} />
          <GuidanceSection title="Avoid" items={voice.avoid} />
          <GuidanceSection title="Examples" items={voice.examples} />
          <GuidanceSection title="Channel notes" items={voice.channelNotes} />
        </div>
      </div>
    </div>
  );
}

function GuidanceSection({ title, items }: { title: string; items: string[] }) {
  return (
    <section className="rounded-xl border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/5">
      <h2 className="text-sm font-semibold">{title}</h2>
      <ul className="mt-4 space-y-3 text-sm leading-6 text-black/65 dark:text-white/65">
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </section>
  );
}
