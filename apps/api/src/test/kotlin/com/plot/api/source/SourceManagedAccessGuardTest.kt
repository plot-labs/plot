package com.plot.api.source

import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class SourceManagedAccessGuardTest {
	@Test
	fun certificationProfileAllowsLoopbackRead() {
		val environment = MockEnvironment().apply {
			setActiveProfiles("generation-certification")
			setProperty("server.address", "127.0.0.1")
		}

		SourceManagedAccessGuard(environment).requireReadable()
	}
}
