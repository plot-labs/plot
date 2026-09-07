// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getGitHubReleaseActivity: vi.fn(),
  getGitHubReleaseActivityById: vi.fn(),
  retryGitHubReleaseDraft: vi.fn(),
  selectGitHubReleaseRange: vi.fn(),
}));

vi.mock("@/lib/api-client", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api-client")>("@/lib/api-client");
  return {
    ...actual,
    plotApiClient: {
      getGitHubReleaseActivity: mocks.getGitHubReleaseActivity,
      getGitHubReleaseActivityById: mocks.getGitHubReleaseActivityById,
      retryGitHubReleaseDraft: mocks.retryGitHubReleaseDraft,
      selectGitHubReleaseRange: mocks.selectGitHubReleaseRange,
    },
  };
});

import type { GitHubReleaseActivity } from "@/lib/api-client";
import { RoutineReleaseActivity } from "./routine-release-activity";

describe("RoutineReleaseActivity", () => {
  beforeEach(() => {
    mocks.getGitHubReleaseActivity.mockReset();
    mocks.getGitHubReleaseActivityById.mockReset();
    mocks.retryGitHubReleaseDraft.mockReset();
    mocks.selectGitHubReleaseRange.mockReset();
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

  it("loads a specific request id and posts a pinned-head range", async () => {
    const head = "a".repeat(40);
    const base = "b".repeat(40);
    mocks.getGitHubReleaseActivityById.mockResolvedValue(activity({
      status: "NEEDS_RANGE",
      artifactId: null,
      baseSha: null,
      headSha: head,
    }));
    mocks.selectGitHubReleaseRange.mockResolvedValue(activity({ status: "QUEUED", artifactId: null, baseSha: base, headSha: head }));
    render(<RoutineReleaseActivity sourceScopeId="source-1" routineName="Release routine" releaseRequestId="request-1" />);

    expect(await screen.findByRole("status")).toHaveTextContent("Latest release: First release for v2.4.0");
    expect(mocks.getGitHubReleaseActivityById).toHaveBeenCalledWith("source-1", "request-1", expect.anything());
    expect(mocks.getGitHubReleaseActivity).not.toHaveBeenCalled();
    expect(screen.getByText(`Tag head ${head}`)).toBeVisible();

    const baseInput = screen.getByRole("textbox", { name: "Previous commit SHA for Release routine" });
    fireEvent.change(baseInput, { target: { value: base } });
    fireEvent.click(screen.getByRole("button", { name: "Generate draft from range for Release routine" }));

    await waitFor(() => expect(mocks.selectGitHubReleaseRange).toHaveBeenCalledWith(
      "source-1",
      "request-1",
      { baseSha: base, headSha: head },
      expect.anything(),
    ));
    expect(await screen.findByRole("status")).toHaveTextContent("Latest release: Preparing draft for v2.4.0…");
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
