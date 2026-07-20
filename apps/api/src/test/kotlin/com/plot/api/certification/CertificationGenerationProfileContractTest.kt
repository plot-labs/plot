package com.plot.api.certification

import com.plot.api.config.PlotAiProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CertificationGenerationProfileContractTest {
	@Test
	fun `model profile identifies the current reviewer prompt contract`() {
		assertEquals(
			"changelog-grounding-v4",
			CertificationGenerationProfileContract.PROMPT_CONTRACT_VERSION,
		)
	}

	@Test
	fun `nano omits temperature while mini binds role temperatures`() {
		val nano = properties(PlotAiProperties.GPT_5_4_NANO_MODEL)
		val mini = properties(PlotAiProperties.GPT_4O_MINI_MODEL)

		assertEquals(
			CertificationGenerationProfileContract.modelProfileHash(nano),
			CertificationGenerationProfileContract.modelProfileHash(nano.copy(writerTemperature = 0.9, reviewerTemperature = 0.8)),
		)
		assertNotEquals(
			CertificationGenerationProfileContract.modelProfileHash(mini),
			CertificationGenerationProfileContract.modelProfileHash(mini.copy(writerTemperature = 0.9)),
		)
	}

	@Test
	fun `request option schema and pinned provider changes alter the hash`() {
		val base = properties(PlotAiProperties.GPT_4O_MINI_MODEL)
		val hash = CertificationGenerationProfileContract.modelProfileHash(base)

		assertNotEquals(hash, CertificationGenerationProfileContract.modelProfileHash(base.copy(maxOutputTokens = 2_001)))
		assertNotEquals(hash, CertificationGenerationProfileContract.modelProfileHash(base.copy(schemaRetries = 0)))
		assertNotEquals(hash, CertificationGenerationProfileContract.modelProfileHash(base.copy(routingProvider = "other-provider")))
		assertNotEquals(
			hash,
			CertificationGenerationProfileContract.modelProfileHash(
				base,
				CertificationProfileSchemas(writer = "{\"type\":\"object\"}"),
			),
		)
	}

	@Test
	fun `matrix rejects anything except the exact two model set`() {
		val nanoOnly = mapOf(PlotAiProperties.GPT_5_4_NANO_MODEL to properties(PlotAiProperties.GPT_5_4_NANO_MODEL))

		assertFailsWith<CertificationProfileException> {
			CertificationGenerationProfileContract.hashes(nanoOnly)
		}
	}

	private fun properties(model: String) = CertificationGenerationProfileContract.properties(
		model = model,
		providerSlug = "openai",
		timeoutSeconds = 45,
		maxOutputTokens = 2_000,
		transportRetries = 0,
		schemaRetries = 1,
	)
}
