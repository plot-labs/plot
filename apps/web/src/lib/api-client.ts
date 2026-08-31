import { createPlotApiClient } from "@plot/api-client";

export { PlotApiError } from "@plot/api-client";

export type {
  ArtifactHistoryDetail,
  Artifact,
  ArtifactSummary,
  SourceReference,
  GitHubAccessCheckTrigger,
  GitHubConnection,
  GitHubImport,
  GitHubReleaseActivity,
  GitHubReleaseDraftStatus,
  GitHubRepository,
  GitHubRepositoryMonitoring,
  PlotApiClient,
  Routine,
  RoutineAgentRunDetail,
  RoutineCadence,
  WorkSessionSummary,
  WorkspaceSummary,
} from "@plot/api-client";

export const getSelectedWorkspaceId = () => typeof window === "undefined"
  ? null
  : window.localStorage.getItem("plot.workspaceId");

export const plotApiClient = createPlotApiClient({ baseUrl: "/api/plot", workspaceId: getSelectedWorkspaceId });
