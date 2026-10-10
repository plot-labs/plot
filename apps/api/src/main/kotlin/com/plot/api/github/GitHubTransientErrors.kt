package com.plot.api.github

import com.plot.api.common.ApiException

/** GitHub provider failures that are expected to clear on their own and are safe to retry. */
internal object GitHubTransientErrors {
	val CODES: Set<String> = setOf(
		"GITHUB_RATE_LIMITED",
		"GITHUB_NETWORK_ERROR",
		"GITHUB_PROVIDER_UNAVAILABLE",
	)

	fun isTransient(failure: ApiException): Boolean = failure.error in CODES
}
