package com.plot.api.common

import jakarta.validation.constraints.Min
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

class ApiExceptionHandlerTest {

	private val mockMvc: MockMvc = MockMvcBuilders
		.standaloneSetup(ValidationController())
		.setControllerAdvice(ApiExceptionHandler())
		.setValidator(LocalValidatorFactoryBean().apply { afterPropertiesSet() })
		.build()

	@Test
	fun handlerMethodValidationExceptionsUseApiErrorShape() {
		mockMvc.perform(get("/validation").param("count", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.error").value("BAD_REQUEST"))
			.andExpect(jsonPath("$.message").value("count must be at least 1"))
	}

	@RestController
	class ValidationController {

		@GetMapping("/validation")
		fun validate(@RequestParam @Min(value = 1, message = "count must be at least 1") count: Int): Map<String, Int> {
			return mapOf("count" to count)
		}
	}
}
