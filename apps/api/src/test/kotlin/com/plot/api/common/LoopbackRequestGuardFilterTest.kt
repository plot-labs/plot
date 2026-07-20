package com.plot.api.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class LoopbackRequestGuardFilterTest {
	private val filter = LoopbackRequestGuardFilter()

	@Test
	fun `allows only loopback Host and forwarded-host chains`() {
		val request = MockHttpServletRequest().apply {
			addHeader("Host", "127.0.0.1:8080")
			addHeader("X-Forwarded-Host", "localhost:8080, [::1]:8080")
		}
		val response = MockHttpServletResponse()
		var continued = false

		filter.doFilter(request, response) { _, _ -> continued = true }

		assertTrue(continued)
		assertEquals(200, response.status)
	}

	@Test
	fun `rejects external missing and mixed forwarded hosts`() {
		listOf(
			null to null,
			"external.invalid" to null,
			"127.0.0.1:8080" to "127.0.0.1:8080, external.invalid",
			"user@127.0.0.1:8080" to null,
			"[::1]evil" to null,
			"[::1]:abc" to null,
			"127.0.0.1:evil" to null,
			"127.0.0.1:65536" to null,
		).forEach { (host, forwarded) ->
			val request = MockHttpServletRequest().apply {
				host?.let { addHeader("Host", it) }
				forwarded?.let { addHeader("X-Forwarded-Host", it) }
			}
			val response = MockHttpServletResponse()
			var continued = false
			filter.doFilter(request, response) { _, _ -> continued = true }
			assertFalse(continued)
			assertEquals(421, response.status)
		}
	}
}
