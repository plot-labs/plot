// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { SourceReference } from "@plot/api-client";

const mocks = vi.hoisted(() => ({
  listSourceReferences: vi.fn(),
  createChatAgentRun: vi.fn(),
  locationAssign: vi.fn(),
  canGenerate: true,
}));

vi.mock("@/lib/api-client", () => ({
  plotApiClient: {
    listSourceReferences: mocks.listSourceReferences,
    createChatAgentRun: mocks.createChatAgentRun,
  },
}));

vi.mock("@/lib/use-workspace-entitlement", () => ({
  useWorkspaceEntitlement: () => ({
    plan: "founding",
    entitlementStatus: "active",
    accessMode: mocks.canGenerate ? "full" : "read_only",
    capabilities: { generate: mocks.canGenerate },
  }),
}));

// The composer has its own tests; here it only needs to hand a request to the workspace.
vi.mock("@/features/chat/chat-composer", () => ({
  ChatComposer: ({ onSubmit, busy, canGenerate, placeholder }: {
    onSubmit: (message: string, skillIds: string[], model: string, effort: string) => void;
    busy?: boolean;
    canGenerate?: boolean;
    placeholder?: string;
  }) => (
    <button
      type="button"
      disabled={busy || canGenerate === false}
      data-placeholder={placeholder}
      onClick={() => onSubmit("Write a changelog", ["skill-1"], "auto", "medium")}
    >
      Start draft
    </button>
  ),
}));

import { CreateContentWorkspace } from "./create-content-workspace";

function source(id: string, overrides: Partial<SourceReference> = {}): SourceReference {
  return {
    id,
    sourceScopeId: "scope-plot",
    provider: "GITHUB",
    sourceKind: "pull_request",
    sourceLabel: `Change ${id}`,
    repositoryLabel: "acme/plot",
    title: `Change ${id}`,
    body: null,
    originalUrl: `https://github.com/acme/plot/pull/${id}`,
    sourceCreatedAt: "2026-10-01T10:00:00Z",
    ...overrides,
  };
}

async function openPicker() {
  fireEvent.click(screen.getByRole("button", { name: /Choose changes|Edit changes/ }));
  await screen.findAllByRole("checkbox");
}

