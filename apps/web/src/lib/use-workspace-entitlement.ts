"use client";

import { useEffect, useState } from "react";

import type { WorkspaceCapabilities, WorkspaceSummary } from "@plot/api-client";
import { plotApiClient } from "@/lib/api-client";

const FULL_CAPABILITIES: WorkspaceCapabilities = {
  generate: true,
  edit: true,
  publish: true,
  export: true,
  configure: true,
  unpublish: true,
};

export type WorkspaceEntitlement = {
	plan: string;
	entitlementStatus: string;
	accessMode: WorkspaceSummary["accessMode"];
	capabilities: WorkspaceCapabilities;
};

export function useWorkspaceEntitlement(): WorkspaceEntitlement | null {
  const [entitlement, setEntitlement] = useState<WorkspaceEntitlement | null>(null);

  useEffect(() => {
    function load() {
      const workspaceId = typeof window === "undefined" ? null : window.localStorage.getItem("plot.workspaceId");
      if (!workspaceId || typeof plotApiClient.getWorkspace !== "function") return;
      void plotApiClient.getWorkspace(workspaceId)
        .then((workspace) => {
          setEntitlement({
				plan: workspace.plan,
				entitlementStatus: workspace.entitlementStatus,
				accessMode: workspace.accessMode,
				capabilities: workspace.capabilities ?? FULL_CAPABILITIES,
          });
        })
        .catch(() => undefined);
    }

    load();
    window.addEventListener("plot:workspace-changed", load);
    return () => window.removeEventListener("plot:workspace-changed", load);
  }, []);

  return entitlement;
}
