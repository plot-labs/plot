// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { PublishDialog } from "./publish-dialog";
import { PlotApiError, type Artifact, type PlotApiClient } from "@plot/api-client";
import { publicChangelogEntryUrl } from "@/lib/public-changelog-url";

vi.mock("@/lib/use-workspace-entitlement", () => ({
  useWorkspaceEntitlement: () => ({
    plan: "founding",
    entitlementStatus: "active",
    accessMode: "full",
    trialEndsAt: null,
    capabilities: {
      generate: true,
      edit: true,
      publish: true,
      export: true,
      configure: true,
      unpublish: true,
    },
  }),
}));

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
  it("explains hosted public citation scope before publish", () => {
    render(
      <PublishDialog
        pack={{
          ...pack,
          variant: {
            ...pack.variant,
            sentences: [{
              ...pack.variant.sentences[0],
              citations: [{
                evidenceId: "ev-1",
                provider: "GITHUB",
                sourceLabel: "acme/app#42",
                originalUrl: "https://github.com/acme/app/pull/42",
              }],
            }],
          },
        }}
        client={{} as PlotApiClient}
        presentation="inline"
      />,
    );

    expect(screen.getByText(/Hosted publish shows the changelog body and public citations only/i)).toBeInTheDocument();
    expect(screen.getByText("acme/app#42")).toBeInTheDocument();
    expect(screen.getByText(/does not remove secrets/i)).toBeInTheDocument();
  });

  it("publishes the current revision and shows the public URL", async () => {
    const publishArtifactVariant = vi.fn().mockResolvedValue({
      entryId: "entry-1",
      entrySlug: "v2.4.0",
      publicPath: "/acme/changelog/v2.4.0",
      publishedAt: "2026-08-31T12:00:00Z",
    });
    const recordProductDeliveryEvent = vi.fn().mockResolvedValue({
      id: "delivery-1",
      kind: "EXTERNAL_DELIVERY_CONFIRMED",
      duplicate: false,
    });

    render(<PublishDialog pack={pack} client={{ publishArtifactVariant, recordProductDeliveryEvent } as unknown as PlotApiClient} />);
    fireEvent.click(screen.getByRole("button", { name: "Publish changelog" }));

    await screen.findByText("Changelog published");
    expect(screen.getByText(/\/acme\/changelog\/v2\.4\.0/)).toBeInTheDocument();
    expect(publishArtifactVariant).toHaveBeenCalledWith("variant-1", {
      expectedRevisionNumber: 3,
      acknowledgeUnresolved: false,
      acknowledgedWarningKeys: [],
    });
    expect(screen.getByRole("link", { name: "View live" })).toHaveAttribute("href", publicChangelogEntryUrl("/acme/changelog/v2.4.0"));

    fireEvent.click(screen.getByRole("button", { name: "I shared this outside Plot" }));
    await waitFor(() => expect(recordProductDeliveryEvent).toHaveBeenCalledWith(
      "variant-1",
      expect.objectContaining({ kind: "EXTERNAL_DELIVERY_CONFIRMED", entryId: "entry-1" }),
    ));
    expect(await screen.findByRole("button", { name: "Marked as shared" })).toBeDisabled();
  });

  it("copies the public URL after publish", async () => {
    const publishArtifactVariant = vi.fn().mockResolvedValue({
      entryId: "entry-1",
      entrySlug: "v2.4.0",
      publicPath: "/acme/changelog/v2.4.0",
      publishedAt: "2026-08-31T12:00:00Z",
    });
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });

    render(<PublishDialog pack={pack} client={{ publishArtifactVariant } as unknown as PlotApiClient} />);
    fireEvent.click(screen.getByRole("button", { name: "Publish changelog" }));
    fireEvent.click(await screen.findByRole("button", { name: "Copy link" }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(publicChangelogEntryUrl("/acme/changelog/v2.4.0")));
    expect(await screen.findByRole("button", { name: "Copied" })).toBeInTheDocument();
  });

  it("unpublishes a live changelog without consuming a publish action", async () => {
    const unpublishArtifactVariant = vi.fn().mockResolvedValue({
      entryId: "entry-1",
      entrySlug: "v2.4.0",
      publicPath: "/acme/changelog/v2.4.0",
      publishedAt: "2026-08-31T12:00:00Z",
      unpublishedAt: "2026-09-07T12:00:00Z",
    });
    const onPackChange = vi.fn();

    render(
      <PublishDialog
        pack={{
          ...pack,
          publication: {
            entryId: "entry-1",
            entrySlug: "v2.4.0",
            publicPath: "/acme/changelog/v2.4.0",
            publishedAt: "2026-08-31T12:00:00Z",
          },
        }}
        client={{ unpublishArtifactVariant } as unknown as PlotApiClient}
        onPackChange={onPackChange}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Unpublish changelog" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm unpublish" }));

    await screen.findByText("Public changelog withdrawn. The internal snapshot is kept.");
    expect(unpublishArtifactVariant).toHaveBeenCalledWith("variant-1");
    expect(onPackChange).toHaveBeenCalledWith(expect.objectContaining({ publication: null }));
    expect(screen.getByRole("button", { name: "Publish changelog" })).toBeInTheDocument();
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
        publicPath: "/acme/changelog/v2.4.0",
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
