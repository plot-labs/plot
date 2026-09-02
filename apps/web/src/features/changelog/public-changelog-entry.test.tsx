// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { PublicChangelogEntry } from "@plot/api-client";
import { PublicChangelogEntryView } from "./public-changelog-entry";

function entry(overrides: Partial<PublicChangelogEntry> = {}): PublicChangelogEntry {
  return {
    id: "entry-1",
    entrySlug: "v2.4.0",
    title: "Release v2.4.0",
    tagName: "v2.4.0",
    publishedAt: "2026-08-31T12:00:00Z",
    bodyMarkdown: "Legacy body.",
    workspaceSlug: "acme",
    workspaceName: "Acme",
    logoUrl: null,
    sentences: [
      {
        orderIndex: 0,
        body: "Supported sentence.",
        citations: [{
          provider: "GITHUB",
          sourceLabel: "PR #42",
          originalUrl: "https://github.com/acme/plot/pull/42",
        }],
      },
      {
        orderIndex: 1,
        body: "Follow-up sentence.",
        citations: [{
          provider: "GITHUB",
          sourceLabel: "PR #42",
          originalUrl: "https://github.com/acme/plot/pull/42",
        }],
      },
    ],
    ...overrides,
  };
}

describe("PublicChangelogEntryView", () => {
  it("renders sentence citations with a focusable popover and deduplicated sources", () => {
    render(<PublicChangelogEntryView workspaceSlug="acme" entry={entry()} />);

    expect(screen.getByText("Supported sentence.")).toBeInTheDocument();
    expect(screen.getByText("Follow-up sentence.")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /Show citation/ })).toHaveLength(2);
    expect(
      screen.getAllByRole("link").filter((link) => link.getAttribute("href") === "https://github.com/acme/plot/pull/42"),
    ).toHaveLength(1);

    const chip = screen.getAllByRole("button", { name: /Show citation/ })[0];
    fireEvent.focus(chip);

    expect(screen.getByRole("dialog", { name: "Citation sources" })).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Citation sources" })).toHaveTextContent("PR #42");
  });

  it("uses the legacy body when no sentence snapshot exists", () => {
    render(<PublicChangelogEntryView workspaceSlug="acme" entry={entry({ sentences: [] })} />);

    expect(screen.getByText("Legacy body.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Show citation/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Sources" })).not.toBeInTheDocument();
  });

  it("does not render unsafe citation links", () => {
    render(
      <PublicChangelogEntryView
        workspaceSlug="acme"
        entry={entry({
          sentences: [{
            orderIndex: 0,
            body: "Untrusted sentence.",
            citations: [{
              provider: "GITHUB",
              sourceLabel: "Unsafe",
              originalUrl: "javascript:alert(1)",
            }],
          }],
        })}
      />,
    );

    expect(screen.getByText("Untrusted sentence.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Show citation/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Sources" })).not.toBeInTheDocument();
  });
});
