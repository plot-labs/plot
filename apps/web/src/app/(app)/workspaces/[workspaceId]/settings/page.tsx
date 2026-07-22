import { Trash2, Upload, UserRound } from "lucide-react";
import Link from "next/link";

export default function WorkspaceSettingsPage() {
  return (
    <div className="h-screen overflow-y-auto bg-white px-8 py-14 dark:bg-[#111113]">
      <div className="mx-auto max-w-[1160px]">
        <header className="max-w-[760px]">
          <h1 className="font-serif text-[38px] font-normal leading-tight tracking-normal text-black/90 dark:text-white/92">
            Personal Workspace
          </h1>
          <p className="mt-2 max-w-[650px] text-[15px] leading-6 text-black/50 dark:text-white/50">
            Adjust the settings of your Personal workspace. A workspace groups your voice, sources, and sessions
            together so Plot drafts with the right context.
          </p>
        </header>

        <div className="mt-9 max-w-[720px] space-y-6">
          <section className="rounded-[14px] border border-black/[0.1] bg-white p-7 shadow-[0_1px_2px_rgb(15_23_42_/_0.03)] dark:border-white/10 dark:bg-white/[0.04]">
            <h2 className="text-[18px] font-semibold text-black/85 dark:text-white/88">General</h2>
            <p className="mt-2 max-w-[610px] text-[14px] leading-5 text-black/45 dark:text-white/45">
              Name, description, and icon for this workspace. The description is passed to Plot on every session so it
              can personalize its output.
            </p>

            <div className="mt-7 space-y-6">
              <label className="block">
                <span className="text-[14px] font-medium text-black/48 dark:text-white/48">Name</span>
                <input
                  readOnly
                  value="Personal"
                  className="mt-2 h-10 w-full rounded-[8px] border border-black/[0.12] bg-white px-3 text-[14px] text-black/80 shadow-sm outline-none transition focus:border-black/25 dark:border-white/12 dark:bg-[#111113] dark:text-white/84"
                />
              </label>

              <label className="block">
                <span className="text-[15px] font-semibold text-black/82 dark:text-white/86">Description</span>
                <span className="mt-1 block text-[13px] text-black/45 dark:text-white/45">
                  This context helps Plot personalize its drafts.
                </span>
                <textarea
                  placeholder="Information about your company, project, audience, etc."
                  className="mt-3 min-h-[96px] w-full resize-none rounded-[8px] border border-black/[0.12] bg-white px-3 py-3 text-[14px] text-black/78 shadow-sm outline-none transition placeholder:text-black/40 focus:border-black/25 dark:border-white/12 dark:bg-[#111113] dark:text-white/84 dark:placeholder:text-white/35"
                />
                <span className="mt-2 block text-right text-[13px] text-black/38 dark:text-white/38">0 / 4000</span>
              </label>

              <div>
                <div className="text-[15px] font-semibold text-black/38 dark:text-white/38">Icon</div>
                <div className="mt-3 flex items-center gap-5">
                  <div className="flex size-[70px] shrink-0 items-center justify-center rounded-[14px] bg-[#ef3f2c] font-serif text-[34px] font-semibold leading-none text-white shadow-sm">
                    P
                  </div>
                  <div className="space-y-1 text-[14px]">
                    <button type="button" className="block text-left font-medium text-black/82 dark:text-white/84">
                      Pick emoji
                    </button>
                    <button type="button" className="block text-left font-medium text-black/82 dark:text-white/84">
                      Change background color
                    </button>
                    <button
                      type="button"
                      className="flex items-center gap-1.5 text-left font-medium text-black/82 dark:text-white/84"
                    >
                      <Upload className="size-3.5" />
                      Upload image
                      <span className="font-normal text-black/38 dark:text-white/38">(recommended: 256 x 256px)</span>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </section>

          <section className="rounded-[14px] border border-black/[0.1] bg-white p-7 shadow-[0_1px_2px_rgb(15_23_42_/_0.03)] dark:border-white/10 dark:bg-white/[0.04]">
            <h2 className="text-[18px] font-semibold text-black/85 dark:text-white/88">Members</h2>
            <p className="mt-2 text-[14px] text-black/45 dark:text-white/45">
              Manage who has access to this workspace&apos;s shared voice and sources.
            </p>

            <div className="mt-7 flex items-center gap-4">
              <div className="flex size-9 items-center justify-center rounded-full border border-black/10 bg-white text-black/60 dark:border-white/12 dark:bg-white/8 dark:text-white/70">
                <UserRound className="size-4" />
              </div>
              <div className="min-w-0 flex-1">
                <div className="text-[14px] font-semibold text-black/82 dark:text-white/84">Seung-u Byeon</div>
                <div className="text-[13px] text-black/38 dark:text-white/38">qusseun@gmail.com</div>
              </div>
              <span className="rounded-[6px] bg-black/[0.06] px-2.5 py-1 text-[12px] font-medium text-black/42 dark:bg-white/10 dark:text-white/44">
                Owner
              </span>
            </div>

            <div className="mt-6 flex items-center justify-between gap-4 border-t border-black/[0.08] pt-5 dark:border-white/10">
              <p className="text-[14px] text-black/45 dark:text-white/45">
                Upgrade to a team plan to invite collaborators.
              </p>
              <button
                type="button"
                className="shrink-0 rounded-full border border-black/[0.12] bg-black/[0.03] px-3 py-1.5 text-[13px] font-medium text-black/48 transition hover:bg-black/[0.06] dark:border-white/12 dark:bg-white/8 dark:text-white/50 dark:hover:bg-white/12"
              >
                Upgrade to team
              </button>
            </div>
          </section>

          <section className="rounded-[14px] border border-black/[0.1] bg-white p-7 shadow-[0_1px_2px_rgb(15_23_42_/_0.03)] dark:border-white/10 dark:bg-white/[0.04]">
            <h2 className="text-[18px] font-semibold text-black/85 dark:text-white/88">GitHub</h2>
            <p className="mt-2 text-[14px] leading-5 text-black/45 dark:text-white/45">
              Connect repositories and import merged pull requests from Integrations.
            </p>
            <Link href="/integrations" className="mt-5 inline-flex rounded-full border border-black/[0.12] bg-black/[0.03] px-3 py-1.5 text-[13px] font-medium text-black/65 transition hover:bg-black/[0.06] dark:border-white/12 dark:bg-white/8 dark:text-white/70 dark:hover:bg-white/12">
              Manage GitHub in Integrations
            </Link>
          </section>

          <section className="rounded-[14px] border border-red-200 bg-white p-7 shadow-[0_1px_2px_rgb(15_23_42_/_0.03)] dark:border-red-500/35 dark:bg-white/[0.04]">
            <h2 className="text-[18px] font-semibold text-black/85 dark:text-white/88">Danger Zone</h2>
            <p className="mt-2 text-[14px] text-black/45 dark:text-white/45">
              These actions are permanent and cannot be undone.
            </p>

            <div className="mt-7 flex items-center justify-between gap-6">
              <div>
                <div className="text-[15px] font-semibold text-black/82 dark:text-white/84">Delete this workspace</div>
                <p className="mt-1 text-[14px] text-black/45 dark:text-white/45">
                  Permanently remove the workspace, all references, and session history.
                </p>
              </div>
              <button
                type="button"
                className="inline-flex shrink-0 items-center gap-2 rounded-full border border-red-300 bg-red-50 px-3 py-1.5 text-[13px] font-semibold text-red-600 transition hover:bg-red-100 dark:border-red-500/40 dark:bg-red-500/10 dark:text-red-300 dark:hover:bg-red-500/16"
              >
                <Trash2 className="size-4" />
                Delete workspace
              </button>
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
