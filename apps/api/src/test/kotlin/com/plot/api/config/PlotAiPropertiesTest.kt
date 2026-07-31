package com.plot.api.config

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
