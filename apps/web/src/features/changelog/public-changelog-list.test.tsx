// @vitest-environment jsdom

import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { PublicChangelogList } from "./public-changelog-list";

describe("PublicChangelogList", () => {
  it("renders published entries with links to entry pages", () => {
    render(
      <PublicChangelogList
        workspaceSlug="acme"
        entries={[
          {
            id: "entry-1",
            entrySlug: "v2.4.0",
            title: "Release v2.4.0",
            tagName: "v2.4.0",
            publishedAt: "2026-08-31T12:00:00Z",
          },
          {
            id: "entry-2",
            entrySlug: "v2.3.0",
            title: "Release v2.3.0",
            tagName: null,
            publishedAt: "2026-08-01T12:00:00Z",
          },
        ]}
      />,
    );

    expect(screen.getByRole("heading", { level: 1, name: "Updates" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Release v2.4.0" })).toHaveAttribute("href", "/acme/changelog/v2.4.0");
    expect(screen.getByRole("link", { name: "Release v2.3.0" })).toHaveAttribute("href", "/acme/changelog/v2.3.0");
    expect(screen.getByText("v2.4.0")).toBeVisible();
    expect(screen.queryByText("v2.3.0")).not.toBeInTheDocument();
  });

  it("shows an empty state when there are no published entries", () => {
    render(<PublicChangelogList workspaceSlug="acme" entries={[]} />);

    expect(screen.getByRole("heading", { level: 1, name: "No changelog entries yet" })).toBeVisible();
    expect(screen.getByText(/Published releases will appear here/i)).toBeVisible();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});
