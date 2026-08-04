// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { ContentSource } from "@plot/api-client";
import { SourcesPopover } from "./sources-popover";

const sources: ContentSource[] = [
  {
    evidenceId: "evidence-1",
    provider: "GITHUB",
    sourceLabel: "PR #184",
    originalUrl: "https://github.com/acme/plot/pull/184",
    statementIds: ["sentence-1"],
  },
  {
    evidenceId: "evidence-2",
    provider: "GITHUB",
    sourceLabel: "Issue #9",
    originalUrl: "https://github.com/acme/plot/issues/9",
    statementIds: ["sentence-2"],
  },
];

describe("SourcesPopover", () => {
  it("traps Tab and Shift+Tab and restores focus on close", () => {
    render(<SourcesPopover sources={sources} />);
    const trigger = screen.getByRole("button", { name: /Sources/ });
    trigger.focus();
    fireEvent.click(trigger);

    const dialog = screen.getByRole("dialog", { name: "Sources" });
    const close = screen.getByRole("button", { name: "Close sources" });
    const links = screen.getAllByRole("doc-noteref");
    expect(dialog).toHaveFocus();

    fireEvent.keyDown(document, { key: "Tab" });
    expect(close).toHaveFocus();

    fireEvent.keyDown(document, { key: "Tab", shiftKey: true });
    expect(links[links.length - 1]).toHaveFocus();

    fireEvent.keyDown(document, { key: "Tab" });
    expect(close).toHaveFocus();

    fireEvent.click(close);
    expect(trigger).toHaveFocus();
  });
});
