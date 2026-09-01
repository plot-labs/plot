// @vitest-environment jsdom

import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { HeroTerminal } from "./hero-terminal";

describe("HeroTerminal", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("ends the typed session on hosted publishing", () => {
    const { container } = render(<HeroTerminal />);
    const terminal = container.querySelector("pre");

    for (let step = 0; step < 200 && !/public changelog\s+live/.test(terminal?.textContent ?? ""); step += 1) {
      act(() => {
        vi.advanceTimersByTime(250);
      });
    }

    expect(terminal).toHaveTextContent(/plot changelog publish/i);
    expect(terminal).toHaveTextContent(/public changelog live/i);
    expect(terminal).not.toHaveTextContent(/outside Plot/i);
    expect(screen.getByText("Publish")).toBeVisible();
  });
});
