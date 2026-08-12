package com.plot.api

import com.plot.api.github.GitHubWebhookParser
import com.plot.api.observability.stopSafely
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ApiApplicationTests {
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var objectMapper: ObjectMapper
	@Autowired private lateinit var webhookParser: GitHubWebhookParser
	@Autowired private lateinit var environment: Environment
	@Autowired private lateinit var observationRegistry: ObservationRegistry
	@Autowired private lateinit var flyway: Flyway

	@Test
	fun contextStartsAndAppliesFlywayMigrations() {
		assertTrue(flyway.configuration.isGroup)
		assertEquals(
				"24",
			jdbcTemplate.queryForObject(
				"select version from flyway_schema_history where success order by installed_rank desc limit 1",
				String::class.java,
			),
		)
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"""
				select count(*)
				from information_schema.tables
				where table_schema = 'public'
				  and table_name in (
				    'generation_intervention_resolutions',
				    'generation_interventions',
				    'writing_block_relation_observations',
				    'writing_block_relations',
				    'writing_block_fragments',
				    'tasks'
				  )
				""".trimIndent(),
				Int::class.java,
			),
		)
		val statusConstraint = jdbcTemplate.queryForObject(
			"select pg_get_constraintdef(oid) from pg_constraint where conname = 'generation_runs_status_check'",
			String::class.java,
		).orEmpty()
		assertTrue(statusConstraint.contains("NEEDS_REVIEW"))
		assertFalse(statusConstraint.contains("NEEDS_YOUR_CALL"))
		val routineCadenceConstraint = jdbcTemplate.queryForObject(
			"select pg_get_constraintdef(oid) from pg_constraint where conname = 'routines_cadence_check'",
			String::class.java,
		).orEmpty()
		assertTrue(routineCadenceConstraint.contains("ON_GITHUB_CHANGE"))
		assertTrue(routineCadenceConstraint.contains("ON_GITHUB_RELEASE"))
		assertTrue(routineCadenceConstraint.contains("ON_GIT_TAG"))
	}

	@Test
	fun contextProvidesJackson3ToTheGitHubWebhookParser() {
		val parsed = webhookParser.parse(
			"jackson3-delivery",
			"push",
			objectMapper.writeValueAsBytes(mapOf(
				"ref" to "refs/tags/v1.2.3",
				"after" to "a".repeat(40),
			)),
		)

		assertEquals("v1.2.3", parsed.tagName)
		assertEquals("a".repeat(40), parsed.afterSha)
	}

	@Test
	fun contextParsesGitHubPushCommitsForChangeRoutines() {
		val parsed = webhookParser.parse(
			"push-delivery",
			"push",
			objectMapper.writeValueAsBytes(mapOf(
				"ref" to "refs/heads/main",
				"commits" to listOf(mapOf(
					"id" to "b".repeat(40),
					"message" to "Ship routines\n\nGenerate a draft on push.",
					"url" to "https://github.com/acme/plot/commit/${"b".repeat(40)}",
					"timestamp" to "2026-08-09T00:00:00Z",
					"author" to mapOf("username" to "octocat"),
				)),
			)),
		)

		assertEquals(1, parsed.commits.size)
		assertEquals("Ship routines\n\nGenerate a draft on push.", parsed.commits.single().message)
		assertEquals("octocat", parsed.commits.single().author)
	}

	@Test
	fun testRuntimeDisablesExternalObservabilityAndSensitiveAiLogging() {
		assertEquals("false", environment.getProperty("management.opentelemetry.enabled"))
		assertEquals("false", environment.getProperty("management.otlp.metrics.export.enabled"))
		assertEquals("false", environment.getProperty("spring.ai.chat.observations.log-prompt"))
		assertEquals("false", environment.getProperty("spring.ai.chat.observations.log-completion"))
		assertEquals("false", environment.getProperty("spring.ai.chat.observations.include-error-logging"))
		assertEquals("false", environment.getProperty("spring.ai.chat.client.observations.log-prompt"))
		assertEquals("false", environment.getProperty("spring.ai.chat.client.observations.log-completion"))
		assertEquals("false", environment.getProperty("spring.ai.chat.client.observations.include-error-logging"))
	}

	@Test
	fun runtimeObservationSanitizesProviderErrorDetails() {
		val observation = Observation.start("plot.test.error", observationRegistry)
		observation.error(IllegalStateException("private provider response"))

		assertEquals("OBSERVATION_ERROR", observation.context.error?.message)
		assertEquals(null, observation.context.error?.cause)
		observation.stopSafely()
	}
}
