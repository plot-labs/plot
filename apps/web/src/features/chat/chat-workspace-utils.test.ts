import { describe, expect, it } from "vitest";

import { agentProgressLabel, documentPhaseStatus } from "./chat-workspace-utils";

describe("agentProgressLabel", () => {
  it("names the current phase of a running response", () => {
    expect(agentProgressLabel("RUNNING", "RESEARCHING")).toBe("Reading the linked changes…");
    expect(agentProgressLabel("RUNNING", "WRITING")).toBe("Drafting the content…");
    expect(agentProgressLabel("RUNNING", "REVIEWING")).toBe("Checking the draft against its sources…");
    expect(agentProgressLabel("RUNNING", "REWRITING")).toBe("Revising the draft…");
  });

  it("falls back to the generic label without a known running phase", () => {
    expect(agentProgressLabel("RUNNING")).toBe("Plot is working on a response…");
    expect(agentProgressLabel("RUNNING", "COMPLETE")).toBe("Plot is working on a response…");
    expect(agentProgressLabel("QUEUED", "WRITING")).toBe("Queued…");
  });
});

describe("documentPhaseStatus", () => {
  it("summarizes draft phases for the document panel", () => {
    expect(documentPhaseStatus("WRITING")).toBe("Drafting");
    expect(documentPhaseStatus("REVIEWING")).toBe("Checking sources");
    expect(documentPhaseStatus("REWRITING")).toBe("Revising");
    expect(documentPhaseStatus(null)).toBe("Generating");
  });
});
