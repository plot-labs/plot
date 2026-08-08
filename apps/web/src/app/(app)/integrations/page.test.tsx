import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ redirect: vi.fn() }));

vi.mock("next/navigation", () => ({ redirect: mocks.redirect }));

import LegacyIntegrationsPage from "./page";

describe("LegacyIntegrationsPage", () => {
  beforeEach(() => mocks.redirect.mockReset());

  it("redirects the legacy route to workspace settings", async () => {
    await LegacyIntegrationsPage({ searchParams: Promise.resolve({}) });

    expect(mocks.redirect).toHaveBeenCalledWith("/settings/integrations");
  });

  it("preserves allowlisted GitHub callback parameters", async () => {
    await LegacyIntegrationsPage({
      searchParams: Promise.resolve({
        githubConnection: "connection-1",
        githubError: "failed",
        privateState: "discard-me",
      }),
    });

    expect(mocks.redirect).toHaveBeenCalledWith(
      "/settings/integrations?githubConnection=connection-1&githubError=failed",
    );
  });
});
