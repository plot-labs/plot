// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getGitHubReleaseActivity: vi.fn(),
  retryGitHubReleaseDraft: vi.fn(),
}));

vi.mock("@/lib/api-client", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api-client")>("@/lib/api-client");
  return {
    ...actual,
    plotApiClient: {
      getGitHubReleaseActivity: mocks.getGitHubReleaseActivity,
      retryGitHubReleaseDraft: mocks.retryGitHubReleaseDraft,
    },
  };
});

import type { GitHubReleaseActivity } from "@/lib/api-client";
import { RoutineReleaseActivity } from "./routine-release-activity";

describe("RoutineReleaseActivity", () => {
  beforeEach(() => {
    mocks.getGitHubReleaseActivity.mockReset();
    mocks.retryGitHubReleaseDraft.mockReset();
  });

  it("renders nothing when release activity returns 204", async () => {
    mocks.getGitHubReleaseActivity.mockResolvedValue(null);
    render(<RoutineReleaseActivity sourceScopeId="source-1" routineName="Release routine" />);

    await waitFor(() => expect(mocks.getGitHubReleaseActivity).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("shows an open artifact link when release draft is ready", async () => {
    mocks.getGitHubReleaseActivity.mockResolvedValue(activity({ status: "READY", artifactId: "artifact-42" }));
    render(<RoutineReleaseActivity sourceScopeId="source-1" routineName="Release routine" />);

    expect(await screen.findByRole("status")).toHaveTextContent("Latest release: v2.4.0 · Draft ready");
    expect(screen.getByRole("link", { name: "Open artifact for Release routine release v2.4.0" }))
      .toHaveAttribute("href", "/artifacts?artifact=artifact-42");
  });

  it("calls retry when a failed release draft is retried", async () => {
    mocks.getGitHubReleaseActivity.mockResolvedValue(activity({ status: "FAILED", artifactId: null, errorCode: "AGENT_RUN_FAILED" }));
    mocks.retryGitHubReleaseDraft.mockResolvedValue(activity({ status: "QUEUED", errorCode: null }));
    render(<RoutineReleaseActivity sourceScopeId="source-1" routineName="Release routine" />);

    fireEvent.click(await screen.findByRole("button", { name: "Retry release draft for Release routine" }));

    await waitFor(() => expect(mocks.retryGitHubReleaseDraft).toHaveBeenCalledWith("source-1", "request-1", expect.anything()));
    expect(await screen.findByRole("status")).toHaveTextContent("Latest release: Preparing draft for v2.4.0…");
    expect(screen.queryByRole("button", { name: "Retry release draft for Release routine" })).not.toBeInTheDocument();
  });

  it("shows in-flight copy without retry for generating drafts", async () => {
    mocks.getGitHubReleaseActivity.mockResolvedValue(activity({ status: "GENERATING", artifactId: null }));
    render(<RoutineReleaseActivity sourceScopeId="source-1" routineName="Release routine" />);

    expect(await screen.findByRole("status")).toHaveTextContent("Latest release: Preparing draft for v2.4.0…");
    expect(screen.queryByRole("button", { name: "Retry release draft for Release routine" })).not.toBeInTheDocument();
  });
});

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
