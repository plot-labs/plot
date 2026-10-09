import { createPlotApiClient } from "@plot/api-client";

export { PlotApiError } from "@plot/api-client";

export type {
  Artifact,
  ArtifactSummary,
  ChatModel,
  ChatModelCapability,
  ChatReasoningEffort,
  ContentBrief,
  ContentProfile,
  CreditUsageEvent,
  SourceReference,
  GitHubAccessCheckTrigger,
  GitHubAvailableInstallation,
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
  WorkspaceCreditOverview,
  WorkspaceCheckout,
  WorkspaceSummary,
} from "@plot/api-client";

export const getSelectedWorkspaceId = () => typeof window === "undefined"
  ? null
  : window.localStorage.getItem("plot.workspaceId");

export const plotApiClient = createPlotApiClient({ baseUrl: "/api/plot", workspaceId: getSelectedWorkspaceId });
