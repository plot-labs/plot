import {
  devContext,
  type DraftDocument,
  type ReferenceDocument,
  type SessionMessage,
} from "@/lib/dev-context";
import { createPlotApiClient } from "@plot/api-client";

export { PlotApiError } from "@plot/api-client";

export type {
  ContentPack,
  ContentPackSummary,
  CreateGenerationInput,
  GenerationRun,
  GenerationReference,
  GitHubConnection,
  GitHubImport,
  GitHubRepository,
  PlotApiClient,
  WritingBlock,
  WorkspaceSummary,
} from "@plot/api-client";

export const getSelectedWorkspaceId = () => typeof window === "undefined"
  ? devContext.workspace.id
  : window.localStorage.getItem("plot.workspaceId") ?? devContext.workspace.id;

export const plotApiClient = createPlotApiClient({ baseUrl: "/api/plot", workspaceId: getSelectedWorkspaceId });

export type {
  DraftDocument,
  ReferenceDocument,
  SessionMessage,
  WorkSession,
} from "@/lib/dev-context";

export type SelectedDocument =
  | { kind: "draft"; document: DraftDocument }
  | { kind: "reference"; document: ReferenceDocument };

export function getProductShellData() {
  return {
    workspace: devContext.workspace,
    sessions: devContext.sessions,
  };
}

export function getSessionsWorkspace() {
  return {
    workspace: devContext.workspace,
    sessions: devContext.sessions,
    drafts: devContext.drafts,
    references: devContext.references,
  };
}

export function getSourcesWorkspace() {
  return {
    references: devContext.references,
    drafts: devContext.drafts,
  };
}

export function getPacksWorkspace() {
  return {
    packs: devContext.packs,
    drafts: devContext.drafts,
    references: devContext.references,
  };
}

export function getVoiceWorkspace() {
  return devContext.voice;
}

export function getSettingsWorkspace() {
  return {
    workspace: devContext.workspace,
    members: devContext.members,
  };
}

export function getSelectedDocument(documentId: string): SelectedDocument | null {
  const draft = devContext.drafts.find((item) => item.id === documentId);

  if (draft) {
    return { kind: "draft", document: draft };
  }

  const reference = devContext.references.find((item) => item.id === documentId);

  if (reference) {
    return { kind: "reference", document: reference };
  }

  return null;
}

export function createDemoAgentReply(message: string, count: number): SessionMessage {
  const asksForAnotherDraft =
    message.toLowerCase().includes("customer") ||
    message.toLowerCase().includes("launch") ||
    message.toLowerCase().includes("draft");

  return {
    id: `agent-reply-${count}`,
    role: "agent",
    author: "Plot",
    timestamp: "Now",
    content: asksForAnotherDraft
      ? "I can add another draft to this work. Open an existing draft on the right, or ask for a specific format."
      : "I noted that. The current draft and references are still available from the summary card.",
  };
}
