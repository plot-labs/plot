"use client";

import { Check, ChevronDown, Plus } from "lucide-react";
import Image from "next/image";
import { useEffect, useRef } from "react";
import type { FormEvent, Dispatch, SetStateAction } from "react";

import type { SidebarWorkspace } from "@/components/layout/use-sidebar-workspace";
import { cn } from "@/lib/utils";

export type WorkspaceMenuItem = SidebarWorkspace & { mark: string; selected: boolean };

type WorkspaceSwitcherProps = {
  collapsed: boolean;
  workspaceLoading: boolean;
  currentWorkspace?: SidebarWorkspace;
  currentWorkspaceId: string | null;
  currentWorkspaceName: string;
  currentWorkspaceMark: string;
  workspaceItems: WorkspaceMenuItem[];
  workspaceMenuOpen: boolean;
  setWorkspaceMenuOpen: Dispatch<SetStateAction<boolean>>;
  onOpen: () => void;
  selectWorkspace: (id: string) => void;
  creatingWorkspace: boolean;
  setCreatingWorkspace: Dispatch<SetStateAction<boolean>>;
  workspaceName: string;
  setWorkspaceName: Dispatch<SetStateAction<string>>;
  workspaceCreateError: string | null;
  setWorkspaceCreateError: Dispatch<SetStateAction<string | null>>;
  isCreatingWorkspace: boolean;
  handleCreateWorkspace: (event: FormEvent<HTMLFormElement>) => void | Promise<void>;
};

