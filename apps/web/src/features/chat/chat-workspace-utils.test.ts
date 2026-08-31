import { describe, expect, it } from "vitest";

import { resolveComposerReferenceIds } from "./chat-workspace-utils";

describe("resolveComposerReferenceIds", () => {
  const references = [
    { id: "source-1", available: true },
    { id: "source-2", available: true },
    { id: "source-3", available: false },
  ];

  it("returns selected ids when selection is non-empty", () => {
    expect(resolveComposerReferenceIds(references, ["source-2"])).toEqual(["source-2"]);
  });

  it("returns all available ids when selection is empty", () => {
    expect(resolveComposerReferenceIds(references, [])).toEqual(["source-1", "source-2"]);
  });

  it("filters out unavailable references from the default set", () => {
    expect(resolveComposerReferenceIds(references, [])).not.toContain("source-3");
  });

  it("returns an empty array when there are no references", () => {
    expect(resolveComposerReferenceIds([], [])).toEqual([]);
  });
});
