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

  it("keeps source link activation inside the open dialog", () => {
    render(<SourcesPopover sources={sources} />);
    const trigger = screen.getByRole("button", { name: /Sources/ });
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "Sources" });
    const source = screen.getAllByRole("doc-noteref")[0];

    expect(source).toHaveAttribute("href", "https://github.com/acme/plot/pull/184");
    expect(source).toHaveAttribute("target", "_blank");
    fireEvent.click(source);
    expect(dialog).toBeInTheDocument();
  });

  it("dismisses on outside click or pointer and restores focus to the trigger", () => {
    render(
      <>
        <SourcesPopover sources={sources} />
        <button type="button">Outside</button>
      </>,
    );
    const trigger = screen.getByRole("button", { name: /Sources/ });
    const outside = screen.getByRole("button", { name: "Outside" });
    trigger.focus();
    fireEvent.click(trigger);
    expect(screen.getByRole("dialog", { name: "Sources" })).toBeInTheDocument();

    fireEvent.click(outside);
    expect(screen.queryByRole("dialog", { name: "Sources" })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();

    fireEvent.click(trigger);
    fireEvent.pointerDown(outside);
    expect(screen.queryByRole("dialog", { name: "Sources" })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("dismisses on Escape and on the close button with focus restoration", () => {
    render(<SourcesPopover sources={sources} />);
    const trigger = screen.getByRole("button", { name: /Sources/ });
    trigger.focus();
    fireEvent.click(trigger);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Sources" })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();

    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole("button", { name: "Close sources" }));
    expect(screen.queryByRole("dialog", { name: "Sources" })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("keeps an empty source dialog keyboard-trapped and dismissible", () => {
    render(<SourcesPopover sources={[]} />);
    const trigger = screen.getByRole("button", { name: "Sources" });
    trigger.focus();
    fireEvent.click(trigger);
    expect(screen.getByText("No current sources are available for this artifact.")).toBeInTheDocument();

    const dialog = screen.getByRole("dialog", { name: "Sources" });
    const close = screen.getByRole("button", { name: "Close sources" });
    expect(dialog).toHaveFocus();
    fireEvent.keyDown(document, { key: "Tab" });
    expect(close).toHaveFocus();
    fireEvent.keyDown(document, { key: "Tab", shiftKey: true });
    expect(close).toHaveFocus();
    fireEvent.click(close);
    expect(trigger).toHaveFocus();
  });
});
