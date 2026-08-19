package com.plot.api.config

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test

class PlotAiPropertiesTest {
	@Test
	fun workerPollDelayDefaultsToFiveSeconds() {
		assertEquals(Duration.ofSeconds(5), PlotAiProperties().workerPollDelay)
	}

	@Test
	fun workerPollDelayMustBePositive() {
		assertFailsWith<IllegalArgumentException> {
			PlotAiProperties(workerPollDelay = Duration.ZERO)
		}
	}

	@Test
	fun gpt56LunaProIsAnAllowedOpenRouterProfile() {
		PlotAiProperties(
			enabled = true,
			model = PlotAiProperties.GPT_5_6_LUNA_PRO_MODEL,
			routingProvider = "openai",
		)
	}

	@Test
	fun nemotronFreeUsesPromptedJsonInsteadOfNativeStructuredOutput() {
		val properties = PlotAiProperties(
			enabled = true,
			model = PlotAiProperties.NEMOTRON_3_5_LIGHTNING_FREE_MODEL,
			routingProvider = "nvidia",
			allowDataCollection = true,
		)

		assertFalse(properties.supportsNativeStructuredOutput)
		assertEquals("allow", properties.openRouterProviderPolicy["data_collection"])
		assertEquals(mapOf("effort" to "none", "exclude" to true), properties.openRouterExtraBody["reasoning"])
		assertEquals(mapOf("type" to "json_object"), properties.openRouterExtraBody["response_format"])
		assertEquals(0.0, properties.openRouterExtraBody["temperature"])
	}

	@Test
	fun nemotronFreeRequiresExplicitDataCollectionConsent() {
		assertFailsWith<IllegalArgumentException> {
			PlotAiProperties(
				enabled = true,
				model = PlotAiProperties.NEMOTRON_3_5_LIGHTNING_FREE_MODEL,
				routingProvider = "nvidia",
			)
		}
	}
}
