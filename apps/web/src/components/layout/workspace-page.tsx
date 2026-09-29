import type { ReactNode } from "react";
import { RefreshCw } from "lucide-react";

export const workspacePageClass = "h-full overflow-y-auto bg-[#f7f8fa] dark:bg-[#18191d]";
export const workspaceSectionClass = "mx-auto min-h-full w-full max-w-[760px] bg-[#f7f8fa] dark:bg-[#18191d] lg:h-full lg:overflow-y-auto";
export const workspaceSearchClass = "mt-5 flex h-10 items-center gap-2.5 rounded-[9px] border border-black/10 bg-white px-3 text-[12px] text-black/40 transition focus-within:border-black/20 focus-within:ring-2 focus-within:ring-black/[0.04] dark:border-white/12 dark:bg-white/[0.04] dark:text-white/42";
export const workspaceSearchInputClass = "min-w-0 flex-1 bg-transparent text-[13px] text-black/75 outline-none placeholder:text-black/35 dark:text-white/80 dark:placeholder:text-white/35";
export const workspaceIconButtonClass = "inline-flex size-9 items-center justify-center rounded-[9px] text-black/45 transition hover:bg-black/[0.04] hover:text-black/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:cursor-wait disabled:opacity-45 dark:text-white/48 dark:hover:bg-white/10 dark:hover:text-white/75";
export const workspaceTextButtonClass = "inline-flex h-8 items-center rounded-[7px] px-2.5 text-[12px] font-medium text-black/55 transition hover:bg-black/[0.04] hover:text-black/78 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:opacity-40 dark:text-white/58 dark:hover:bg-white/10 dark:hover:text-white/82";
export const workspacePrimaryButtonClass = "inline-flex h-8 items-center rounded-full bg-[#252a30] px-3.5 text-[12px] font-semibold text-white transition hover:bg-[#171a1e] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:opacity-40 dark:bg-white dark:text-[#18191b] dark:hover:bg-white/90";
export const workspaceNoticeClass = "rounded-[9px] border border-black/10 bg-white px-3 py-2.5 text-[12px] text-black/58 dark:border-white/12 dark:bg-white/[0.04] dark:text-white/60";
export const workspaceListClass = "divide-y divide-black/[0.07] border-y border-black/[0.07] dark:divide-white/[0.08] dark:border-white/[0.08]";

export function WorkspaceHeader({
  actions,
  children,
  description,
  id,
  title,
  variant = "section",
}: {
  actions?: ReactNode;
  children?: ReactNode;
  description: string;
  id: string;
  title: string;
  variant?: "section" | "standalone";
}) {
  return (
    <header className={variant === "standalone" ? "pt-8" : "border-b border-black/[0.08] px-6 pb-5 pt-8 dark:border-white/10"}>
      <div className={`flex items-start justify-between gap-4${variant === "standalone" ? " flex-wrap" : ""}`}>
        <div className="min-w-0">
          <h1 id={id} className="font-display text-[32px] font-normal leading-none tracking-[-0.025em] text-black/90 dark:text-white/92">
            {title}
          </h1>
          <p className="mt-2 text-[13px] leading-5 text-black/48 dark:text-white/50">{description}</p>
        </div>
        {actions && <div className="flex shrink-0 items-center gap-1.5">{actions}</div>}
      </div>
      {children}
    </header>
  );
}

export function WorkspaceEmptyState({
  title,
  description,
  icon,
}: {
  title: string;
  description: string;
  icon?: ReactNode;
}) {
  return (
    <div className="px-6 py-10 text-center">
      {icon && <div className="mb-3 flex justify-center text-black/25 dark:text-white/30">{icon}</div>}
      <p className="text-[13px] font-medium text-black/58 dark:text-white/62">{title}</p>
      <p className="mt-1 text-[12px] leading-5 text-black/40 dark:text-white/42">{description}</p>
    </div>
  );
}

export function WorkspaceErrorNotice({ message, onRetry, retrying = false }: {
  message: string;
  onRetry: () => void;
  retrying?: boolean;
}) {
  return (
    <div role="alert" className={`${workspaceNoticeClass} flex flex-wrap items-center justify-between gap-3`}>
      <span className="min-w-0 flex-1 basis-48 leading-5">{message}</span>
      <button type="button" onClick={onRetry} disabled={retrying} aria-label={retrying ? "Retrying…" : "Retry"} title="Retry" className="inline-flex size-7 shrink-0 items-center justify-center rounded-[7px] text-black/45 transition hover:bg-black/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:cursor-wait disabled:opacity-45 dark:text-white/48 dark:hover:bg-white/10">
        <RefreshCw className="size-3.5" aria-hidden="true" />
      </button>
    </div>
  );
}
