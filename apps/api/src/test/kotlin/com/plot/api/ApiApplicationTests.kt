package com.plot.api

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ApiApplicationTests {

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	fun contextStartsAndFlywayCreatesCoreTables() {
		val tableCount = jdbcTemplate.queryForObject(
			"""
			select count(*)
			from information_schema.tables
			where table_schema = 'public'
			  and table_name in (
			    'users',
			    'workspaces',
			    'workspace_members',
			    'work_sessions',
			    'tasks',
			    'writing_blocks'
			  )
			""".trimIndent(),
			Int::class.java,
		)

		assertEquals(6, tableCount)
	}
}
