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

	@Test
	fun notraChatModelsAreSupportedOpenRouterProfiles() {
		val models = setOf(
			PlotAiProperties.CLAUDE_OPUS_5_MODEL,
			PlotAiProperties.CLAUDE_OPUS_4_8_MODEL,
			PlotAiProperties.CLAUDE_SONNET_5_MODEL,
			PlotAiProperties.CLAUDE_SONNET_4_6_MODEL,
			PlotAiProperties.CLAUDE_HAIKU_4_5_MODEL,
			PlotAiProperties.GPT_5_4_MODEL,
			PlotAiProperties.GPT_5_5_MODEL,
			PlotAiProperties.GPT_5_6_SOL_MODEL,
			PlotAiProperties.GPT_5_6_LUNA_MODEL,
			PlotAiProperties.GEMINI_3_8_FLASH_MODEL,
			PlotAiProperties.DEEPSEEK_V4_1_FLASH_MODEL,
			PlotAiProperties.GROK_4_6_MODEL,
			PlotAiProperties.QWEN_3_8_MAX_MODEL,
		)

		assertTrue(PlotAiProperties.SUPPORTED_MODELS.containsAll(models))
		assertEquals(listOf("anthropic"), PlotAiProperties().openRouterProviderPolicyFor("anthropic")["only"])
	}
}
