import { createPlotApiClient } from "@plot/api-client";

export { PlotApiError } from "@plot/api-client";

export type {
  ArtifactHistoryDetail,
  Artifact,
  ArtifactSummary,
  ChatModel,
  ChatModelCapability,
  ChatReasoningEffort,
  ContentBrief,
  ContentProfile,
  SourceReference,
  GitHubAccessCheckTrigger,
  GitHubConnection,
  GitHubImport,
  GitHubReleaseActivity,
  GitHubReleaseDraftStatus,
  GitHubReleaseRangeInput,
  GitHubRepository,
  GitHubRepositoryMonitoring,
  PlotApiClient,
  Routine,
  RoutineAgentRunDetail,
  RoutineCadence,
  UpdateContentProfileInput,
  WorkSessionSummary,
  WorkspaceCapabilities,
  WorkspaceSummary,
} from "@plot/api-client";

export const getSelectedWorkspaceId = () => typeof window === "undefined"
  ? null
  : window.localStorage.getItem("plot.workspaceId");

export const plotApiClient = createPlotApiClient({ baseUrl: "/api/plot", workspaceId: getSelectedWorkspaceId });
