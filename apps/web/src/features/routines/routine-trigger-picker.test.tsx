// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { RoutineTriggerPicker } from "./routine-trigger-picker";

describe("RoutineTriggerPicker", () => {
  it("opens a grouped custom listbox with every trigger", () => {
    render(<RoutineTriggerPicker value="WEEKLY" onChange={vi.fn()} />);

    const trigger = screen.getByRole("button", { name: "Trigger: Weekly" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByText("Weekly")).toBeVisible();

    fireEvent.click(trigger);

    const listbox = screen.getByRole("listbox", { name: "Routine trigger" });
    const selectedOption = screen.getByRole("option", { name: /Weekly/ });
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(listbox).toHaveFocus();
    expect(listbox).toHaveAttribute("tabindex", "0");
    expect(listbox).toHaveAttribute("aria-activedescendant", selectedOption.id);
    expect(screen.getByRole("group", { name: "Time" })).toBeVisible();
    expect(screen.getByRole("group", { name: "Event" })).toBeVisible();
    expect(screen.getAllByRole("option")).toHaveLength(5);
    expect(screen.getAllByRole("option").every((option) => option.tabIndex === -1)).toBe(true);
    expect(selectedOption).toHaveAttribute("aria-selected", "true");
  });

  it("navigates with Arrow, Home, and End and selects the active option with Space", () => {
    const onChange = vi.fn();
    render(<RoutineTriggerPicker value="WEEKLY" onChange={onChange} />);
    const trigger = screen.getByRole("button", { name: "Trigger: Weekly" });

    fireEvent.keyDown(trigger, { key: "ArrowDown" });
    const listbox = screen.getByRole("listbox", { name: "Routine trigger" });
    const daily = screen.getByRole("option", { name: /Daily/ });
    const release = screen.getByRole("option", { name: /Release published/ });
    const tag = screen.getByRole("option", { name: /Git tag pushed/ });

    fireEvent.keyDown(listbox, { key: "Home" });
    expect(listbox).toHaveAttribute("aria-activedescendant", daily.id);
    fireEvent.keyDown(listbox, { key: "End" });
    expect(listbox).toHaveAttribute("aria-activedescendant", tag.id);
    fireEvent.keyDown(listbox, { key: "ArrowUp" });
    expect(listbox).toHaveAttribute("aria-activedescendant", release.id);
    fireEvent.keyDown(listbox, { key: " " });

    expect(onChange).toHaveBeenCalledWith("ON_GITHUB_RELEASE");
    expect(screen.queryByRole("listbox", { name: "Routine trigger" })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("selects the active option with Enter", () => {
    const onChange = vi.fn();
    render(<RoutineTriggerPicker value="DAILY" onChange={onChange} />);
    const trigger = screen.getByRole("button", { name: "Trigger: Daily" });

    fireEvent.keyDown(trigger, { key: "ArrowDown" });
    const listbox = screen.getByRole("listbox", { name: "Routine trigger" });
    fireEvent.keyDown(listbox, { key: "ArrowDown" });
    fireEvent.keyDown(listbox, { key: "Enter" });

    expect(onChange).toHaveBeenCalledWith("WEEKLY");
    expect(trigger).toHaveFocus();
  });

  it("selects a release trigger, closes, and restores trigger focus", () => {
    const onChange = vi.fn();
    render(<RoutineTriggerPicker value="DAILY" onChange={onChange} />);
    const trigger = screen.getByRole("button", { name: "Trigger: Daily" });

    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole("option", { name: /Release published/ }));

    expect(onChange).toHaveBeenCalledWith("ON_GITHUB_RELEASE");
    expect(screen.queryByRole("listbox", { name: "Routine trigger" })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("closes on Escape and restores trigger focus", () => {
    render(<RoutineTriggerPicker value="DAILY" onChange={vi.fn()} />);
    const trigger = screen.getByRole("button", { name: "Trigger: Daily" });

    fireEvent.click(trigger);
    fireEvent.keyDown(screen.getByRole("listbox", { name: "Routine trigger" }), { key: "Escape" });

    expect(screen.queryByRole("listbox", { name: "Routine trigger" })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("closes on Tab or when focus leaves the picker", () => {
    render(
      <>
        <RoutineTriggerPicker value="DAILY" onChange={vi.fn()} />
        <button type="button">After picker</button>
      </>,
    );
    const trigger = screen.getByRole("button", { name: "Trigger: Daily" });

    fireEvent.click(trigger);
    fireEvent.keyDown(screen.getByRole("listbox", { name: "Routine trigger" }), { key: "Tab" });
    expect(screen.queryByRole("listbox", { name: "Routine trigger" })).not.toBeInTheDocument();

    fireEvent.click(trigger);
    const listbox = screen.getByRole("listbox", { name: "Routine trigger" });
    const afterPicker = screen.getByRole("button", { name: "After picker" });
    fireEvent.blur(listbox, { relatedTarget: afterPicker });
    expect(screen.queryByRole("listbox", { name: "Routine trigger" })).not.toBeInTheDocument();
  });

  it("dismisses on an outside pointer interaction", () => {
    render(<RoutineTriggerPicker value="DAILY" onChange={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Trigger: Daily" }));

    fireEvent.pointerDown(document.body);

    expect(screen.queryByRole("listbox", { name: "Routine trigger" })).not.toBeInTheDocument();
  });

  it("allows selecting day of week when Weekly is selected", () => {
    const onDayChange = vi.fn();
    render(<RoutineTriggerPicker value="WEEKLY" onChange={vi.fn()} onDayChange={onDayChange} />);

    const dayTrigger = screen.getByRole("button", { name: "Day: Monday" });
    expect(dayTrigger).toBeVisible();

    fireEvent.click(dayTrigger);

    const listbox = screen.getByRole("listbox", { name: "Day of week" });
    expect(listbox).toBeVisible();

    fireEvent.click(screen.getByRole("option", { name: "Friday" }));

    expect(onDayChange).toHaveBeenCalledWith("Friday");
    expect(screen.getByRole("button", { name: "Day: Friday" })).toBeVisible();
  });
});
