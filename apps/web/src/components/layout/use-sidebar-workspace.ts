"use client";

import { useEffect, useState } from "react";
import type { FormEvent } from "react";

import { plotApiClient } from "@/lib/api-client";

export type SidebarWorkspace = {
  id: string;
  name: string;
  slug: string;
  logoUrl: string | null;
  role: string;
};

export type SidebarAccount = {
  user: { id: string; email: string; displayName: string };
  workspaces: SidebarWorkspace[];
  defaultWorkspaceId: string;
};

export function useSidebarWorkspace() {
  const [account, setAccount] = useState<SidebarAccount | null>(null);
  const [selectedWorkspaceId, setSelectedWorkspaceId] = useState<string | null>(null);
  const [workspaceMenuOpen, setWorkspaceMenuOpen] = useState(false);
  const [creatingWorkspace, setCreatingWorkspace] = useState(false);
  const [workspaceName, setWorkspaceName] = useState("");
  const [workspaceCreateError, setWorkspaceCreateError] = useState<string | null>(null);
  const [isCreatingWorkspace, setIsCreatingWorkspace] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/plot/me", { cache: "no-store", headers: { Accept: "application/json" } })
      .then((response) => response.ok ? response.json() as Promise<SidebarAccount> : null)
      .then((value) => {
        if (cancelled || !value) return;
        setAccount(value);
        const savedId = window.localStorage.getItem("plot.workspaceId");
        const savedWorkspace = savedId ? value.workspaces.find((item) => item.id === savedId) : undefined;
        const resolvedWorkspaceId = savedWorkspace?.id ?? value.defaultWorkspaceId ?? value.workspaces[0]?.id ?? null;
        if (resolvedWorkspaceId) window.localStorage.setItem("plot.workspaceId", resolvedWorkspaceId);
        else window.localStorage.removeItem("plot.workspaceId");
        setSelectedWorkspaceId(resolvedWorkspaceId);
        if (resolvedWorkspaceId !== savedId) {
          window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: resolvedWorkspaceId } }));
        }
      })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    function updateWorkspace(event: Event) {
      const workspace = (event as CustomEvent<{ id?: string; name?: string; logoUrl?: string | null }>).detail;
      if (!workspace?.id) return;
      setAccount((current) => current
        ? {
          ...current,
          workspaces: current.workspaces.map((item) => item.id === workspace.id
            ? { ...item, name: workspace.name ?? item.name, logoUrl: workspace.logoUrl === undefined ? item.logoUrl : workspace.logoUrl }
            : item),
        }
        : current);
    }

    window.addEventListener("plot:workspace-updated", updateWorkspace);
    return () => window.removeEventListener("plot:workspace-updated", updateWorkspace);
  }, []);

  const currentWorkspace = account?.workspaces.find((item) => item.id === selectedWorkspaceId)
    ?? account?.workspaces.find((item) => item.id === account.defaultWorkspaceId)
    ?? account?.workspaces[0];
  const currentWorkspaceId = currentWorkspace?.id ?? selectedWorkspaceId;
  const currentWorkspaceName = currentWorkspace?.name ?? "Workspace";
  const currentWorkspaceMark = currentWorkspaceName.slice(0, 1).toUpperCase();
  const workspaceItems = account?.workspaces.map((item) => ({
    ...item,
    mark: item.name.slice(0, 1).toUpperCase(),
    selected: item.id === currentWorkspaceId,
  })) ?? [];

  function selectWorkspace(id: string) {
    window.localStorage.setItem("plot.workspaceId", id);
    setSelectedWorkspaceId(id);
    setWorkspaceMenuOpen(false);
    window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id } }));
  }

  async function handleCreateWorkspace(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const name = workspaceName.trim();
    if (!name || isCreatingWorkspace) return;

    setIsCreatingWorkspace(true);
    setWorkspaceCreateError(null);
    try {
      const workspace = await plotApiClient.createWorkspace({ name });
      window.localStorage.setItem("plot.workspaceId", workspace.id);
      setAccount((current) => current
        ? { ...current, workspaces: [...current.workspaces, { ...workspace, role: workspace.role ?? "OWNER" }] }
        : current);
      setSelectedWorkspaceId(workspace.id);
      setWorkspaceMenuOpen(false);
      setCreatingWorkspace(false);
      setWorkspaceName("");
      window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: workspace.id } }));
    } catch {
      setWorkspaceCreateError("Workspace could not be created.");
    } finally {
      setIsCreatingWorkspace(false);
    }
  }

  return {
    account,
    currentWorkspace,
    currentWorkspaceId,
    currentWorkspaceName,
    currentWorkspaceMark,
    workspaceItems,
    workspaceLoading: !account,
    workspaceMenuOpen,
    setWorkspaceMenuOpen,
    creatingWorkspace,
    setCreatingWorkspace,
    workspaceName,
    setWorkspaceName,
    workspaceCreateError,
    setWorkspaceCreateError,
    isCreatingWorkspace,
    selectWorkspace,
    handleCreateWorkspace,
  };
}
