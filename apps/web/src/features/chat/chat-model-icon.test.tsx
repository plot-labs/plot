// @vitest-environment jsdom

import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ChatModelIcon } from "./chat-model-icon";

describe("ChatModelIcon", () => {
  it.each([
    ["openai", "/icons/models/openai.svg"],
    ["anthropic", "/icons/models/claude.svg"],
    ["google", "/icons/models/gemini.svg"],
    ["deepseek", "/icons/models/deepseek.svg"],
    ["xai", "/icons/models/grok.svg"],
    ["qwen", "/icons/models/qwen.svg"],
  ] as const)("uses the local %s SVG asset", (provider, source) => {
    const { container } = render(<ChatModelIcon provider={provider} />);

    expect(container.querySelector("img")).toHaveAttribute("src", source);
  });
});
