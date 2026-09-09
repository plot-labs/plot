// @vitest-environment jsdom

import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { ExecutionTimelineItem } from "@plot/api-client";
import { AgentActivityDetail, ExecutionTimelineCard } from "./chat-activity";

function makeTimelineItem(overrides: Partial<ExecutionTimelineItem> = {}): ExecutionTimelineItem {
  return {
    id: "exec-1",
    workspaceId: "ws-1",
    origin: "CHAT",
    stage: "AGENT",
    status: "RUNNING",
    statusLabel: "Running",
    deliveryId: null,
    releaseRequestId: null,
    routineId: null,
    routineExecutionId: null,
    agentRunId: "agent-1",
    artifactWorkflowRunId: null,
    artifactId: null,
    workSessionId: "session-1",
    attemptCount: 1,
    maxAttempts: 3,
    nextAttemptAt: null,
    safeErrorCode: null,
    recoveryAction: null,
    createdAt: "2026-09-09T00:00:00Z",
    updatedAt: "2026-09-09T00:01:00Z",
    finishedAt: null,
    ...overrides,
  };
}

describe("ExecutionTimelineCard (R-011)", () => {
  it("renders Waiting to start for queued fixture", () => {
    const item = makeTimelineItem({
      status: "QUEUED",
      statusLabel: "Waiting to start",
      stage: "ADMISSION",
    });
    render(<ExecutionTimelineCard item={item} />);

    expect(screen.getByTestId("timeline-status")).toHaveTextContent("Waiting to start");
    expect(screen.getByTestId("timeline-stage")).toHaveTextContent("ADMISSION");
    expect(screen.queryByTestId("timeline-spinner")).not.toBeInTheDocument();
  });

  it("renders Running with active spinner for running fixture", () => {
    const item = makeTimelineItem({
      status: "RUNNING",
      statusLabel: "Running",
      stage: "AGENT",
    });
    render(<ExecutionTimelineCard item={item} />);

    expect(screen.getByTestId("timeline-status")).toHaveTextContent("Running");
    expect(screen.getByTestId("timeline-stage")).toHaveTextContent("AGENT");
    expect(screen.getByTestId("timeline-spinner")).toBeInTheDocument();
  });

  it("renders Retry scheduled with next retry timestamp and NO loading spinner", () => {
    const futureTime = new Date(Date.now() + 300_000).toISOString();
    const item = makeTimelineItem({
      status: "RETRY_SCHEDULED",
      statusLabel: "Retry scheduled",
      nextAttemptAt: futureTime,
      recoveryAction: "Automatic retry scheduled",
    });
    render(<ExecutionTimelineCard item={item} />);

    expect(screen.getByTestId("timeline-status")).toHaveTextContent("Retry scheduled");
    expect(screen.getByTestId("timeline-next-retry")).toHaveTextContent("Next retry:");
    expect(screen.getByTestId("timeline-recovery-action")).toHaveTextContent("Automatic retry scheduled");
    expect(screen.queryByTestId("timeline-spinner")).not.toBeInTheDocument();
  });

  it("renders Needs connection with safe error code and recovery action without spinner", () => {
    const item = makeTimelineItem({
      status: "NEEDS_CONNECTION",
      statusLabel: "Needs connection",
      safeErrorCode: "SOURCE_NOT_READY",
      recoveryAction: "Reconnect repository access",
    });
    render(<ExecutionTimelineCard item={item} />);

    expect(screen.getByTestId("timeline-status")).toHaveTextContent("Needs connection");
    expect(screen.getByTestId("timeline-error-code")).toHaveTextContent("Error: SOURCE_NOT_READY");
    expect(screen.getByTestId("timeline-recovery-action")).toHaveTextContent("Reconnect repository access");
    expect(screen.queryByTestId("timeline-spinner")).not.toBeInTheDocument();
  });

  it("renders Failed with safe error code and NO loading spinner", () => {
    const item = makeTimelineItem({
      status: "FAILED",
      statusLabel: "Failed",
      safeErrorCode: "UNKNOWN_ERROR",
      recoveryAction: "Review safe error code and retry",
    });
    render(<ExecutionTimelineCard item={item} />);

    expect(screen.getByTestId("timeline-status")).toHaveTextContent("Failed");
    expect(screen.getByTestId("timeline-error-code")).toHaveTextContent("Error: UNKNOWN_ERROR");
    expect(screen.getByTestId("timeline-recovery-action")).toHaveTextContent("Review safe error code and retry");
    expect(screen.queryByTestId("timeline-spinner")).not.toBeInTheDocument();
  });

  it("renders No activity fixture without spinner", () => {
    const item = makeTimelineItem({
      status: "NO_ACTIVITY",
      statusLabel: "No activity",
      stage: "RELEASE",
    });
    render(<ExecutionTimelineCard item={item} />);

    expect(screen.getByTestId("timeline-status")).toHaveTextContent("No activity");
    expect(screen.queryByTestId("timeline-spinner")).not.toBeInTheDocument();
  });

  it("renders Ready for review fixture without spinner", () => {
    const item = makeTimelineItem({
      status: "READY",
      statusLabel: "Ready for review",
      stage: "ARTIFACT",
    });
    render(<ExecutionTimelineCard item={item} />);

    expect(screen.getByTestId("timeline-status")).toHaveTextContent("Ready for review");
    expect(screen.getByTestId("timeline-stage")).toHaveTextContent("ARTIFACT");
    expect(screen.queryByTestId("timeline-spinner")).not.toBeInTheDocument();
  });
});

describe("AgentActivityDetail with Timeline", () => {
  it("renders timeline projection and safe error without loading spinner on failure", () => {
    const timeline = makeTimelineItem({
      status: "FAILED",
      statusLabel: "Failed",
      safeErrorCode: "GITHUB_ACCESS_DENIED",
      recoveryAction: "Reconnect repository access",
    });
    render(
      <AgentActivityDetail
        run={{
          id: "agent-1",
          chatId: "chat-1",
          instruction: "Write docs",
          contentType: "CHANGELOG",
          contentProfileRevisionId: null,
          brief: null,
          status: "FAILED",
          failureCode: "GITHUB_ACCESS_DENIED",
          artifactId: null,
          artifact: null,
          createdAt: "2026-09-09T00:00:00Z",
          updatedAt: "2026-09-09T00:01:00Z",
        }}
        busy={false}
        error=""
        instruction="Write docs"
        references={[]}
        timelineItem={timeline}
      />,
    );

    expect(screen.getByTestId("timeline-status")).toHaveTextContent("Failed");
    expect(screen.getByTestId("timeline-error-code")).toHaveTextContent("GITHUB_ACCESS_DENIED");
    expect(screen.queryByTestId("timeline-spinner")).not.toBeInTheDocument();
  });
});
