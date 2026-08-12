// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ChatComposer } from "./chat-composer";

const references = [{ id: "source-1", label: "PR #1", available: true }];

describe("ChatComposer", () => {
  it("enables send only for a trimmed prompt with a connected source", () => {
    const onSubmit = vi.fn();
    render(<ChatComposer references={references} onSubmit={onSubmit} />);
    const prompt = screen.getByRole("textbox", { name: "Chat message" });
    const send = screen.getByRole("button", { name: "Send message" });

    expect(send).toBeDisabled();
    fireEvent.change(prompt, { target: { value: "   " } });
    expect(send).toBeDisabled();
    fireEvent.change(prompt, { target: { value: " Write release notes " } });
    expect(send).toBeEnabled();

    fireEvent.click(send);
    fireEvent.click(send);

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith("Write release notes", []);
    expect(send).toBeDisabled();
  });

  it("stays disabled without a selected source or while busy", () => {
    const { unmount } = render(<ChatComposer references={[]} onSubmit={vi.fn()} />);
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "Write release notes" } });
    expect(screen.getByRole("button", { name: "Send message" })).toBeDisabled();

    unmount();
    render(<ChatComposer references={references} onSubmit={vi.fn()} busy />);
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "Write release notes" } });
    expect(screen.getByRole("button", { name: "Send message" })).toBeDisabled();
  });
});
