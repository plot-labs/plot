package com.plot.api.chat

import com.plot.api.common.ApiException
import com.plot.api.config.PlotAiProperties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class ChatModelSelectionTest {
	@Test
	fun `auto freezes the configured workspace model`() {
		val properties = PlotAiProperties(
			enabled = true,
			model = PlotAiProperties.DEEPSEEK_V4_FLASH_MODEL,
			routingProvider = "deepinfra",
		)

		assertEquals(
			ChatModelSelection("auto", PlotAiProperties.DEEPSEEK_V4_FLASH_MODEL, "deepinfra"),
			ChatModels.resolve("auto", properties),
		)
	}

	@Test
	fun `explicit model selects its matching provider`() {
		assertEquals(
			ChatModelSelection(PlotAiProperties.GPT_5_4_MODEL, PlotAiProperties.GPT_5_4_MODEL, "openai"),
			ChatModels.resolve(PlotAiProperties.GPT_5_4_MODEL, PlotAiProperties()),
		)
	}

	@Test
	fun `unknown model is rejected before admission`() {
		assertFailsWith<ApiException> {
			ChatModels.resolve("vendor/untrusted-model", PlotAiProperties())
		}
	}
}
