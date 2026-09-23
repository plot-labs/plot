// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getWorkspace: vi.fn(),
  updateWorkspace: vi.fn(),
  createSubscriptionCheckout: vi.fn(),
	createSubscriptionPortal: vi.fn(),
}));

vi.mock("@/lib/api-client", () => ({
  getSelectedWorkspaceId: () => "workspace-1",
  plotApiClient: mocks,
}));

import { WorkspaceGeneral } from "./workspace-general";
import { publicChangelogUrl } from "@/lib/public-changelog-url";

describe("WorkspaceGeneral", () => {
  beforeEach(() => {
    mocks.getWorkspace.mockReset().mockResolvedValue({
      id: "workspace-1",
      name: "Personal",
      slug: "personal",
      status: "ACTIVE",
      logoUrl: null,
      publicCitationsEnabled: true,
      role: "OWNER",
    });
    mocks.updateWorkspace.mockReset().mockResolvedValue({
      id: "workspace-1",
      name: "Product",
      slug: "personal",
      status: "ACTIVE",
      logoUrl: null,
      publicCitationsEnabled: true,
      role: "OWNER",
    });
    mocks.createSubscriptionCheckout.mockReset();
		mocks.createSubscriptionPortal.mockReset();
  });

  it("shows the public changelog URL with copy and view actions", async () => {
    render(<WorkspaceGeneral />);

    expect(await screen.findByText(publicChangelogUrl("personal"))).toBeVisible();
    expect(screen.getByText("personal", { selector: ".font-sans" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Copy link" })).toBeVisible();
    expect(screen.getByRole("link", { name: "View live" })).toHaveAttribute("href", publicChangelogUrl("personal"));
  });

  it("loads the workspace profile and saves a renamed workspace", async () => {
    render(<WorkspaceGeneral />);

    const name = await screen.findByRole("textbox", { name: "Workspace name" });
    expect(name).toHaveValue("Personal");
    fireEvent.change(name, { target: { value: "Product" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(mocks.updateWorkspace).toHaveBeenCalledWith("workspace-1", {
      name: "Product",
      logoUrl: "",
      publicCitationsEnabled: true,
    }));
    expect(await screen.findByRole("status")).toHaveTextContent("Workspace settings saved.");
  });

  it("saves the public citation visibility setting", async () => {
    render(<WorkspaceGeneral />);

    const toggle = await screen.findByRole("switch", { name: "Public citations" });
    expect(toggle).toHaveAttribute("aria-checked", "true");
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-checked", "false");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(mocks.updateWorkspace).toHaveBeenCalledWith("workspace-1", {
      name: "Personal",
      logoUrl: "",
      publicCitationsEnabled: false,
    }));
  });

  it("reloads the profile when the selected workspace changes", async () => {
    render(<WorkspaceGeneral />);

    await screen.findByRole("textbox", { name: "Workspace name" });
    window.dispatchEvent(new CustomEvent("plot:workspace-changed", { detail: { id: "workspace-2" } }));

    await waitFor(() => expect(mocks.getWorkspace).toHaveBeenCalledTimes(2));
  });

	it("allows an unsubscribed workspace and explains that signup credits are not included", async () => {
    mocks.getWorkspace.mockResolvedValueOnce({
      id: "workspace-1",
      name: "Personal",
      slug: "personal",
      status: "ACTIVE",
      logoUrl: null,
      publicCitationsEnabled: true,
      role: "OWNER",
      plan: "none",
      entitlementStatus: "subscription_required",
      accessMode: "full",
      capabilities: { generate: true, edit: true, publish: true, export: true, configure: true, unpublish: true },
    });

    render(<WorkspaceGeneral />);

    expect(await screen.findByText(/New workspaces do not receive free signup credits/i)).toBeVisible();
    expect(await screen.findByRole("button", { name: "Subscribe to Founding" })).toBeVisible();
  });

	it("shows subscription management and billing state to an active owner", async () => {
		mocks.getWorkspace.mockResolvedValueOnce(subscriptionWorkspace({
			subscriptionStatus: "active",
			subscriptionCurrentPeriodEnd: "2026-10-21T00:00:00Z",
		}));

		render(<WorkspaceGeneral />);

		expect(await screen.findByRole("button", { name: "Manage subscription" })).toBeVisible();
		expect(screen.getByText("Renews Oct 21, 2026.")).toBeVisible();
	});

	it("keeps period-end cancellation active and makes its date clear", async () => {
		mocks.getWorkspace.mockResolvedValueOnce(subscriptionWorkspace({
			subscriptionStatus: "canceled",
			subscriptionCancelAtPeriodEnd: true,
			subscriptionCurrentPeriodEnd: "2026-10-31T00:00:00Z",
		}));

		render(<WorkspaceGeneral />);

		expect(await screen.findByText("Cancellation scheduled. Access ends Oct 31, 2026.")).toBeVisible();
		expect(screen.getByRole("button", { name: "Manage subscription" })).toBeVisible();
	});

	it("shows a scheduled cancellation from an updated active snapshot", async () => {
		mocks.getWorkspace.mockResolvedValueOnce(subscriptionWorkspace({
			subscriptionStatus: "active",
			subscriptionCancelAtPeriodEnd: true,
			subscriptionCurrentPeriodEnd: "2026-10-31T00:00:00Z",
		}));

		render(<WorkspaceGeneral />);

		expect(await screen.findByText("Cancellation scheduled. Access ends Oct 31, 2026.")).toBeVisible();
		expect(screen.queryByText("Renews Oct 31, 2026.")).not.toBeInTheDocument();
	});

	it("shows a payment warning without removing access", async () => {
		mocks.getWorkspace.mockResolvedValueOnce(subscriptionWorkspace({ subscriptionStatus: "past_due" }));

		render(<WorkspaceGeneral />);

		expect(await screen.findByText("Payment needs attention. Your workspace remains available while Polar retries.")).toBeVisible();
		expect(screen.getByRole("button", { name: "Manage subscription" })).toBeVisible();
	});

	it("keeps a revoked workspace usable while letting its owner subscribe again", async () => {
		mocks.getWorkspace.mockResolvedValueOnce(subscriptionWorkspace({
			entitlementStatus: "revoked",
			accessMode: "full",
			capabilities: { configure: true, write: true, export: true, publish: true, unpublish: true },
			subscriptionStatus: "revoked",
		}));

		render(<WorkspaceGeneral />);

		expect(await screen.findByRole("button", { name: "Subscribe again" })).toBeVisible();
	});

	it("shows billing state but no payment actions to a member", async () => {
		mocks.getWorkspace.mockResolvedValueOnce(subscriptionWorkspace({
			role: "MEMBER",
			subscriptionStatus: "past_due",
		}));

		render(<WorkspaceGeneral />);

		expect(await screen.findByText("Payment needs attention. Your workspace remains available while Polar retries.")).toBeVisible();
		expect(screen.queryByRole("button", { name: /subscription/i })).not.toBeInTheDocument();
	});

	it("keeps the billing card visible when the portal cannot be opened", async () => {
		mocks.getWorkspace.mockResolvedValueOnce(subscriptionWorkspace());
		mocks.createSubscriptionPortal.mockRejectedValueOnce(new Error("unavailable"));

		render(<WorkspaceGeneral />);

		fireEvent.click(await screen.findByRole("button", { name: "Manage subscription" }));
		expect(await screen.findByRole("alert")).toHaveTextContent("Subscription portal could not be started.");
		expect(screen.getByRole("button", { name: "Manage subscription" })).toBeVisible();
	});
});

function subscriptionWorkspace(overrides: Record<string, unknown> = {}) {
	return {
		id: "workspace-1",
		name: "Personal",
		slug: "personal",
		status: "ACTIVE",
		logoUrl: null,
		publicCitationsEnabled: true,
		role: "OWNER",
		plan: "founding",
		entitlementStatus: "active",
		accessMode: "full",
		capabilities: { configure: true, write: true, export: true, publish: true, unpublish: true },
		subscriptionStatus: "active",
		subscriptionCancelAtPeriodEnd: false,
		subscriptionCurrentPeriodEnd: "2026-10-21T00:00:00Z",
		subscriptionEventAt: "2026-09-21T00:00:00Z",
		...overrides,
	};
}
