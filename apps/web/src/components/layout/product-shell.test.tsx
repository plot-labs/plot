// @vitest-environment jsdom

import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const navigation = vi.hoisted(() => ({ pathname: "/artifacts" }));

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
}));

vi.mock("@/components/layout/product-sidebar", () => ({
  ProductSidebar: () => null,
}));

import { ProductShell } from "./product-shell";

describe("ProductShell mobile navigation", () => {
  beforeEach(() => {
    navigation.pathname = "/artifacts";
    vi.stubGlobal("matchMedia", () => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
  });

  it("links directly to workspace Settings and marks Artifacts as current", () => {
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

  it("marks only Routines as current on the routines page", () => {
    navigation.pathname = "/routines";

    render(
      <ProductShell>
        <div>Content</div>
      </ProductShell>,
    );

    expect(screen.getByRole("link", { name: "Routines" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Chat" })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("link", { name: "Artifacts" })).not.toHaveAttribute("aria-current");
  });
});