describe("CreateContentWorkspace", () => {
  beforeEach(() => {
    mocks.canGenerate = true;
    mocks.listSourceReferences.mockReset().mockResolvedValue([
      source("1", { sourceLabel: "Add export dialog", sourceCreatedAt: "2026-10-01T10:00:00Z" }),
      source("2", { sourceLabel: "Fix publish warning", sourceCreatedAt: "2026-10-03T10:00:00Z" }),
      source("3", { sourceScopeId: "scope-docs", repositoryLabel: "acme/docs", sourceLabel: "Update install guide" }),
      source("4", { sourceKind: "commit", sourceLabel: "Bump lockfile" }),
    ]);
    mocks.createChatAgentRun.mockReset().mockResolvedValue({ id: "run-1", chatId: "chat-1" });
    mocks.locationAssign.mockReset();
    Object.defineProperty(window, "location", { configurable: true, value: { ...window.location, assign: mocks.locationAssign } });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("starts with automatic changes and does not load the list until asked", async () => {
    render(<CreateContentWorkspace />);

    expect(screen.getByText("Automatic. Plot finds the relevant pull requests and commits itself.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Choose changes" })).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(screen.getByRole("button", { name: "Start draft" }));

    await waitFor(() => expect(mocks.createChatAgentRun).toHaveBeenCalled());
    expect(mocks.createChatAgentRun.mock.calls[0][0].writingBlockIds).toEqual([]);
    expect(mocks.listSourceReferences).not.toHaveBeenCalled();
  });

  it("lists pull requests by repository with the newest first", async () => {
    render(<CreateContentWorkspace />);
    await openPicker();

    const plot = screen.getByRole("group", { name: "acme/plot" });
    const labels = within(plot).getAllByRole("checkbox").map((box) => box.closest("li")?.textContent ?? "");
    expect(labels[0]).toContain("Fix publish warning");
    expect(labels[1]).toContain("Add export dialog");
    expect(within(screen.getByRole("group", { name: "acme/docs" })).getByText("Update install guide")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Hide list" })).toHaveAttribute("aria-expanded", "true");
  });

  it("leaves commits out until the reader includes them", async () => {
    render(<CreateContentWorkspace />);
    await openPicker();

    expect(screen.queryByRole("checkbox", { name: /Bump lockfile/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: "Include commits" }));

    expect(screen.getByRole("checkbox", { name: /Bump lockfile/ })).toBeInTheDocument();
    expect(screen.getByRole("searchbox", { name: "Search changes" })).toHaveAttribute("placeholder", "Search pull requests and commits");
  });

  it("shows commits straight away when nothing else was imported", async () => {
    mocks.listSourceReferences.mockResolvedValue([source("4", { sourceKind: "commit", sourceLabel: "Bump lockfile" })]);
    render(<CreateContentWorkspace />);
    await openPicker();

    expect(screen.getByRole("checkbox", { name: /Bump lockfile/ })).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Include commits" })).not.toBeInTheDocument();
  });

  it("starts a draft from the chosen changes and opens its chat", async () => {
    render(<CreateContentWorkspace />);
    await openPicker();

    fireEvent.click(screen.getByRole("checkbox", { name: /Add export dialog/ }));
    fireEvent.click(screen.getByRole("checkbox", { name: /Update install guide/ }));
    expect(screen.getByText("Plot will write from the 2 changes you chose.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Start draft" }));

    await waitFor(() => expect(mocks.locationAssign).toHaveBeenCalledWith("/chat?chat=chat-1&agent=run-1"));
    expect(mocks.createChatAgentRun).toHaveBeenCalledWith(
      {
        instruction: "Write a changelog",
        writingBlockIds: ["1", "3"],
        skillIds: ["skill-1"],
        model: "auto",
        reasoningEffort: "medium",
      },
      expect.any(String),
    );
  });

  it("keeps the selection when the list is hidden", async () => {
    render(<CreateContentWorkspace />);
    await openPicker();

    fireEvent.click(screen.getByRole("checkbox", { name: /Add export dialog/ }));
    fireEvent.click(screen.getByRole("button", { name: "Hide list" }));

    expect(screen.queryByRole("checkbox", { name: /Add export dialog/ })).not.toBeInTheDocument();
    expect(screen.getByText("Plot will write from the change you chose.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Edit changes" }));
    expect(screen.getByRole("checkbox", { name: /Add export dialog/ })).toBeChecked();
    expect(mocks.listSourceReferences).toHaveBeenCalledTimes(1);
  });

  it("stops at twenty selected changes", async () => {
    mocks.listSourceReferences.mockResolvedValue(Array.from({ length: 22 }, (_, index) => source(String(index + 1))));
    render(<CreateContentWorkspace />);
    await openPicker();

    const boxes = screen.getAllByRole("checkbox");
    boxes.slice(0, 20).forEach((box) => fireEvent.click(box));

    expect(screen.getByText("20 of 20 selected. Remove one to choose another.")).toBeInTheDocument();
    expect(boxes[20]).toBeDisabled();
    expect(boxes[0]).toBeEnabled();

    fireEvent.click(boxes[0]);
    expect(boxes[20]).toBeEnabled();
  });

  it("filters the list by search text and can clear the selection", async () => {
    render(<CreateContentWorkspace />);
    await openPicker();

    fireEvent.click(screen.getByRole("checkbox", { name: /Add export dialog/ }));
    fireEvent.change(screen.getByRole("searchbox", { name: "Search changes" }), { target: { value: "install" } });

    expect(screen.queryByRole("checkbox", { name: /Add export dialog/ })).not.toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: /Update install guide/ })).toBeInTheDocument();
    // A change hidden by the search stays selected.
    expect(screen.getByText("1 of 20 selected")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Clear selection" }));
    expect(screen.getByText("0 of 20 selected")).toBeInTheDocument();
  });

  it("points to Connections when nothing has been imported, and still allows a draft", async () => {
    mocks.listSourceReferences.mockResolvedValue([]);
    render(<CreateContentWorkspace />);
    fireEvent.click(screen.getByRole("button", { name: "Choose changes" }));

    expect(await screen.findByRole("link", { name: "Connections" })).toHaveAttribute("href", "/settings/integrations");
    expect(screen.getByRole("button", { name: "Start draft" })).toBeEnabled();
  });

  it("offers a retry when the changes fail to load", async () => {
    mocks.listSourceReferences.mockRejectedValueOnce(new Error("offline"));
    render(<CreateContentWorkspace />);
    fireEvent.click(screen.getByRole("button", { name: "Choose changes" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Your changes could not be loaded");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByRole("checkbox", { name: /Add export dialog/ })).toBeInTheDocument();
  });

  it("shows the server's reason and keeps the selection when the draft cannot start", async () => {
    mocks.createChatAgentRun.mockRejectedValueOnce(Object.assign(new Error("A selected source item is unavailable"), { status: 400 }));
    render(<CreateContentWorkspace />);
    await openPicker();

    fireEvent.click(screen.getByRole("checkbox", { name: /Add export dialog/ }));
    fireEvent.click(screen.getByRole("button", { name: "Start draft" }));

    expect(await screen.findByText("A selected source item is unavailable")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: /Add export dialog/ })).toBeChecked();
    expect(mocks.locationAssign).not.toHaveBeenCalled();
  });

  it("drops the selection and closes the list when the workspace changes", async () => {
    render(<CreateContentWorkspace />);
    await openPicker();

    fireEvent.click(screen.getByRole("checkbox", { name: /Add export dialog/ }));
    mocks.listSourceReferences.mockResolvedValue([source("9", { sourceLabel: "Other workspace change" })]);
    fireEvent(window, new CustomEvent("plot:workspace-changed", { detail: { id: "workspace-2" } }));

    expect(await screen.findByText("Automatic. Plot finds the relevant pull requests and commits itself.")).toBeInTheDocument();
    await openPicker();
    expect(screen.getByRole("checkbox", { name: /Other workspace change/ })).not.toBeChecked();
    expect(screen.queryByRole("checkbox", { name: /Add export dialog/ })).not.toBeInTheDocument();
  });

  it("does not let a workspace without generation start a draft", () => {
    mocks.canGenerate = false;
    render(<CreateContentWorkspace />);

    expect(screen.getByRole("button", { name: "Start draft" })).toBeDisabled();
    expect(screen.getByText(/cannot start new responses/)).toBeInTheDocument();
  });
});
