// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ChatComposer } from "./chat-composer";

const references = [{ id: "source-1", label: "PR #1", available: true }];

function inputText(element: HTMLElement, value: string) {
  element.textContent = value;
  fireEvent.input(element);
}

describe("ChatComposer", () => {
  it("enables send only for a trimmed prompt with a connected source", () => {
    const onSubmit = vi.fn();
    render(<ChatComposer references={references} onSubmit={onSubmit} />);
    const prompt = screen.getByRole("textbox", { name: "Chat message" });
    const send = screen.getByRole("button", { name: "Send message" });

    expect(send).toHaveClass("bg-primary", "text-primary-foreground", "dark:bg-[#f4f4f5]");
    expect(send).toBeDisabled();
    inputText(prompt, "   ");
    expect(send).toBeDisabled();
    fireEvent.keyDown(prompt, { key: "Enter" });
    expect(onSubmit).not.toHaveBeenCalled();
    inputText(prompt, " Write release notes ");
    expect(send).toBeEnabled();

    fireEvent.click(send);
    fireEvent.click(send);

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith("Write release notes", ["source-1"]);
    expect(send).toBeDisabled();
  });

  it("stays disabled without a selected source or while busy", () => {
    const { unmount } = render(<ChatComposer references={[]} onSubmit={vi.fn()} />);
    inputText(screen.getByRole("textbox"), "Write release notes");
    expect(screen.getByRole("button", { name: "Send message" })).toBeDisabled();

    unmount();
    render(<ChatComposer references={references} onSubmit={vi.fn()} busy />);
    inputText(screen.getByRole("textbox"), "Write release notes");
    expect(screen.getByRole("button", { name: "Send message" })).toBeDisabled();
  });

  it("passes connected source ids from the center variant", () => {
    const onSubmit = vi.fn();
    render(<ChatComposer variant="center" references={references} onSubmit={onSubmit} />);
    const prompt = screen.getByRole("textbox", { name: "Chat message" });
    const send = screen.getByRole("button", { name: "Send message" });

    fireEvent.change(prompt, { target: { value: "Write release notes" } });
    fireEvent.click(send);

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith("Write release notes", ["source-1"]);
  });

  it("passes all available reference ids on submit", () => {
    const onSubmit = vi.fn();
    const multiReferences = [
      { id: "source-1", label: "PR #1", available: true },
      { id: "source-2", label: "PR #2", available: true },
    ];
    render(<ChatComposer references={multiReferences} onSubmit={onSubmit} />);

    inputText(screen.getByRole("textbox", { name: "Chat message" }), "Write release notes");
    fireEvent.click(screen.getByRole("button", { name: "Send message" }));

    expect(onSubmit).toHaveBeenCalledWith("Write release notes", ["source-1", "source-2"]);
  });

  it("excludes unavailable references from the default set", () => {
    const onSubmit = vi.fn();
    const mixedReferences = [
      { id: "source-1", label: "PR #1", available: true },
      { id: "source-2", label: "PR #2", available: false },
    ];
    render(<ChatComposer references={mixedReferences} onSubmit={onSubmit} />);

    inputText(screen.getByRole("textbox", { name: "Chat message" }), "Write release notes");
    fireEvent.click(screen.getByRole("button", { name: "Send message" }));

    expect(onSubmit).toHaveBeenCalledWith("Write release notes", ["source-1"]);
  });
});
