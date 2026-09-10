import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ redirect: vi.fn() }));

vi.mock("next/navigation", () => ({ redirect: mocks.redirect }));

import LegacyArtifactsPage from "./artifacts/page";
import LegacyUpdatesPage from "./updates/page";
import LegacyRoutinesPage from "./routines/page";
import LegacyAutonomyPage from "./settings/autonomy/page";
import LegacyActivityPage from "./activity/page";
import LegacyAutomationActivityPage from "./automation/activity/page";

describe("legacy route pages", () => {
  beforeEach(() => mocks.redirect.mockReset());

  it("redirects contents aliases while preserving artifact filters", async () => {
    await LegacyArtifactsPage({
      searchParams: Promise.resolve({ artifact: "artifact-1", view: "draft" }),
    });
    expect(mocks.redirect).toHaveBeenCalledWith("/contents?artifact=artifact-1&view=draft");

    mocks.redirect.mockReset();
    await LegacyUpdatesPage({
      searchParams: Promise.resolve({ artifact: ["artifact-2", "artifact-3"] }),
    });
    expect(mocks.redirect).toHaveBeenCalledWith("/contents?artifact=artifact-2&artifact=artifact-3");
  });

  it("redirects automation aliases to Automation", async () => {
    await LegacyRoutinesPage({ searchParams: Promise.resolve({ routine: "routine-1" }) });
    expect(mocks.redirect).toHaveBeenCalledWith("/automation?routine=routine-1");

    mocks.redirect.mockReset();
    await LegacyAutonomyPage({ searchParams: Promise.resolve({}) });
    expect(mocks.redirect).toHaveBeenCalledWith("/automation");
  });

  it("redirects activity aliases to Chat while preserving the chat selection", async () => {
    await LegacyActivityPage({ searchParams: Promise.resolve({ chat: "chat-1" }) });
    expect(mocks.redirect).toHaveBeenCalledWith("/chat?chat=chat-1");

    mocks.redirect.mockReset();
    await LegacyAutomationActivityPage({ searchParams: Promise.resolve({ chat: "chat-2" }) });
    expect(mocks.redirect).toHaveBeenCalledWith("/chat?chat=chat-2");
  });
});
