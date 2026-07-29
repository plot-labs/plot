package com.plot.api

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ApiApplicationTests {
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	fun contextStartsAndAppliesFlywayMigrations() {
		assertEquals(
			"9",
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
	}
}
