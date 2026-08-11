// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { SourceRepositoryPicker } from "./source-repository-picker";

describe("SourceRepositoryPicker", () => {
  const sampleSources = [
    { id: "repo-1", displayName: "qyinm/MirrorNote" },
    { id: "repo-2", displayName: "plot-labs/plot" },
  ];

  it("renders selected source and opens custom listbox options", () => {
    const onChange = vi.fn();
    render(<SourceRepositoryPicker sources={sampleSources} value="repo-1" onChange={onChange} />);

    const trigger = screen.getByRole("button", { name: "Source repository" });
    expect(trigger).toHaveTextContent("qyinm/MirrorNote");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();

    fireEvent.click(trigger);

    const listbox = screen.getByRole("listbox", { name: "Source repository options" });
    expect(listbox).toBeInTheDocument();

    const option2 = screen.getByRole("option", { name: "plot-labs/plot" });
    fireEvent.click(option2);

    expect(onChange).toHaveBeenCalledWith("repo-2");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("closes when focus leaves or outside click occurs", () => {
    render(<SourceRepositoryPicker sources={sampleSources} value="repo-1" onChange={vi.fn()} />);

    const trigger = screen.getByRole("button", { name: "Source repository" });
    fireEvent.click(trigger);
    expect(screen.getByRole("listbox")).toBeInTheDocument();

    fireEvent.pointerDown(document.body);
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });
});
