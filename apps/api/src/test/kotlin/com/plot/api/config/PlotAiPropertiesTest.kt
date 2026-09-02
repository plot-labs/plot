package com.plot.api.config

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PlotAiPropertiesTest {
	@Test
	fun gpt56LunaProIsAnAllowedOpenRouterProfile() {
		val properties = PlotAiProperties(
			enabled = true,
			model = PlotAiProperties.GPT_5_6_LUNA_PRO_MODEL,
			routingProvider = "openai",
		)

		assertEquals("deny", properties.openRouterProviderPolicy["data_collection"])
	}

	@Test
	fun deepSeekV4FlashIsAllowedWithoutDataCollection() {
		val properties = PlotAiProperties(
			enabled = true,
			model = PlotAiProperties.DEEPSEEK_V4_FLASH_MODEL,
			routingProvider = "deepinfra",
		)

		assertTrue(properties.supportsTemperature)
		assertEquals("deny", properties.openRouterProviderPolicy["data_collection"])
	}

	@Test
	fun freeTrainingModelIsNotSupported() {
		assertFailsWith<IllegalArgumentException> {
			PlotAiProperties(
				enabled = true,
				model = "nvidia/nemotron-3.5-lightning:free",
				routingProvider = "nvidia",
			)
		}
	}
}
