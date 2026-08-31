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
  if (status === "NO_ACTIVITY") return `${tagName} · No activity in range`;
  return tagName;
}

export function formatReleaseActivityDetail(activity: GitHubReleaseActivity): string | null {
  if (activity.status === "NEEDS_RANGE") {
    return "Plot recorded this tag as the starting boundary. The next release will generate a draft.";
  }
  if (activity.status === "FAILED" && activity.errorCode) {
    return activity.errorCode.replaceAll("_", " ").toLowerCase();
  }
  return null;
}
