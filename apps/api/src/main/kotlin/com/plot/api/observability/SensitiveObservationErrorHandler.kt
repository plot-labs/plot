package com.plot.api.observability

import io.micrometer.common.KeyValue
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** Keeps provider exception details out of tracing handlers while preserving a safe outcome code. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SensitiveObservationErrorHandler : ObservationHandler<Observation.Context> {
	override fun supportsContext(context: Observation.Context): Boolean = true

	override fun onError(context: Observation.Context) {
		if (context.error != null) {
			context.addLowCardinalityKeyValue(KeyValue.of("plot.error_code", "OBSERVATION_ERROR"))
			context.setError(SafeObservationException())
		}
	}
}

private class SafeObservationException : RuntimeException("OBSERVATION_ERROR", null, false, false)
