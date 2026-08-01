package com.plot.api.observability

import io.micrometer.observation.Observation

/** Observation handlers and exporters are best-effort and must not change business outcomes. */
internal fun Observation.stopSafely() {
	runCatching { stop() }
}
