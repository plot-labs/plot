import { describe, expect, it } from "vitest";

import { PlotApiError } from "@plot/api-client";

import { isPublicChangelogNotFound } from "./public-changelog-errors";

describe("isPublicChangelogNotFound", () => {
  it("detects PlotApiError 404 responses", () => {
    expect(isPublicChangelogNotFound(new PlotApiError(404, "NOT_FOUND", "Changelog not found"))).toBe(true);
  });

  it("ignores non-404 API errors", () => {
    expect(isPublicChangelogNotFound(new PlotApiError(500, "API_ERROR", "Failed"))).toBe(false);
  });
});
