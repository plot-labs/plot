// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { RoutineTriggerPicker } from "./routine-trigger-picker";

describe("RoutineTriggerPicker", () => {
  it("opens a grouped custom listbox with every trigger", () => {
    render(<RoutineTriggerPicker value="WEEKLY" onChange={vi.fn()} />);

    const trigger = screen.getByRole("button", { name: "Trigger: Every week" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByText("Run on a selected weekday")).toBeVisible();

    fireEvent.click(trigger);

    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("group", { name: "Time" })).toBeVisible();
    expect(screen.getByRole("group", { name: "Event" })).toBeVisible();
    expect(screen.getAllByRole("option")).toHaveLength(5);
    expect(screen.getByRole("option", { name: /Every week/ })).toHaveAttribute("aria-selected", "true");
  });

  it("selects a release trigger, closes, and restores trigger focus", () => {
    const onChange = vi.fn();
    render(<RoutineTriggerPicker value="DAILY" onChange={onChange} />);
    const trigger = screen.getByRole("button", { name: "Trigger: Every day" });

    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole("option", { name: /Release published/ }));

    expect(onChange).toHaveBeenCalledWith("ON_GITHUB_RELEASE");
    expect(screen.queryByRole("listbox", { name: "Routine trigger" })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });
});
