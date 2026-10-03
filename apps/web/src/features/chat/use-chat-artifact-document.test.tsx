// @vitest-environment jsdom
import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Artifact } from "@plot/api-client";
const mocks = vi.hoisted(() => ({ getArtifact: vi.fn() }));
vi.mock("@/lib/api-client", () => ({ getSelectedWorkspaceId: () => "workspace-1", plotApiClient: { getArtifact: mocks.getArtifact } }));
import { useChatArtifactDocument } from "./use-chat-artifact-document";
const artifact = (id: string) => ({ id, title: id, variant: { id: `variant-${id}`, revisionNumber: 1, sentences: [], sources: [] } }) as unknown as Artifact;

describe("Chat document selection", () => {
  beforeEach(() => mocks.getArtifact.mockReset().mockImplementation(async (id) => artifact(id)));
  it("retains an existing dirty editor when a new terminal document becomes available, until explicitly opened", async () => {
    const { result, rerender } = renderHook((props) => useChatArtifactDocument(props), { initialProps: { requestedArtifactId: "old", selectedActivityArtifactId: "old", retainCurrentArtifact: false, retryFinalRead: false } });
    await waitFor(() => expect(result.current.currentArtifact?.id).toBe("old"));
    const draft = { lexicalContent: {}, statements: [{ body: "My unsaved edit" }] } as Parameters<typeof result.current.onDraftChange>[0];
    act(() => result.current.onDraftChange(draft));
    rerender({ requestedArtifactId: "new", selectedActivityArtifactId: "new", retainCurrentArtifact: true, retryFinalRead: true });
    await act(async () => { await Promise.resolve(); });
    expect(result.current.currentArtifact?.id).toBe("old");
    expect(mocks.getArtifact).toHaveBeenCalledTimes(1);
    expect(result.current.drafts.old).toEqual(draft);
    rerender({ requestedArtifactId: "new", selectedActivityArtifactId: "new", retainCurrentArtifact: false, retryFinalRead: true });
    await waitFor(() => expect(result.current.currentArtifact?.id).toBe("new"));
    rerender({ requestedArtifactId: "old", selectedActivityArtifactId: "old", retainCurrentArtifact: false, retryFinalRead: false });
    await waitFor(() => expect(result.current.currentArtifact?.id).toBe("old"));
    expect(result.current.drafts.old).toEqual(draft);
    expect(result.current.saveState).toBe("dirty");
  });
});
