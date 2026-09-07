package com.plot.api.waitlist

import com.plot.api.TestcontainersConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WaitlistSignupApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	fun `waitlist signup persists company and pain channel`() {
		val email = "founder+${System.nanoTime()}@acme.test"
		mockMvc.post("/api/public/waitlist") {
			contentType = MediaType.APPLICATION_JSON
			content = """
				{"email":"$email","role":"founder","painChannel":"changelog","company":"Acme Labs"}
			""".trimIndent()
		}.andExpect {
			status { isOk() }
			jsonPath("$.duplicate") { value(false) }
			jsonPath("$.id") { exists() }
		}

		assertEquals(1, jdbcTemplate.queryForObject(
			"""
			select count(*) from waitlist_signups
			where email = ? and pain_channel = 'changelog' and company = 'Acme Labs'
			""".trimIndent(),
			Int::class.java,
			email,
		))

		mockMvc.post("/api/public/waitlist") {
			contentType = MediaType.APPLICATION_JSON
			content = """
				{"email":"$email","role":"founder","painChannel":"docs","company":"Acme Labs"}
			""".trimIndent()
		}.andExpect {
			status { isOk() }
			jsonPath("$.duplicate") { value(true) }
		}

		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from waitlist_signups where email = ?",
			Int::class.java,
			email,
		))
	}

	@Test
	fun `honeypot submissions are ignored without persistence`() {
		val before = jdbcTemplate.queryForObject("select count(*) from waitlist_signups", Int::class.java) ?: 0
		mockMvc.post("/api/public/waitlist") {
			contentType = MediaType.APPLICATION_JSON
			content = """
				{"email":"bot@example.com","painChannel":"docs","company":"Spam Co","website":"https://spam.test"}
			""".trimIndent()
		}.andExpect {
			status { isOk() }
			jsonPath("$.duplicate") { value(false) }
		}
		assertEquals(before, jdbcTemplate.queryForObject("select count(*) from waitlist_signups", Int::class.java))
	}
}
