import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  fetchPublicChangelog: vi.fn(),
  notFound: vi.fn(),
}));

vi.mock("@/lib/public-changelog", () => ({
  fetchPublicChangelog: mocks.fetchPublicChangelog,
}));

vi.mock("next/navigation", () => ({
  notFound: () => {
    mocks.notFound();
    throw new Error("NEXT_NOT_FOUND");
  },
}));

vi.mock("@/features/changelog/public-changelog-layout", () => ({
  PublicChangelogLayout: ({
    children,
    workspaceName,
  }: {
    children: React.ReactNode;
    workspaceName: string;
  }) => (
    <div data-testid="layout">
      <span>{workspaceName}</span>
      {children}
    </div>
  ),
}));

vi.mock("@/features/changelog/public-changelog-list", () => ({
  PublicChangelogList: ({
    workspaceSlug,
    entries,
  }: {
    workspaceSlug: string;
    entries: Array<{ title: string }>;
  }) => (
    <div data-testid="list">
      <span>{workspaceSlug}</span>
      <span>{entries.map((entry) => entry.title).join(", ")}</span>
    </div>
  ),
}));

import { PlotApiError } from "@plot/api-client";

import PublicChangelogPage from "./page";

describe("PublicChangelogPage", () => {
  beforeEach(() => {
    mocks.fetchPublicChangelog.mockReset();
    mocks.notFound.mockReset();
  });

  it("loads the public changelog list for a known workspace", async () => {
    mocks.fetchPublicChangelog.mockResolvedValue({
      workspaceSlug: "acme",
      workspaceName: "Acme",
      logoUrl: null,
      entries: [{ id: "entry-1", entrySlug: "v2.4.0", title: "Release v2.4.0", tagName: "v2.4.0", publishedAt: "2026-08-31T12:00:00Z" }],
    });

    const page = await PublicChangelogPage({ params: Promise.resolve({ workspaceSlug: "acme" }) });

    expect(mocks.fetchPublicChangelog).toHaveBeenCalledWith("acme");
    expect(page).toMatchObject({
      props: {
        workspaceName: "Acme",
        children: expect.objectContaining({
          props: expect.objectContaining({
            workspaceSlug: "acme",
            entries: expect.arrayContaining([
              expect.objectContaining({ title: "Release v2.4.0" }),
            ]),
          }),
        }),
      },
    });
  });

  it("returns notFound for an unknown workspace slug", async () => {
    mocks.fetchPublicChangelog.mockRejectedValue(new PlotApiError(404, "NOT_FOUND", "Changelog not found"));

    await expect(
      PublicChangelogPage({ params: Promise.resolve({ workspaceSlug: "missing" }) }),
    ).rejects.toThrow("NEXT_NOT_FOUND");

    expect(mocks.notFound).toHaveBeenCalled();
  });
});
