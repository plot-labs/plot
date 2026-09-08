import { describe, expect, it } from "vitest";

import { emptyChatBriefDraft, toContentBrief } from "./chat-brief-panel";

describe("chat brief CTA destinations", () => {
	it("only includes a destination after explicit confirmation", () => {
		const draft = {
			...emptyChatBriefDraft(),
			purpose: "Announce the beta",
			ctaDestinationLabel: "Join the beta",
			ctaDestinationUrl: "https://plot.test/join",
		};

		expect(toContentBrief(draft)?.destinations).toEqual([]);
		expect(toContentBrief({ ...draft, ctaDestinationConfirmed: true })?.destinations).toEqual([
			expect.objectContaining({ label: "Join the beta", url: "https://plot.test/join" }),
		]);
	});

	it("drops a previously confirmed destination when its URL is edited", () => {
		const brief = toContentBrief({
			...emptyChatBriefDraft(),
			purpose: "Announce the beta",
			ctaDestinationLabel: "Join",
			ctaDestinationUrl: "https://plot.test/join",
			ctaDestinationConfirmed: true,
		});

		expect(toContentBrief({
			...emptyChatBriefDraft(),
			purpose: "Announce the beta",
			ctaDestinationLabel: "Join",
			ctaDestinationUrl: "https://plot.test/changed",
			ctaDestinationId: brief?.destinations?.[0]?.id ?? "destination-1",
			ctaDestinationConfirmed: false,
		})?.destinations).toEqual([]);
	});
});
