import { describe, expect, it } from "vitest";

import { parseWaitlistPayload } from "./waitlist";

describe("parseWaitlistPayload", () => {
  it("keeps email, pain channel, and optional company for persistence", () => {
    expect(parseWaitlistPayload({
      email: " Founder@Acme.dev ",
      role: "founder",
      painChannel: "changelog",
      company: " Acme ",
      website: "",
    })).toEqual({
      email: "founder@acme.dev",
      role: "founder",
      painChannel: "changelog",
      company: "Acme",
      website: undefined,
    });
  });

  it("rejects payloads without a pain channel", () => {
    expect(parseWaitlistPayload({
      email: "founder@acme.dev",
      company: "Acme",
    })).toBeNull();
  });
});
