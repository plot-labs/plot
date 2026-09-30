package com.plot.api.content

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CtaDestinationContractTest {
	private val destinationId = UUID.fromString("00000000-0000-4000-8000-000000000001")

	@Test
	fun `only confirmed absolute HTTPS destinations enter the domain brief`() {
		val brief = ContentBrief(
			destinations = listOf(ContentBriefDestination(destinationId, "Join the beta", "https://plot.test/join")),
		)

		assertEquals(destinationId, brief.destinations.single().id)
		assertEquals("Join the beta", brief.destinations.single().label)
		assertEquals("https://plot.test/join", brief.destinations.single().url)
		assertTrue(!brief.isBlank())
	}

	@Test
	fun `invalid destination schemes and credentials are rejected`() {
		listOf(
			"http://plot.test/join",
			"/join",
			"javascript:alert(1)",
			"https://user:pass@plot.test/join",
			"https://plot.test:444/join",
		).forEach { url ->
			assertFailsWith<IllegalArgumentException> {
				ContentBriefDestination(destinationId, "Join", url)
			}
		}
	}
}
