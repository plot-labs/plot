import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  fetchPublicChangelog: vi.fn(),
  fetchPublicChangelogEntry: vi.fn(),
  notFound: vi.fn(),
}));

vi.mock("@/lib/public-changelog", () => ({
  fetchPublicChangelog: mocks.fetchPublicChangelog,
  fetchPublicChangelogEntry: mocks.fetchPublicChangelogEntry,
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

vi.mock("@/features/changelog/public-changelog-entry", () => ({
  PublicChangelogEntryView: ({
    entry,
  }: {
    entry: { title: string };
  }) => <div data-testid="entry">{entry.title}</div>,
}));

import { PlotApiError } from "@plot/api-client";

import PublicChangelogEntryPage from "./page";

describe("PublicChangelogEntryPage", () => {
  beforeEach(() => {
    mocks.fetchPublicChangelog.mockReset();
    mocks.fetchPublicChangelogEntry.mockReset();
    mocks.notFound.mockReset();
  });

  it("loads a published entry for a known workspace", async () => {
    mocks.fetchPublicChangelog.mockResolvedValue({
      workspaceSlug: "acme",
      workspaceName: "Acme",
      logoUrl: null,
      entries: [],
    });
    mocks.fetchPublicChangelogEntry.mockResolvedValue({
      id: "entry-1",
      entrySlug: "v2.4.0",
      title: "Release v2.4.0",
      tagName: "v2.4.0",
      bodyMarkdown: "Supported sentence.",
      publishedAt: "2026-08-31T12:00:00Z",
    });

    const page = await PublicChangelogEntryPage({
      params: Promise.resolve({ workspaceSlug: "acme", entrySlug: "v2.4.0" }),
    });

    expect(mocks.fetchPublicChangelog).toHaveBeenCalledWith("acme");
    expect(mocks.fetchPublicChangelogEntry).toHaveBeenCalledWith("acme", "v2.4.0");
    expect(page).toMatchObject({
      props: {
        workspaceName: "Acme",
        children: expect.objectContaining({
          props: expect.objectContaining({
            entry: expect.objectContaining({ title: "Release v2.4.0" }),
          }),
        }),
      },
    });
  });

  it("returns notFound for an unknown entry slug", async () => {
    mocks.fetchPublicChangelog.mockResolvedValue({
      workspaceSlug: "acme",
      workspaceName: "Acme",
      logoUrl: null,
      entries: [],
    });
    mocks.fetchPublicChangelogEntry.mockRejectedValue(
      new PlotApiError(404, "NOT_FOUND", "Changelog entry not found"),
    );

    await expect(
      PublicChangelogEntryPage({
        params: Promise.resolve({ workspaceSlug: "acme", entrySlug: "missing" }),
      }),
    ).rejects.toThrow("NEXT_NOT_FOUND");

    expect(mocks.notFound).toHaveBeenCalled();
  });
});
