// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SkillSelector } from "./skill-selector";

const mocks = vi.hoisted(() => ({ listSkills: vi.fn(), createSkill: vi.fn() }));
vi.mock("@/lib/api-client", () => ({ plotApiClient: mocks }));
const guides = Array.from({ length: 5 }, (_, i) => ({ id: `skill-${i}`, name: `guide-${i}`, description: `Guide ${i}`, revision: 1, isSystem: true }));
function Harness() {
  const [value, onChange] = useState<string[]>([]);
  return <SkillSelector value={value} onChange={onChange} />;
}

beforeEach(() => { mocks.listSkills.mockReset().mockResolvedValue(guides); mocks.createSkill.mockReset(); });
describe("SkillSelector", () => {
  it("limits selection and resets workspace-specific choices", async () => {
    render(<Harness />);
    fireEvent.click(screen.getByText("Skills · Optional"));
    const boxes = await screen.findAllByRole("checkbox");
    for (const box of boxes.slice(0, 4)) fireEvent.click(box);
    expect(boxes[4]).toBeDisabled();
    expect(screen.getByText("Skills · 4 selected")).toBeTruthy();
    fireEvent(window, new Event("plot:workspace-changed"));
    await waitFor(() => expect(screen.getByText("Skills · Optional")).toBeTruthy());
    await waitFor(() => expect(mocks.listSkills).toHaveBeenCalledTimes(2));
    expect((await screen.findAllByRole("checkbox"))[0]).not.toBeChecked();
  });

  it("creates and selects a reusable guide without submitting its parent form", async () => {
    const submit = vi.fn();
    mocks.createSkill.mockResolvedValue({ id: "new-skill", name: "my-guide", description: "Short updates", content: "Keep it brief.", revision: 1 });
    render(<form onSubmit={submit}><Harness /></form>);
    fireEvent.click(screen.getByText("Skills · Optional"));
    await screen.findAllByRole("checkbox");
    fireEvent.click(screen.getByRole("button", { name: "Create skill" }));
    fireEvent.change(screen.getByLabelText("Skill name"), { target: { value: "my-guide" } });
    fireEvent.change(screen.getByLabelText("When to use it"), { target: { value: "Short updates" } });
    fireEvent.change(screen.getByLabelText("Instructions"), { target: { value: "Keep it brief." } });
    fireEvent.click(screen.getByRole("button", { name: "Save skill" }));
    await waitFor(() => expect(mocks.createSkill).toHaveBeenCalledWith({ name: "my-guide", description: "Short updates", content: "Keep it brief." }, expect.objectContaining({ signal: expect.any(AbortSignal) })));
    expect(await screen.findByRole("checkbox", { name: /my-guide/ })).toBeChecked();
    expect(submit).not.toHaveBeenCalled();
  });

  it("shows a recoverable loading error", async () => {
    mocks.listSkills.mockRejectedValueOnce(new Error("offline"));
    render(<Harness />);
    fireEvent.click(screen.getByText("Skills · Optional"));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findAllByRole("checkbox")).toHaveLength(5);
  });
});
