import Link from "next/link";
import { AutonomyHomeWorkspace } from "./autonomy-home";
import { RoutinesWorkspace } from "@/features/routines/routines-workspace";

export function AutomationWorkspace({ activity = false }: { activity?: boolean }) {
  return <div className="flex h-full min-h-0 flex-col">
    <nav aria-label="Automation views" className="flex shrink-0 gap-2 border-b border-black/10 bg-[#f8fafc] px-6 py-3 text-sm dark:border-white/10 dark:bg-[#18181b] lg:px-12">
      <Link className="rounded-lg px-3 py-2 aria-[current=page]:bg-black/5 dark:aria-[current=page]:bg-white/10" href="/automation" aria-current={!activity ? "page" : undefined}>Automations</Link>
      <Link className="rounded-lg px-3 py-2 aria-[current=page]:bg-black/5 dark:aria-[current=page]:bg-white/10" href="/automation/activity" aria-current={activity ? "page" : undefined}>Activity</Link>
    </nav>
    <div className="min-h-0 flex-1 overflow-y-auto">{activity ? <AutonomyHomeWorkspace view="activity" /> : <RoutinesWorkspace />}</div>
  </div>;
}
