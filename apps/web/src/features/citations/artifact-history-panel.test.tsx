// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { ArtifactHistoryDetail, PlotApiClient } from "@plot/api-client";
import { ArtifactHistoryPanel } from "./artifact-history-panel";

const detail = {
  createdAt: "2026-08-01T00:01:00Z",
  cause: "Edited by you",
  readOnly: true,
  artifact: { id: "artifact-1" },
} as unknown as ArtifactHistoryDetail;

describe("ArtifactHistoryPanel", () => {
  it("loads content snapshots with accessible timestamps and selects a read-only snapshot", async () => {
    const client = {
      listArtifactHistory: vi.fn().mockResolvedValue([
        { position: 0, createdAt: "2026-08-01T00:01:00Z", cause: "Edited by you" },
        { position: 1, createdAt: "2026-08-01T00:00:00Z", cause: "Initial generation" },
      ]),
      getArtifactHistoryAt: vi.fn().mockResolvedValue(detail),
    } as unknown as PlotApiClient;
    const onSelect = vi.fn();

    render(<ArtifactHistoryPanel variantId="variant-1" client={client} onSelect={onSelect} />);

    expect(await screen.findByRole("button", { name: /Edited by you/ })).toBeVisible();
    expect(screen.getAllByRole("time", { name: /Aug 1, 2026/ })).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: /Initial generation/ }));
    await waitFor(() => expect(onSelect).toHaveBeenCalledWith(detail, 1));
    expect(client.getArtifactHistoryAt).toHaveBeenCalledWith("variant-1", 1, expect.any(Object));
  });
});
