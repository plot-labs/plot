package com.plot.api.content

import com.plot.api.routine.dto.ContentBriefRequest
import com.plot.api.routine.dto.CtaDestinationRequest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CtaDestinationContractTest {
	private val destinationId = UUID.fromString("00000000-0000-4000-8000-000000000001")

	@Test
	fun `only confirmed absolute HTTPS destinations enter the domain brief`() {
		val brief = ContentBriefRequest(
			destinations = listOf(CtaDestinationRequest(destinationId, "Join the beta", "https://plot.test/join")),
		).toDomain()

		assertEquals(destinationId, brief.destinations.single().id)
		assertEquals("Join the beta", brief.destinations.single().label)
		assertEquals("https://plot.test/join", brief.destinations.single().url)
		assertTrue(!brief.isBlank())
	}

	@Test
	fun `destination identity and values participate in the frozen brief fingerprint`() {
		val first = ContentBrief(destinations = listOf(ContentBriefDestination(destinationId, "Join", "https://plot.test/join")))
		val changed = first.copy(destinations = listOf(ContentBriefDestination(destinationId, "Start", "https://plot.test/start")))

		assertNotEquals(first.canonicalFingerprint(), changed.canonicalFingerprint())
	}

	@Test
	fun `invalid destination schemes credentials and duplicate ids are rejected`() {
		listOf(
			"http://plot.test/join",
			"/join",
			"javascript:alert(1)",
			"https://user:pass@plot.test/join",
			"https://plot.test:444/join",
		).forEach { url ->
			assertFailsWith<RuntimeException> {
				ContentBriefRequest(
					destinations = listOf(CtaDestinationRequest(destinationId, "Join", url)),
				).toDomain()
			}
		}

		assertFailsWith<RuntimeException> {
			ContentBriefRequest(
				destinations = listOf(
					CtaDestinationRequest(destinationId, "One", "https://plot.test/one"),
					CtaDestinationRequest(destinationId, "Two", "https://plot.test/two"),
				),
			).toDomain()
		}
	}
}
