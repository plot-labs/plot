package com.plot.api.source

import com.plot.api.common.ApiException
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/** Temporary product-auth boundary shared by every provider-managed source. */
@Component
class SourceManagedAccessGuard(private val environment: Environment) {
	fun requireReadable() {
		if (environment.activeProfiles.none {
				it == "local" || it == "dev" || it == "test" || it == "generation-certification"
			}) {
			throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_AUTH_REQUIRED", "Source-managed blocks require product authentication")
		}
		if (environment.getProperty("server.address") !in setOf("localhost", "127.0.0.1", "::1")) {
			throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_EXPOSURE_INVALID", "Source-managed blocks require a loopback server address")
		}
	}
}
