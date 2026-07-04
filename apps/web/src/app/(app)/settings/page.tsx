import { getSettingsWorkspace } from "@/lib/api-client";

export default function SettingsPage() {
  const { workspace, members } = getSettingsWorkspace();

  return (
    <div className="h-screen overflow-y-auto bg-[#f8fafc] p-8 dark:bg-[#18181b]">
      <div className="mx-auto max-w-4xl">
        <div>
          <div className="text-xs font-medium uppercase text-black/45 dark:text-white/45">Settings</div>
          <h1 className="mt-2 text-3xl font-semibold">Workspace settings</h1>
          <p className="mt-2 text-sm text-black/60 dark:text-white/60">
            Dev workspace configuration for the product shell.
          </p>
        </div>

        <div className="mt-8 space-y-4">
          <section className="rounded-xl border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/5">
            <h2 className="text-sm font-semibold">Workspace</h2>
            <dl className="mt-4 grid gap-3 text-sm">
              <div className="flex justify-between gap-4">
                <dt className="text-black/50 dark:text-white/50">Name</dt>
                <dd>{workspace.name}</dd>
              </div>
              <div className="flex justify-between gap-4">
                <dt className="text-black/50 dark:text-white/50">Environment</dt>
                <dd>{workspace.environment}</dd>
              </div>
              <div className="flex justify-between gap-4">
                <dt className="text-black/50 dark:text-white/50">Source connection</dt>
                <dd>{workspace.connectionLabel}</dd>
              </div>
            </dl>
          </section>

          <section className="rounded-xl border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/5">
            <h2 className="text-sm font-semibold">Members</h2>
            <div className="mt-4 flex flex-wrap gap-2">
              {members.map((member) => (
                <span key={member} className="rounded-full bg-black/5 px-3 py-1.5 text-sm dark:bg-white/10">
                  {member}
                </span>
              ))}
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
