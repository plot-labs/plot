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
  it("links Integrations and Artifacts and marks the current page", () => {
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
    expect(screen.getByRole("link", { name: "Integrations" })).toHaveAttribute("href", "/integrations");
    expect(screen.getByRole("link", { name: "Artifacts" })).toHaveAttribute("aria-current", "page");
  });
});
