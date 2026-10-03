// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ChatComposer } from "./chat-composer";

vi.mock("@/lib/api-client", () => ({ plotApiClient: {
  listSkills: vi.fn().mockResolvedValue([{ id: "skill-1", name: "humanizer", description: "Natural prose", revision: 1, isSystem: true }]),
} }));


function inputText(element: HTMLElement, value: string) {
  element.textContent = value;
  fireEvent.input(element);
}

describe("ChatComposer", () => {
  beforeEach(() => {window.localStorage.clear(); Element.prototype.scrollIntoView = vi.fn();});

  it("submits the selected writing skill with the original prompt", async () => {
    const onSubmit = vi.fn();
    render(<ChatComposer variant="center" onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole("button", { name: "Choose skill (/)" }));
    fireEvent.click(await screen.findByRole("button", { name: /\/humanizer/ }));
    fireEvent.change(screen.getByRole("textbox", { name: "Chat message" }), { target: { value: "Draft an update" } });
    fireEvent.click(screen.getByRole("button", { name: "Send message" }));
    expect(onSubmit).toHaveBeenCalledWith("Draft an update", ["skill-1"], "auto", "medium");
  });

  it("opens skills menu when typing slash, shows skill chip, and allows removing it", async () => {
    const onSubmit = vi.fn();
    render(<ChatComposer variant="center" onSubmit={onSubmit} />);
    const prompt = screen.getByRole("textbox", { name: "Chat message" });
    fireEvent.change(prompt, { target: { value: "/" } });
    fireEvent.click(await screen.findByRole("button", { name: /\/humanizer/ }));
    expect(screen.getByText("humanizer")).toBeInTheDocument();

    const removeBtn = screen.getByRole("button", { name: "Remove skill humanizer" });
    fireEvent.click(removeBtn);
    expect(screen.queryByText("humanizer")).not.toBeInTheDocument();
  });

  it("enables send only for a trimmed prompt", () => {
    const onSubmit = vi.fn();
    render(<ChatComposer onSubmit={onSubmit} />);
    const prompt = screen.getByRole("textbox", { name: "Chat message" });
    const send = screen.getByRole("button", { name: "Send message" });

    expect(send).toHaveClass("glass-button", "glass-primary");
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
    expect(onSubmit).toHaveBeenCalledWith("Write release notes", [], "auto", "medium");
    expect(send).toBeDisabled();
  });
  it("does not render a voice input control", () => {
    render(<ChatComposer variant="center" onSubmit={vi.fn()} />);

    expect(screen.queryByRole("button", { name: "Voice input" })).not.toBeInTheDocument();
  });

  it("shows progress and blocks repeated sends until the response finishes", () => {
    const onSubmit = vi.fn();
    const { rerender } = render(<ChatComposer busy onSubmit={onSubmit} />);
    const prompt = screen.getByRole("textbox", { name: "Chat message" });
    const send = screen.getByRole("button", { name: "Send message" });
    inputText(prompt, "Write release notes");
    expect(send).toHaveAttribute("aria-busy", "true");
    expect(send).toBeDisabled();
    fireEvent.click(send);
    fireEvent.keyDown(prompt, { key: "Enter" });
    expect(onSubmit).not.toHaveBeenCalled();
    rerender(<ChatComposer busy={false} onSubmit={onSubmit} />);
    expect(send).toHaveAttribute("aria-busy", "false");
    expect(send).toBeEnabled();
  });

  it("stays disabled when generation is not allowed", () => {
    render(<ChatComposer variant="center" canGenerate={false} onSubmit={vi.fn()} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Chat message" }), { target: { value: "Write release notes" } });
    expect(screen.getByRole("button", { name: "Send message" })).toBeDisabled();
  });

	it("submits a general question without a connected source but stays disabled while busy", () => {
		const onSubmit = vi.fn();
		const { unmount } = render(<ChatComposer onSubmit={onSubmit} />);
		inputText(screen.getByRole("textbox"), "Write release notes");
		const send = screen.getByRole("button", { name: "Send message" });
		expect(send).toBeEnabled();
		fireEvent.click(send);
		expect(onSubmit).toHaveBeenCalledWith("Write release notes", [], "auto", "medium");

    unmount();
    render(<ChatComposer onSubmit={vi.fn()} busy />);
    inputText(screen.getByRole("textbox"), "Write release notes");
    expect(screen.getByRole("button", { name: "Send message" })).toBeDisabled();
  });
  it("submits from the center variant without a source selection", () => {
    const onSubmit = vi.fn();
    render(<ChatComposer variant="center" onSubmit={onSubmit} />);
    const prompt = screen.getByRole("textbox", { name: "Chat message" });
    const send = screen.getByRole("button", { name: "Send message" });

    fireEvent.change(prompt, { target: { value: "Write release notes" } });
    fireEvent.click(send);

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith("Write release notes", [], "auto", "medium");
  });

  it("opens the model picker above the composer and remembers the selected model", async () => {
    const onSubmit = vi.fn();
    render(<ChatComposer onSubmit={onSubmit} />);

    fireEvent.click(screen.getByRole("button", { name: "Choose model" }));
    expect(screen.getByRole("listbox", { name: "Available models" }).closest("[data-side]")).toHaveAttribute("data-side", "top");
    fireEvent.click(screen.getByRole("option", { name: /Gemini 3\.8 Flash/ }));
    inputText(screen.getByRole("textbox", { name: "Chat message" }), "Answer quickly");
    fireEvent.click(screen.getByRole("button", { name: "Send message" }));

    expect(onSubmit).toHaveBeenCalledWith(
      "Answer quickly",
      [],
      "google/gemini-3.8-flash",
      "medium",
    );
    await waitFor(() => expect(window.localStorage.getItem("plot.chat.model")).toBe("google/gemini-3.8-flash"));
  });

  it("opens the effort list beside the model picker and persists the selected level", async () => {
    const onSubmit = vi.fn();
    render(<ChatComposer onSubmit={onSubmit} />);

    fireEvent.click(screen.getByRole("button", { name: "Choose model" }));
    fireEvent.click(screen.getByRole("button", { name: /Choose reasoning effort/ }));
    const effortMenu = screen.getByRole("menu", { name: "Reasoning effort options" });
    expect(effortMenu).toBeInTheDocument();
    expect(effortMenu).toHaveClass("bottom-0", "left-[calc(100%+8px)]");
    expect(screen.queryByRole("slider")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("menuitemradio", { name: /^High/ }));

    inputText(screen.getByRole("textbox", { name: "Chat message" }), "Think carefully");
    fireEvent.click(screen.getByRole("button", { name: "Send message" }));

    expect(onSubmit).toHaveBeenCalledWith("Think carefully", [], "auto", "high");
    await waitFor(() => expect(window.localStorage.getItem("plot.chat.reasoning-effort")).toBe("high"));
  });

  it("only shows the effort levels supported by the selected model", async () => {
    render(<ChatComposer onSubmit={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Choose model" }));
    fireEvent.click(screen.getByRole("option", { name: /Gemini 3\.8 Flash/ }));
    fireEvent.click(screen.getByRole("button", { name: "Choose model" }));
    fireEvent.click(screen.getByRole("button", { name: /Choose reasoning effort/ }));

    expect(screen.getAllByRole("menuitemradio")).toHaveLength(3);
    expect(screen.getByRole("menuitemradio", { name: /^Low/ })).toBeInTheDocument();
    expect(screen.getByRole("menuitemradio", { name: /^Medium/ })).toBeInTheDocument();
    expect(screen.getByRole("menuitemradio", { name: /^High/ })).toBeInTheDocument();
    expect(screen.queryByRole("menuitemradio", { name: /^Max/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitemradio", { name: /^Extra high/ })).not.toBeInTheDocument();
    expect(screen.queryByText("A balanced default for most tasks")).not.toBeInTheDocument();
  });

  it("hides effort for models without reasoning support", () => {
    render(<ChatComposer onSubmit={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Choose model" }));
    fireEvent.click(screen.getByRole("option", { name: /Haiku 4\.5/ }));
    fireEvent.click(screen.getByRole("button", { name: "Choose model" }));

    expect(screen.queryByRole("button", { name: /Choose reasoning effort/ })).not.toBeInTheDocument();
  });

  it("opens the model picker below the composer in center variant", () => {
    render(<ChatComposer variant="center" onSubmit={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Choose model" }));
    expect(screen.getByRole("listbox", { name: "Available models" }).closest("[data-side]")).toHaveAttribute("data-side", "bottom");
  });
  it("preserves draft for Shift+Enter and IME while Enter submits once", () => {
    const onSubmit=vi.fn(); render(<ChatComposer onSubmit={onSubmit}/>);
    const input=screen.getByRole('textbox',{name:'Chat message'});
    fireEvent.change(input,{target:{value:'Hello'}});
    fireEvent.keyDown(input,{key:'Enter',shiftKey:true});
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input,{key:'Enter'});
    expect(onSubmit).not.toHaveBeenCalled(); expect(input).toHaveValue('Hello');
    fireEvent.compositionEnd(input);
    fireEvent.keyDown(input,{key:'Enter'});
    expect(onSubmit).toHaveBeenCalledTimes(1); expect(input).toHaveValue('');
  });

  it("does not consume Enter on reasoning effort controls as model selection", () => {
    render(<ChatComposer onSubmit={vi.fn()}/>);
    fireEvent.click(screen.getByRole("button", {name:"Choose model"}));
    fireEvent.click(screen.getByRole("option", {name:/GPT-5\.5/}));
    fireEvent.click(screen.getByRole("button", {name:"Choose model"}));
    const trigger=screen.getByRole("button", {name:/Choose reasoning effort/});
    expect(fireEvent.keyDown(trigger,{key:"Enter"})).toBe(true);
    fireEvent.click(trigger);
    const high=screen.getByRole("menuitemradio", {name:/^High/});
    expect(fireEvent.keyDown(high,{key:"Enter"})).toBe(true);
    fireEvent.click(high);
    expect(screen.getByRole("button", {name:/Choose reasoning effort/})).toHaveTextContent("High");
    expect(screen.getByRole("button", {name:"Choose model"})).toHaveTextContent("GPT-5.5");
  });

});
