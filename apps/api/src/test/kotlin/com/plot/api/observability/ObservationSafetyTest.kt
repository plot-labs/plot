package com.plot.api.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistry
import java.util.UUID
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class ObservationSafetyTest {
	@Test
	fun allowlistedObservationDoesNotContainPrivateContentKeys() {
		val observations = TestObservationRegistry.create()
		Observation.start("plot.artifact_workflow.model_call", observations)
			.lowCardinalityKeyValue("plot.operation", "model_invocation")
			.lowCardinalityKeyValue("plot.outcome", "SUCCEEDED")
			.highCardinalityKeyValue("plot.artifact_workflow_run_id", UUID.randomUUID().toString())
			.highCardinalityKeyValue("plot.model_provider", "openrouter")
			.highCardinalityKeyValue("plot.served_model", "test-model")
			.highCardinalityKeyValue("plot.provider_response_id", "response-1")
			.highCardinalityKeyValue("plot.total_tokens", "4")
			.highCardinalityKeyValue("plot.model_latency_ms", "12")
			.stopSafely()

		observations.assertThat()
			.hasObservationWithNameEqualTo("plot.artifact_workflow.model_call")
			.that()
			.hasOnlyKeys(
				"plot.operation",
				"plot.outcome",
				"plot.artifact_workflow_run_id",
				"plot.model_provider",
				"plot.served_model",
				"plot.provider_response_id",
				"plot.total_tokens",
				"plot.model_latency_ms",
			)
			.doesNotHaveError()
	}

	@Test
	fun exporterHandlerFailureCannotEscapeObservationBoundary() {
		val registry = ObservationRegistry.create()
		registry.observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
			override fun supportsContext(context: Observation.Context): Boolean = true

			override fun onStop(context: Observation.Context): Unit =
				throw IllegalStateException("provider secret and stack trace must stay local")
		})

		assertDoesNotThrow {
		Observation.start("plot.artifact_workflow.attempt", registry).stopSafely()
		}
	}

	@Test
	fun providerErrorIsReplacedWithSafeCodeBeforeTracingHandlersSeeIt() {
		val observations = TestObservationRegistry.create()
		observations.observationConfig().observationHandler(SensitiveObservationErrorHandler())
		val observation = Observation.start("spring.ai.chat", observations)

		observation.error(IllegalStateException("private provider response body"))
		assertTrue(observation.context.error?.stackTrace?.isEmpty() == true)
		observation.stopSafely()

		observations.assertThat()
			.hasObservationWithNameEqualTo("spring.ai.chat")
			.that()
			.hasError()
			.assertThatError()
			.hasMessage("OBSERVATION_ERROR")
			.hasNoCause()
			.backToContext()
			.hasLowCardinalityKeyValue("plot.error_code", "OBSERVATION_ERROR")
	}
}
