// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const navigation = vi.hoisted(() => ({ pathname: "/artifacts" }));

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
}));

vi.mock("@/components/layout/product-sidebar", () => ({
  ProductSidebar: ({ onThemeChange }: { onThemeChange: (theme: "system" | "light" | "dark") => void }) => (
    <div>
      <button type="button" onClick={() => onThemeChange("light")}>Use light theme</button>
      <button type="button" onClick={() => onThemeChange("dark")}>Use dark theme</button>
    </div>
  ),
}));

import { ProductShell } from "./product-shell";

describe("ProductShell", () => {
  beforeEach(() => {
    navigation.pathname = "/artifacts";
    document.documentElement.dataset.theme = "light";
  });

  it("links directly to workspace Settings and marks Artifacts as current", () => {
    render(
      <ProductShell>
        <div>Content</div>
      </ProductShell>,
    );

    const navigation = screen.getByRole("navigation", { name: "Product navigation" });
    expect(navigation).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Overview" })).toHaveAttribute("href", "/home");
    expect(screen.getByRole("link", { name: "Activity" })).toHaveAttribute("href", "/activity");
    expect(screen.getByRole("link", { name: "Updates" })).toHaveAttribute("aria-current", "page");

    expect(screen.getByRole("link", { name: "Workspace settings" })).toHaveAttribute("href", "/settings/general");
  });

  it("marks only Routines as current on the routines page", () => {
    navigation.pathname = "/routines";

    render(
      <ProductShell>
        <div>Content</div>
      </ProductShell>,
    );

    expect(screen.getByRole("link", { name: "Autonomy" })).toHaveAttribute("aria-current", "page");
    expect(screen.queryByRole("link", { name: "Activity" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Updates" })).not.toBeInTheDocument();
  });

  it("places Connections within Settings", () => {
    navigation.pathname = "/settings/integrations";
    render(<ProductShell><div>Connections content</div></ProductShell>);
    expect(screen.getByRole("link", { name: "Back to Overview" })).toHaveAttribute("href", "/home");
    expect(screen.getByRole("link", { name: "Connections" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Workspace settings" })).toHaveAttribute("aria-current", "page");
  });

  it("keeps the document theme in sync with the product theme", async () => {
    const { unmount } = render(
      <ProductShell>
        <div>Content</div>
      </ProductShell>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Use dark theme" }));
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe("dark"));

    fireEvent.click(screen.getByRole("button", { name: "Use light theme" }));
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe("light"));

    fireEvent.click(screen.getByRole("button", { name: "Use dark theme" }));
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe("dark"));
    unmount();
    expect(document.documentElement.dataset.theme).toBe("light");
  });
});
