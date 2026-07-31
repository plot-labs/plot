package com.plot.api.observability

import org.springframework.boot.micrometer.observation.autoconfigure.ObservationHandlerGroup
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ObservationSafetyConfiguration {
	@Bean
	fun sensitiveObservationErrorHandlerGroup(): ObservationHandlerGroup = object : ObservationHandlerGroup {
		override fun handlerType(): Class<*> = SensitiveObservationErrorHandler::class.java

		override fun compareTo(other: ObservationHandlerGroup): Int =
			if (other.handlerType().name == TRACING_HANDLER_TYPE) -1 else 0
	}

	private companion object {
		const val TRACING_HANDLER_TYPE = "io.micrometer.tracing.handler.TracingObservationHandler"
	}
}
