// @vitest-environment jsdom

import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  usePathname: () => "/artifacts",
}));

vi.mock("@/components/layout/product-sidebar", () => ({
  ProductSidebar: () => null,
}));

import { ProductShell } from "./product-shell";

describe("ProductShell mobile navigation", () => {
  it("links directly to workspace Settings and marks Artifacts as current", () => {
    vi.stubGlobal("matchMedia", () => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));

    render(
      <ProductShell>
        <div>Content</div>
      </ProductShell>,
    );

    const navigation = screen.getByRole("navigation", { name: "Product navigation" });
    expect(navigation).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Chat" })).toHaveAttribute("href", "/chat");
    expect(screen.getByRole("link", { name: "Artifacts" })).toHaveAttribute("aria-current", "page");

    expect(screen.getByRole("link", { name: "Workspace settings" })).toHaveAttribute("href", "/settings/integrations");
  });
});
