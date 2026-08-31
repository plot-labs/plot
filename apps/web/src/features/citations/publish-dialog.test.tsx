// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { PublishDialog } from "./publish-dialog";
import { PlotApiError, type Artifact, type PlotApiClient } from "@plot/api-client";
import { publicChangelogEntryUrl } from "@/lib/public-changelog-url";

const pack: Artifact = {
  id: "pack-1",
  status: "NEEDS_REVIEW",
  title: "July changelog",
  variant: {
    id: "variant-1",
    status: "NEEDS_REVIEW",
    revisionId: "artifact-revision-1",
    revisionNumber: 3,
    lexicalContent: {
      root: {
        children: [{
          children: [{ detail: 0, format: 0, mode: "normal", style: "", text: "A claim.", type: "text", version: 1 }],
          direction: null,
          format: "",
          indent: 0,
          type: "paragraph",
          version: 1,
        }],
        direction: null,
        format: "",
        indent: 0,
        type: "root",
        version: 1,
      },
    },
    sentences: [
      { id: "sentence-7", revisionId: "rev-7", revisionNumber: 2, orderIndex: 0, body: "A claim.", origin: "USER_MODIFIED", citations: [] },
    ],
    sources: [],
  },
};

describe("PublishDialog", () => {
  it("publishes the current revision and shows the public URL", async () => {
    const publishArtifactVariant = vi.fn().mockResolvedValue({
      entryId: "entry-1",
      entrySlug: "v2.4.0",
      publicPath: "/changelog/acme/v2.4.0",
      publishedAt: "2026-08-31T12:00:00Z",
    });

    render(<PublishDialog pack={pack} client={{ publishArtifactVariant } as unknown as PlotApiClient} />);
    fireEvent.click(screen.getByRole("button", { name: "Publish changelog" }));

    await screen.findByText("Changelog published");
    expect(screen.getByText(/\/changelog\/acme\/v2\.4\.0/)).toBeInTheDocument();
    expect(publishArtifactVariant).toHaveBeenCalledWith("variant-1", {
      expectedRevisionNumber: 3,
      acknowledgeUnresolved: false,
      acknowledgedWarningKeys: [],
    });
    expect(screen.getByRole("link", { name: "View live" })).toHaveAttribute("href", publicChangelogEntryUrl("/changelog/acme/v2.4.0"));
  });

  it("copies the public URL after publish", async () => {
    const publishArtifactVariant = vi.fn().mockResolvedValue({
      entryId: "entry-1",
      entrySlug: "v2.4.0",
      publicPath: "/changelog/acme/v2.4.0",
      publishedAt: "2026-08-31T12:00:00Z",
    });
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });

    render(<PublishDialog pack={pack} client={{ publishArtifactVariant } as unknown as PlotApiClient} />);
    fireEvent.click(screen.getByRole("button", { name: "Publish changelog" }));
    fireEvent.click(await screen.findByRole("button", { name: "Copy link" }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(publicChangelogEntryUrl("/changelog/acme/v2.4.0")));
    expect(await screen.findByRole("button", { name: "Copied" })).toBeInTheDocument();
  });

  it("requires explicit confirmation for unresolved statements", async () => {
    const publishArtifactVariant = vi
      .fn()
      .mockRejectedValueOnce(new PlotApiError(409, "PUBLISH_CONFIRMATION_REQUIRED", "Confirm", {
        warnings: [{ key: "warning-key-1", sentenceNumber: 1, excerpt: "A claim." }],
      }))
      .mockResolvedValueOnce({
        entryId: "entry-1",
        entrySlug: "v2.4.0",
        publicPath: "/changelog/acme/v2.4.0",
        publishedAt: "2026-08-31T12:00:00Z",
      });

    render(
      <>
        <div data-statement-id="sentence-7" tabIndex={-1}>Draft sentence</div>
        <PublishDialog pack={pack} client={{ publishArtifactVariant } as unknown as PlotApiClient} />
      </>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Publish changelog" }));
    const affected = await screen.findByRole("button", { name: /Statement 1 — “A claim\.”/ });
    expect(screen.queryByText(/sentence-7|rev-7/)).not.toBeInTheDocument();
    fireEvent.click(affected);
    expect(screen.getByText("Draft sentence")).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: /confirm and publish/i }));

    await screen.findByText("Changelog published");
    expect(publishArtifactVariant).toHaveBeenNthCalledWith(1, "variant-1", expect.objectContaining({
      expectedRevisionNumber: 3,
      acknowledgedWarningKeys: [],
    }));
    expect(publishArtifactVariant).toHaveBeenNthCalledWith(2, "variant-1", expect.objectContaining({
      expectedRevisionNumber: 3,
      acknowledgedWarningKeys: ["warning-key-1"],
    }));
  });
});
