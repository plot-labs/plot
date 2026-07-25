package com.plot.api.entitlement

import java.time.Duration

object TrialPolicy {
	const val PACK_LIMIT = 3L
	val DURATION: Duration = Duration.ofDays(30)
}
