// @vitest-environment jsdom

import { describe, expect, it, vi } from "vitest";

import type { ChatAgentRun, PlotApiClient } from "@plot/api-client";

import { isTerminalChatAgentStatus, pollChatAgentRun } from "./chat-agent-polling";

const queued: ChatAgentRun = {
  id: "agent-1",
  chatId: "chat-1",
  status: "QUEUED",
  failureCode: null,
  generationRunId: null,
  artifactId: null,
  createdAt: "2026-07-01T00:00:00Z",
  updatedAt: "2026-07-01T00:00:00Z",
};

describe("chat-agent-polling", () => {
  it("stops at the Generation handoff and reports each Agent update", async () => {
    const handoff = { ...queued, status: "RUNNING" as const, generationRunId: "generation-1" };
    const getChatAgentRun = vi.fn().mockResolvedValueOnce(handoff);
    const updates: string[] = [];
    const client = { getChatAgentRun } as unknown as PlotApiClient;

    const result = await pollChatAgentRun(client, queued.id, {
      initialRun: queued,
      initialDelayMs: 1,
      onUpdate: (run) => updates.push(run.status),
    });

    expect(result).toEqual(handoff);
    expect(updates).toEqual(["QUEUED", "RUNNING"]);
    expect(getChatAgentRun).toHaveBeenCalledWith("agent-1", expect.any(Object));
  });

  it("treats failed Agent runs as terminal", async () => {
    expect(isTerminalChatAgentStatus("FAILED")).toBe(true);
    const failed = { ...queued, status: "FAILED" as const, failureCode: "SOURCE_UNAVAILABLE" };
    const client = { getChatAgentRun: vi.fn().mockResolvedValue(failed) } as unknown as PlotApiClient;

    await expect(pollChatAgentRun(client, queued.id, { initialDelayMs: 1 })).resolves.toEqual(failed);
  });
});
