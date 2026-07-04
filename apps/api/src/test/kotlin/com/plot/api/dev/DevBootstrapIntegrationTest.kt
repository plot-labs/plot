package com.plot.api.dev

import com.plot.api.TestcontainersConfiguration
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
class DevBootstrapIntegrationTest {

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var devBootstrap: DevBootstrap

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	fun bootstrapCreatesDevUserWorkspaceAndMembership() {
		assertEquals(1, countRows("users", devContext.devUserId))
		assertEquals(1, countRows("workspaces", devContext.devWorkspaceId))
		assertEquals(1, countRows("workspace_members", devContext.devWorkspaceMemberId))
	}

	@Test
	fun bootstrapIsIdempotent() {
		devBootstrap.run()
		devBootstrap.run()

		val membershipCount = jdbcTemplate.queryForObject(
			"select count(*) from workspace_members where workspace_id = ? and user_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			devContext.devUserId,
		)

		assertEquals(1, membershipCount)
	}

	private fun countRows(tableName: String, id: Any): Int {
		return jdbcTemplate.queryForObject(
			"select count(*) from $tableName where id = ?",
			Int::class.java,
			id,
		) ?: 0
	}
}
