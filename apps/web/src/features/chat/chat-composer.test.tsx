// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ChatComposer } from "./chat-composer";

vi.mock("@/lib/api-client", () => ({ plotApiClient: {
  listSkills: vi.fn().mockResolvedValue([{ id: "skill-1", name: "humanizer", description: "Natural prose", revision: 1, isSystem: true }]),
} }));

const references = [{ id: "source-1", label: "PR #1", available: true }];

function inputText(element: HTMLElement, value: string) {
  element.textContent = value;
  fireEvent.input(element);
}

describe("ChatComposer", () => {
  it("submits the selected writing skill with the original prompt", async () => {
    const onSubmit = vi.fn();
    render(<ChatComposer variant="center" references={references} onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole("button", { name: "Choose skill (/)" }));
    fireEvent.click(await screen.findByRole("button", { name: /\/humanizer/ }));
    fireEvent.change(screen.getByRole("textbox", { name: "Chat message" }), { target: { value: "Draft an update" } });
    fireEvent.click(screen.getByRole("button", { name: "Send message" }));
    expect(onSubmit).toHaveBeenCalledWith("Draft an update", ["source-1"], ["skill-1"]);
  });

  it("opens skills menu when typing slash, shows skill chip, and allows removing it", async () => {
    const onSubmit = vi.fn();
    render(<ChatComposer variant="center" references={references} onSubmit={onSubmit} />);
    const prompt = screen.getByRole("textbox", { name: "Chat message" });
    fireEvent.change(prompt, { target: { value: "/" } });
    fireEvent.click(await screen.findByRole("button", { name: /\/humanizer/ }));
    expect(screen.getByText("humanizer")).toBeInTheDocument();

    const removeBtn = screen.getByRole("button", { name: "Remove skill humanizer" });
    fireEvent.click(removeBtn);
    expect(screen.queryByText("humanizer")).not.toBeInTheDocument();
  });

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
    expect(onSubmit).toHaveBeenCalledWith("Write release notes", ["source-1"], []);
    expect(send).toBeDisabled();
  });
  it("does not render a voice input control", () => {
    render(<ChatComposer variant="center" references={references} onSubmit={vi.fn()} />);

    expect(screen.queryByRole("button", { name: "Voice input" })).not.toBeInTheDocument();
  });

  it("stays disabled when generation is not allowed", () => {
    render(<ChatComposer variant="center" references={references} canGenerate={false} onSubmit={vi.fn()} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Chat message" }), { target: { value: "Write release notes" } });
    expect(screen.getByRole("button", { name: "Send message" })).toBeDisabled();
  });

	it("submits a general question without a connected source but stays disabled while busy", () => {
		const onSubmit = vi.fn();
		const { unmount } = render(<ChatComposer references={[]} onSubmit={onSubmit} />);
		inputText(screen.getByRole("textbox"), "Write release notes");
		const send = screen.getByRole("button", { name: "Send message" });
		expect(send).toBeEnabled();
		fireEvent.click(send);
		expect(onSubmit).toHaveBeenCalledWith("Write release notes", [], []);

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
    expect(onSubmit).toHaveBeenCalledWith("Write release notes", ["source-1"], []);
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

    expect(onSubmit).toHaveBeenCalledWith("Write release notes", ["source-1", "source-2"], []);
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

    expect(onSubmit).toHaveBeenCalledWith("Write release notes", ["source-1"], []);
  });
});
