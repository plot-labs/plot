package com.plot.api.writingblock

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class WritingBlockApiIntegrationTest {

	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@BeforeEach
	fun cleanDevWritingBlockData() {
		jdbcTemplate.update("delete from writing_block_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from writing_blocks where workspace_id = ?", devContext.devWorkspaceId)
	}

	@Test
	fun listReturnsWritingBlocksByCreatedAtDescending() {
		val olderId = UUID.randomUUID()
		val newerId = UUID.randomUUID()
		insertWritingBlock(olderId, "Older Block", Instant.parse("2026-01-01T00:00:00Z"))
		insertWritingBlock(newerId, "Newer Block", Instant.parse("2026-01-02T00:00:00Z"))

		mockMvc.get("/api/blocks")
			.andExpect {
				status { isOk() }
				jsonPath("$.items[0].id") { value(newerId.toString()) }
				jsonPath("$.items[1].id") { value(olderId.toString()) }
				jsonPath("$.items[0].workspaceId") { doesNotExist() }
				jsonPath("$.items[0].contentHash") { doesNotExist() }
			}
	}

	private fun insertWritingBlock(id: UUID, title: String, createdAt: Instant) {
		val timestamp = Timestamp.from(createdAt)
		jdbcTemplate.update(
			"""
			insert into writing_blocks (
				id, workspace_id, source_origin, source_kind, title, body,
				content_hash, ingested_at, status, created_by_user_id, created_at, updated_at
			)
			values (?, ?, 'manual', 'note', ?, 'Body', 'hash', ?, 'ACTIVE', ?, ?, ?)
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			title,
			timestamp,
			devContext.devUserId,
			timestamp,
			timestamp,
		)
	}
}
