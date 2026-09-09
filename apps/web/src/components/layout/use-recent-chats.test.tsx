// @vitest-environment jsdom
import { act, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useRecentChats } from "./use-recent-chats";

const api = vi.hoisted(() => ({ listSessions: vi.fn() }));
vi.mock("@/lib/api-client", () => ({ plotApiClient: api }));
afterEach(() => { vi.useRealTimers(); api.listSessions.mockReset(); });

it("refreshes background conversations and stops polling on unmount", async () => {
  vi.useFakeTimers();
  api.listSessions.mockResolvedValueOnce([]).mockResolvedValue([{ id: "automated", title: "Release notes" }]);
  const { result, unmount } = renderHook(() => useRecentChats({ settingsMode: false, selectedWorkspaceId: "a" }));
  await act(async () => {});
  expect(result.current).toEqual([]);
  await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
  expect(result.current[0].id).toBe("automated");
  unmount();
  await vi.advanceTimersByTimeAsync(30_000);
  expect(api.listSessions).toHaveBeenCalledTimes(2);
});

it("hides the previous workspace and ignores late responses from it", async () => {
  let resolveOld!: (value: { id: string }[]) => void;
  api.listSessions.mockResolvedValueOnce([{ id: "old" }])
    .mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
    .mockResolvedValueOnce([{ id: "new" }]);
  const { result, rerender } = renderHook(({ workspace }) => useRecentChats({ settingsMode: false, selectedWorkspaceId: workspace }), { initialProps: { workspace: "a" } });
  await act(async () => {});
  expect(result.current[0].id).toBe("old");
  act(() => { window.dispatchEvent(new Event("focus")); });
  rerender({ workspace: "b" });
  expect(result.current).toEqual([]);
  await act(async () => {});
  await act(async () => { resolveOld([{ id: "stale" }]); });
  expect(result.current[0].id).toBe("new");
});
