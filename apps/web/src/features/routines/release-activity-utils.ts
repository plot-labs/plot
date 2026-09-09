import type { GitHubReleaseActivity, GitHubReleaseDraftStatus, RoutineCadence } from "@/lib/api-client";

export function isReleaseCadence(cadence: RoutineCadence): boolean {
  return cadence === "ON_GITHUB_RELEASE" || cadence === "ON_GIT_TAG";
}

export function isReleaseActivityInFlight(status: GitHubReleaseDraftStatus): boolean {
  return status === "QUEUED" || status === "RESOLVING" || status === "GENERATING";
}

export function formatReleaseActivityLabel(activity: GitHubReleaseActivity): string {
  const { tagName, status } = activity;
  if (isReleaseActivityInFlight(status)) return `Preparing draft for ${tagName}…`;
  if (status === "READY") return `${tagName} · Draft ready`;
  if (status === "FAILED") return `${tagName} · Failed`;
  if (status === "NEEDS_RANGE") return `First release for ${tagName}`;
  if (status === "DEFERRED") return `${tagName} · Draft held`;
  if (status === "NO_ACTIVITY") return `${tagName} · No activity in range`;
  return tagName;
}

export function formatReleaseActivityDetail(activity: GitHubReleaseActivity): string | null {
  if (activity.status === "DEFERRED") {
    return "Plot has held this draft after assessing customer value. See Home for the decision and any missing evidence.";
  }
  if (activity.status === "NEEDS_RANGE") {
    return "Choose the previous commit SHA. Plot keeps this tag head and drafts from that range.";
  }
  if (activity.status === "FAILED" && activity.errorCode) {
    return activity.errorCode.replaceAll("_", " ").toLowerCase();
  }
  return null;
}

const FULL_COMMIT_SHA = /^[0-9a-f]{40}$/;

export function normalizeCommitSha(value: string): string {
  return value.trim().toLowerCase();
}

export function isFullCommitSha(value: string): boolean {
  return FULL_COMMIT_SHA.test(normalizeCommitSha(value));
}
