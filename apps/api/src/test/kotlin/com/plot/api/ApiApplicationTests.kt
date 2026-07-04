package com.plot.api

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ApiApplicationTests {

	@Test
	fun applicationNameIsStable() {
		assertEquals("ApiApplication", ApiApplication::class.simpleName)
	}

}
