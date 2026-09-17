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
			ChatModelSelection("auto", PlotAiProperties.DEEPSEEK_V4_FLASH_MODEL, "deepinfra", "high"),
			ChatModels.resolve("auto", properties),
		)
	}

	@Test
	fun `explicit model selects its matching provider`() {
		mapOf(
			PlotAiProperties.GPT_5_4_MODEL to "openai",
			PlotAiProperties.GPT_5_6_SOL_MODEL to "openai",
			PlotAiProperties.GEMINI_3_8_FLASH_MODEL to "google-ai-studio",
			PlotAiProperties.GROK_4_6_MODEL to "xai",
			PlotAiProperties.QWEN_3_8_MAX_MODEL to "alibaba",
		).forEach { (model, provider) ->
			assertEquals(
				ChatModelSelection(model, model, provider),
				ChatModels.resolve(model, PlotAiProperties()),
			)
		}
		assertEquals(
			ChatModelSelection(PlotAiProperties.DEEPSEEK_V4_1_FLASH_MODEL, PlotAiProperties.DEEPSEEK_V4_1_FLASH_MODEL, "deepinfra", "high"),
			ChatModels.resolve(PlotAiProperties.DEEPSEEK_V4_1_FLASH_MODEL, PlotAiProperties()),
		)
	}

	@Test
	fun `reasoning effort is normalized and retained`() {
		assertEquals(
			ChatModelSelection("auto", null, null, "high"),
			ChatModels.resolve("auto", PlotAiProperties(), " HIGH "),
		)
	}

	@Test
	fun `unsupported reasoning effort is rejected before admission`() {
		assertFailsWith<ApiException> {
			ChatModels.resolve("auto", PlotAiProperties(), "ultra")
		}
	}

	@Test
	fun `reasoning effort follows each model capability`() {
		assertEquals("max", ChatModels.resolve(PlotAiProperties.DEEPSEEK_V4_1_FLASH_MODEL, PlotAiProperties(), "max").reasoningEffort)
		assertEquals("none", ChatModels.resolve(PlotAiProperties.GPT_5_4_MODEL, PlotAiProperties(), "none").reasoningEffort)
		assertEquals("xhigh", ChatModels.resolve(PlotAiProperties.QWEN_3_8_MAX_MODEL, PlotAiProperties(), "xhigh").reasoningEffort)
		assertEquals(null, ChatModels.resolve(PlotAiProperties.CLAUDE_HAIKU_4_5_MODEL, PlotAiProperties(), "medium").reasoningEffort)

		assertFailsWith<ApiException> {
			ChatModels.resolve(PlotAiProperties.GEMINI_3_8_FLASH_MODEL, PlotAiProperties(), "max")
		}
		assertFailsWith<ApiException> {
			ChatModels.resolve(PlotAiProperties.QWEN_3_8_MAX_MODEL, PlotAiProperties(), "max")
		}
	}

	@Test
	fun `unknown model is rejected before admission`() {
		assertFailsWith<ApiException> {
			ChatModels.resolve("vendor/untrusted-model", PlotAiProperties())
		}
	}
}
