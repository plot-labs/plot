import { describe, expect, it } from "vitest";

import type { GitHubReleaseActivity } from "@/lib/api-client";

import {
  formatReleaseActivityDetail,
  formatReleaseActivityLabel,
  isFullCommitSha,
  isReleaseActivityInFlight,
  isReleaseCadence,
  normalizeCommitSha,
} from "./release-activity-utils";

function activity(overrides: Partial<GitHubReleaseActivity> = {}): GitHubReleaseActivity {
  return {
    id: "request-1",
    sourceScopeId: "source-1",
    tagName: "v2.4.0",
    status: "READY",
    baseSha: "base",
    headSha: "head",
    artifactId: "artifact-1",
    errorCode: null,
    createdAt: "2026-08-10T00:00:00Z",
    updatedAt: "2026-08-10T00:01:00Z",
    ...overrides,
  };
}

describe("release-activity-utils", () => {
  it("identifies release cadences", () => {
    expect(isReleaseCadence("ON_GITHUB_RELEASE")).toBe(true);
    expect(isReleaseCadence("ON_GIT_TAG")).toBe(true);
    expect(isReleaseCadence("WEEKLY")).toBe(false);
    expect(isReleaseCadence("ON_GITHUB_CHANGE")).toBe(false);
  });

  it("identifies in-flight release activity statuses", () => {
    expect(isReleaseActivityInFlight("QUEUED")).toBe(true);
    expect(isReleaseActivityInFlight("RESOLVING")).toBe(true);
    expect(isReleaseActivityInFlight("GENERATING")).toBe(true);
    expect(isReleaseActivityInFlight("READY")).toBe(false);
    expect(isReleaseActivityInFlight("FAILED")).toBe(false);
  });

  it("formats labels per status", () => {
    expect(formatReleaseActivityLabel(activity({ status: "QUEUED" }))).toBe("Preparing draft for v2.4.0…");
    expect(formatReleaseActivityLabel(activity({ status: "RESOLVING" }))).toBe("Preparing draft for v2.4.0…");
    expect(formatReleaseActivityLabel(activity({ status: "GENERATING" }))).toBe("Preparing draft for v2.4.0…");
    expect(formatReleaseActivityLabel(activity({ status: "READY" }))).toBe("v2.4.0 · Draft ready");
    expect(formatReleaseActivityLabel(activity({ status: "FAILED" }))).toBe("v2.4.0 · Failed");
    expect(formatReleaseActivityLabel(activity({ status: "NEEDS_RANGE" }))).toBe("First release for v2.4.0");
    expect(formatReleaseActivityLabel(activity({ status: "NO_ACTIVITY" }))).toBe("v2.4.0 · No activity in range");
  });

  it("formats detail copy for needs-range and failed statuses", () => {
    expect(formatReleaseActivityDetail(activity({ status: "READY" }))).toBeNull();
    expect(formatReleaseActivityDetail(activity({ status: "NEEDS_RANGE" }))).toBe(
      "Choose the previous commit SHA. Plot keeps this tag head and drafts from that range.",
    );
    expect(formatReleaseActivityDetail(activity({ status: "FAILED", errorCode: "AGENT_RUN_FAILED" }))).toBe(
      "agent run failed",
    );
    expect(formatReleaseActivityDetail(activity({ status: "FAILED", errorCode: null }))).toBeNull();
  });

  it("shows deferred decisions as held and stops generation polling", () => {
    const held = activity({ status: "DEFERRED", artifactId: null });
    expect(isReleaseActivityInFlight(held.status)).toBe(false);
    expect(formatReleaseActivityLabel(held)).toBe("v2.4.0 · Draft held");
    expect(formatReleaseActivityDetail(held)).toBe(
      "Plot has held this draft after assessing customer value. See Home for the decision and any missing evidence.",
    );
  });

  it("accepts only lowercase 40-character commit SHAs", () => {
    expect(normalizeCommitSha(` ${"A".repeat(40)} `)).toBe("a".repeat(40));
    expect(isFullCommitSha("a".repeat(40))).toBe(true);
    expect(isFullCommitSha("A".repeat(40))).toBe(true);
    expect(isFullCommitSha("main")).toBe(false);
    expect(isFullCommitSha("a".repeat(39))).toBe(false);
  });
});