export function WorkspaceSwitcher({
  collapsed,
  workspaceLoading,
  currentWorkspace,
  currentWorkspaceId,
  currentWorkspaceName,
  currentWorkspaceMark,
  workspaceItems,
  workspaceMenuOpen,
  setWorkspaceMenuOpen,
  onOpen,
  selectWorkspace,
  creatingWorkspace,
  setCreatingWorkspace,
  workspaceName,
  setWorkspaceName,
  workspaceCreateError,
  setWorkspaceCreateError,
  isCreatingWorkspace,
  handleCreateWorkspace,
}: WorkspaceSwitcherProps) {
  const workspaceMenuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!workspaceMenuOpen) return;

    function closeOnOutsidePointer(event: MouseEvent) {
      if (event.target instanceof Node && workspaceMenuRef.current && !workspaceMenuRef.current.contains(event.target)) {
        setWorkspaceMenuOpen(false);
      }
    }

    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") setWorkspaceMenuOpen(false);
    }

    document.addEventListener("mousedown", closeOnOutsidePointer);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("mousedown", closeOnOutsidePointer);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [setWorkspaceMenuOpen, workspaceMenuOpen]);

  useEffect(() => {
    if (!creatingWorkspace) return;
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setCreatingWorkspace(false);
        setWorkspaceCreateError(null);
      }
    }
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [creatingWorkspace, setCreatingWorkspace, setWorkspaceCreateError]);

  return (
    <>
      <div ref={workspaceMenuRef} className={cn("relative pb-4", collapsed ? "px-2" : "px-3")}>
        <div className={cn("mb-2 flex h-7 items-center pl-0.5", collapsed && "hidden")}>
          <div className="text-[11px] font-medium uppercase text-black/35 dark:text-white/35">Workspace</div>
        </div>

        <button
          type="button"
          onClick={() => {
            if (!workspaceMenuOpen) onOpen();
            setWorkspaceMenuOpen(!workspaceMenuOpen);
          }}
          disabled={workspaceLoading}
          aria-label={workspaceLoading ? "Loading workspace" : currentWorkspaceName}
          aria-busy={workspaceLoading}
          aria-expanded={workspaceMenuOpen}
          aria-haspopup="menu"
          className={cn(
            collapsed
              ? "mx-auto flex size-10 items-center justify-center rounded-[12px] border-0 px-0 text-left transition"
              : "flex h-9 w-full items-center gap-2 rounded-[8px] border px-2 text-left text-[13px] font-semibold transition",
            collapsed
              ? workspaceMenuOpen
                ? "bg-white text-black/82 shadow-sm dark:bg-white/10 dark:text-white"
                : "hover:bg-white/65 dark:hover:bg-white/10"
              : workspaceMenuOpen
                ? "border-black/20 bg-white text-black/82 shadow-sm dark:border-white/16 dark:bg-white/10 dark:text-white"
                : "border-black/[0.12] bg-white/40 text-black/76 hover:bg-white/65 dark:border-white/12 dark:bg-white/5 dark:text-white/78 dark:hover:bg-white/10",
            workspaceLoading && "cursor-wait opacity-80",
          )}
        >
          {workspaceLoading ? (
            <span aria-hidden="true" className="size-6 rounded-[7px] bg-black/[0.06] dark:bg-white/[0.08]" />
          ) : (
            <>
              <WorkspaceAvatar logoUrl={currentWorkspace?.logoUrl} mark={currentWorkspaceMark} variant="trigger" />
              <span className={cn("min-w-0 flex-1 truncate", collapsed && "sr-only")}>{currentWorkspaceName}</span>
              <ChevronDown
                className={cn(
                  "size-4 shrink-0 text-black/38 transition dark:text-white/40",
                  collapsed && "hidden",
                  workspaceMenuOpen && "rotate-180",
                )}
              />
            </>
          )}
        </button>

        {workspaceMenuOpen && (
          <div
            role="menu"
            aria-label="Workspace menu"
            className={cn(
              "absolute z-50 w-[228px] overflow-hidden rounded-[12px] border border-black/[0.08] bg-white text-[13px] text-black/76 shadow-[0_12px_30px_rgb(15_23_42_/_0.1)] dark:border-white/10 dark:bg-[#292a2f] dark:text-white/80",
              collapsed ? "left-2 top-[48px]" : "left-3 top-[76px]",
            )}
          >
            <div className="border-b border-black/[0.08] px-3 pb-1.5 pt-2.5 text-[12px] font-medium text-black/45 dark:border-white/10 dark:text-white/45">
              Workspaces
            </div>
            <div className="p-1">
              {workspaceItems.map((workspace) => (
                <button
                  key={workspace.id}
                  type="button"
                  role="menuitemradio"
                  aria-label={`${workspace.name} workspace`}
                  aria-checked={workspace.selected}
                  onClick={() => {
                    if (workspace.id === currentWorkspaceId) {
                      setWorkspaceMenuOpen(false);
                      return;
                    }
                    selectWorkspace(workspace.id);
                  }}
                  className={cn(
                    "flex h-9 w-full items-center gap-2 rounded-[8px] px-2 text-left font-medium transition hover:bg-black/[0.04] focus-visible:bg-black/[0.04] focus-visible:outline-none dark:hover:bg-white/10 dark:focus-visible:bg-white/10",
                    workspace.selected && "bg-black/[0.035] text-black/82 dark:bg-white/[0.07] dark:text-white/88",
                  )}
                >
                  <WorkspaceAvatar logoUrl={workspace.logoUrl} mark={workspace.mark} variant="menu" />
                  <span className="min-w-0 flex-1 truncate text-[13px]">{workspace.name}</span>
                  {workspace.selected && <Check className="size-4 shrink-0 text-black/55 dark:text-white/60" />}
                </button>
              ))}
            </div>
            <div className="border-t border-black/[0.08] p-1 dark:border-white/10">
              <button
                type="button"
                aria-label="Create workspace"
                onClick={() => {
                  setWorkspaceMenuOpen(false);
                  setCreatingWorkspace(true);
                  setWorkspaceName("");
                  setWorkspaceCreateError(null);
                }}
                className="flex h-9 w-full items-center gap-2.5 rounded-[8px] px-2 text-left text-[13px] font-medium text-black/65 transition hover:bg-black/[0.04] dark:text-white/65 dark:hover:bg-white/10"
              >
                <Plus className="size-5 shrink-0" />
                Create workspace
              </button>
            </div>
          </div>
        )}
      </div>

      {creatingWorkspace && (
        <div
          className="fixed inset-0 z-[100] flex items-center justify-center bg-black/20 p-4 backdrop-blur-[2px] dark:bg-black/45"
          onMouseDown={() => {
            setCreatingWorkspace(false);
            setWorkspaceCreateError(null);
          }}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="create-workspace-title"
            className="w-full max-w-[380px] rounded-[16px] border border-black/[0.1] bg-white p-6 text-black/85 shadow-[0_18px_50px_rgb(15_23_42_/_0.16)] dark:border-white/12 dark:bg-[#292a2f] dark:text-white/88"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <h2 id="create-workspace-title" className="font-display text-[24px] leading-none tracking-normal">Create workspace</h2>
            <p className="mt-2 text-[13px] leading-5 text-black/48 dark:text-white/50">Give your new workspace a name to get started.</p>
            <form onSubmit={(event) => void handleCreateWorkspace(event)} className="mt-6 space-y-4">
              <div className="space-y-2">
                <label htmlFor="workspace-name" className="text-[12px] font-medium text-black/68 dark:text-white/70">Workspace name</label>
                <input
                  id="workspace-name"
                  autoFocus
                  value={workspaceName}
                  onChange={(event) => setWorkspaceName(event.target.value)}
                  placeholder="e.g. Product"
                  maxLength={80}
                  className="h-10 w-full rounded-[9px] border border-black/12 bg-white px-3 text-sm text-black/80 outline-none placeholder:text-black/35 focus:border-black/30 focus:ring-2 focus:ring-black/[0.06] dark:border-white/14 dark:bg-white/[0.06] dark:text-white/88 dark:placeholder:text-white/35"
                />
                {workspaceCreateError && <p role="alert" className="text-[12px] text-red-600 dark:text-red-300">{workspaceCreateError}</p>}
              </div>
              <div className="flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setCreatingWorkspace(false);
                    setWorkspaceCreateError(null);
                  }}
                  className="h-9 rounded-[9px] px-3 text-[13px] font-medium text-black/55 transition hover:bg-black/[0.05] dark:text-white/58 dark:hover:bg-white/10"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={!workspaceName.trim() || isCreatingWorkspace}
                  className="h-9 rounded-[9px] bg-[#ef3f2c] px-3.5 text-[13px] font-medium text-white transition hover:bg-[#dc3424] disabled:cursor-not-allowed disabled:opacity-45"
                >
                  {isCreatingWorkspace ? "Creating…" : "Create workspace"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  );
}

export function WorkspaceAvatar({ logoUrl, mark, variant }: { logoUrl?: string | null; mark: string; variant: "menu" | "trigger" }) {
  const isMenu = variant === "menu";
  return (
    <span
      className={cn(
        "flex shrink-0 items-center justify-center overflow-hidden font-serif font-semibold leading-none",
        isMenu ? "size-6 rounded-[7px] text-[12px]" : "size-6 rounded-[7px] text-[14px]",
        logoUrl ? "bg-black/[0.04] dark:bg-white/10" : "bg-[#ef3f2c] text-white",
      )}
    >
      {logoUrl ? (
        <Image
          src={logoUrl}
          alt=""
          width={isMenu ? 28 : 24}
          height={isMenu ? 28 : 24}
          unoptimized
          className="size-full object-cover"
        />
      ) : mark}
    </span>
  );
}
