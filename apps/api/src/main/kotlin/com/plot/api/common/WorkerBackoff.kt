package com.plot.api.common

import java.time.Duration

/** Bounded exponential backoff shared by the durable worker queues. */
internal object WorkerBackoff {
	private const val MAX_SHIFT = 8
	val MAX_DELAY: Duration = Duration.ofMinutes(15)

	/**
	 * Returns `initialDelay * 2^exponent`, with the exponent clamped to 0..8 and
	 * the result capped at [MAX_DELAY]. Callers choose whether the first retry
	 * uses exponent 0 or 1 by passing `attempt - 1` or `attempt`.
	 */
	fun delay(initialDelay: Duration, exponent: Int): Duration =
		minOf(MAX_DELAY, initialDelay.multipliedBy(1L shl exponent.coerceIn(0, MAX_SHIFT)))
}
