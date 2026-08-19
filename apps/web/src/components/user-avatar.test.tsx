// @vitest-environment jsdom

import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { UserAvatar } from "@/components/user-avatar";

describe("UserAvatar", () => {
  it("renders a deterministic decorative blobatar from the user id", () => {
    const { container } = render(<UserAvatar userId="user-123" size={40} />);
    const image = container.querySelector("img");

    expect(image).toHaveAttribute("alt", "");
    expect(image).toHaveAttribute("width", "40");
    expect(image).toHaveAttribute("height", "40");
    expect(image?.getAttribute("src")).toMatch(/^data:image\/svg\+xml/);
  });
});
