// @vitest-environment jsdom

import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ChatModelIcon } from "./chat-model-icon";

describe("ChatModelIcon", () => {
  it.each([
    ["openai", "/icons/models/openai.svg"],
    ["anthropic", "/icons/models/claude.svg"],
  ] as const)("uses the local %s SVG asset", (provider, source) => {
    const { container } = render(<ChatModelIcon provider={provider} />);

    expect(container.querySelector("img")).toHaveAttribute("src", source);
  });
});
