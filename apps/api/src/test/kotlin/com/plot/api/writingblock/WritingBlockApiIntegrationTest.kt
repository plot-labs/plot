package com.plot.api.writingblock

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

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
		jdbcTemplate.update("delete from writing_block_relation_observations where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from writing_block_relations where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from writing_block_fragments where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from writing_block_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update(
			"delete from writing_blocks where workspace_id = ?",
			devContext.devWorkspaceId,
		)
	}

	@Test
	fun createListDetailAndUpdateWritingBlock() {
		mockMvc.post("/api/blocks") {
			contentType = MediaType.APPLICATION_JSON
			content = """
				{
					"sourceOrigin":"  import  ",
					"sourceKind":"  note  ",
					"title":"  Draft Title  ",
					"body":"  Draft Body  ",
					"url":"  https://example.com/a  ",
					"canonicalUrl":"  https://example.com/canonical  ",
					"author":"  Ada  ",
					"platform":"  web  ",
					"metadata":{"source":"notebook","tags":["draft","api"],"count":2},
					"sourceCreatedAt":"2026-01-01T00:00:00Z",
					"sourceUpdatedAt":"2026-01-02T00:00:00Z"
				}
			""".trimIndent()
		}.andExpect {
			status { isOk() }
			jsonPath("$.sourceOrigin") { value("import") }
			jsonPath("$.sourceKind") { value("note") }
			jsonPath("$.title") { value("Draft Title") }
			jsonPath("$.body") { value("Draft Body") }
			jsonPath("$.url") { value("https://example.com/a") }
			jsonPath("$.canonicalUrl") { value("https://example.com/canonical") }
			jsonPath("$.author") { value("Ada") }
			jsonPath("$.platform") { value("web") }
			jsonPath("$.metadata.source") { value("notebook") }
			jsonPath("$.metadata.tags[0]") { value("draft") }
			jsonPath("$.metadata.count") { value(2) }
			jsonPath("$.sourceCreatedAt") { value("2026-01-01T00:00:00Z") }
			jsonPath("$.sourceUpdatedAt") { value("2026-01-02T00:00:00Z") }
			jsonPath("$.ingestedAt") { exists() }
			jsonPath("$.status") { value("ACTIVE") }
			jsonPath("$.createdAt") { exists() }
			jsonPath("$.updatedAt") { exists() }
			jsonPath("$.workspaceId") { doesNotExist() }
			jsonPath("$.contentHash") { doesNotExist() }
		}

		val blockId = findBlockIdByTitle("Draft Title")
		val originalIngestedAt = findIngestedAt(blockId)

		mockMvc.get("/api/blocks")
			.andExpect {
				status { isOk() }
				jsonPath("$.items[0].id") { value(blockId.toString()) }
				jsonPath("$.items[0].sourceOrigin") { value("import") }
				jsonPath("$.items[0].sourceKind") { value("note") }
				jsonPath("$.items[0].title") { value("Draft Title") }
				jsonPath("$.items[0].body") { value("Draft Body") }
				jsonPath("$.items[0].metadata.tags[1]") { value("api") }
				jsonPath("$.items[0].workspaceId") { doesNotExist() }
				jsonPath("$.items[0].contentHash") { doesNotExist() }
			}

		mockMvc.get("/api/blocks/$blockId")
			.andExpect {
				status { isOk() }
				jsonPath("$.id") { value(blockId.toString()) }
				jsonPath("$.sourceOrigin") { value("import") }
				jsonPath("$.sourceKind") { value("note") }
				jsonPath("$.title") { value("Draft Title") }
				jsonPath("$.body") { value("Draft Body") }
				jsonPath("$.metadata.source") { value("notebook") }
				jsonPath("$.workspaceId") { doesNotExist() }
				jsonPath("$.contentHash") { doesNotExist() }
			}

		mockMvc.patch("/api/blocks/$blockId") {
			contentType = MediaType.APPLICATION_JSON
			content = """
				{
					"sourceOrigin":"  manual  ",
					"sourceKind":"  quote  ",
					"title":"  Updated Title  ",
					"body":"  Updated Body  ",
					"url":"  https://example.com/b  ",
					"canonicalUrl":"  https://example.com/updated  ",
					"author":"  Grace  ",
					"platform":"  mobile  ",
					"metadata":{"source":"editor","tags":["updated"]},
					"sourceCreatedAt":"2026-02-01T00:00:00Z",
					"sourceUpdatedAt":"2026-02-02T00:00:00Z"
				}
			""".trimIndent()
		}.andExpect {
			status { isOk() }
			jsonPath("$.id") { value(blockId.toString()) }
			jsonPath("$.sourceOrigin") { value("manual") }
			jsonPath("$.sourceKind") { value("quote") }
			jsonPath("$.title") { value("Updated Title") }
			jsonPath("$.body") { value("Updated Body") }
			jsonPath("$.url") { value("https://example.com/b") }
			jsonPath("$.canonicalUrl") { value("https://example.com/updated") }
			jsonPath("$.author") { value("Grace") }
			jsonPath("$.platform") { value("mobile") }
			jsonPath("$.metadata.source") { value("editor") }
			jsonPath("$.metadata.tags[0]") { value("updated") }
			jsonPath("$.sourceCreatedAt") { value("2026-02-01T00:00:00Z") }
			jsonPath("$.sourceUpdatedAt") { value("2026-02-02T00:00:00Z") }
			jsonPath("$.status") { value("ACTIVE") }
			jsonPath("$.workspaceId") { doesNotExist() }
			jsonPath("$.contentHash") { doesNotExist() }
		}

		assertEquals(originalIngestedAt, findIngestedAt(blockId))
	}

	@Test
	fun listOrdersWritingBlocksByCreatedAtDescending() {
		val olderId = UUID.randomUUID()
		val newerId = UUID.randomUUID()
		insertWritingBlock(
			id = olderId,
			title = "Older Block",
			createdAt = Instant.parse("2026-01-01T00:00:00Z"),
		)
		insertWritingBlock(
			id = newerId,
			title = "Newer Block",
			createdAt = Instant.parse("2026-01-02T00:00:00Z"),
		)

		mockMvc.get("/api/blocks")
			.andExpect {
				status { isOk() }
				jsonPath("$.items[0].id") { value(newerId.toString()) }
				jsonPath("$.items[1].id") { value(olderId.toString()) }
			}
	}

	@Test
	fun createRejectsBlankTitleAndBody() {
		mockMvc.post("/api/blocks") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sourceOrigin":"manual","sourceKind":"note","title":" ","body":" "}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
			jsonPath("$.message") { value("Writing block requires title or body") }
		}
	}

	@Test
	fun createAndUpdateRejectBlankSourceFields() {
		mockMvc.post("/api/blocks") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sourceOrigin":" ","sourceKind":"note","title":"Draft"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}

		mockMvc.post("/api/blocks") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sourceOrigin":"manual","sourceKind":" ","title":"Draft"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}

		val blockId = UUID.randomUUID()
		insertWritingBlock(
			id = blockId,
			title = "Draft Block",
			createdAt = Instant.parse("2026-01-01T00:00:00Z"),
		)

		mockMvc.patch("/api/blocks/$blockId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sourceOrigin":" ","sourceKind":"note","title":"Draft"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}

		mockMvc.patch("/api/blocks/$blockId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sourceOrigin":"manual","sourceKind":" ","title":"Draft"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}
	}

	@Test
	fun updateRejectsBlankTitleAndBody() {
		val blockId = UUID.randomUUID()
		insertWritingBlock(
			id = blockId,
			title = "Draft Block",
			createdAt = Instant.parse("2026-01-01T00:00:00Z"),
		)

		mockMvc.patch("/api/blocks/$blockId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sourceOrigin":"manual","sourceKind":"note","title":" ","body":" "}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
			jsonPath("$.message") { value("Writing block requires title or body") }
		}
	}

	@Test
	fun getReturnsNotFoundForRandomUuid() {
		val randomUuid = UUID.randomUUID()

		mockMvc.get("/api/blocks/$randomUuid")
			.andExpect {
				status { isNotFound() }
				jsonPath("$.error") { value("NOT_FOUND") }
			}
	}

	@Test
	fun getReturnsNotFoundForWritingBlockInAnotherWorkspace() {
		val otherWorkspaceId = insertOtherWorkspace()
		val otherBlockId = UUID.randomUUID()
		insertWritingBlock(
			id = otherBlockId,
			workspaceId = otherWorkspaceId,
			title = "Other Workspace Block",
			createdAt = Instant.parse("2026-01-03T00:00:00Z"),
		)

		mockMvc.get("/api/blocks/$otherBlockId")
			.andExpect {
				status { isNotFound() }
				jsonPath("$.error") { value("NOT_FOUND") }
			}
	}

	@Test
	fun patchReturnsNotFoundForRandomUuid() {
		val randomUuid = UUID.randomUUID()

		mockMvc.patch("/api/blocks/$randomUuid") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sourceOrigin":"manual","sourceKind":"note","title":"Updated"}"""
		}.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("NOT_FOUND") }
		}
	}

	@Test
	fun getReturnsBadRequestForMalformedBlockId() {
		mockMvc.get("/api/blocks/not-a-uuid")
			.andExpect {
				status { isBadRequest() }
				jsonPath("$.error") { value("BAD_REQUEST") }
			}
	}

	private fun findBlockIdByTitle(title: String): UUID {
		return jdbcTemplate.queryForObject(
			"""
			select id
			from writing_blocks
			where workspace_id = ? and title = ?
			""".trimIndent(),
			UUID::class.java,
			devContext.devWorkspaceId,
			title,
		)!!
	}

	private fun findIngestedAt(id: UUID): Instant {
		return jdbcTemplate.queryForObject(
			"""
			select ingested_at
			from writing_blocks
			where workspace_id = ? and id = ?
			""".trimIndent(),
			Timestamp::class.java,
			devContext.devWorkspaceId,
			id,
		)!!.toInstant()
	}

	private fun insertOtherWorkspace(): UUID {
		val workspaceId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at)
			values (?, 'Other Writing Block Workspace', ?, ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			workspaceId,
			"other-writing-block-${workspaceId}",
			devContext.devUserId,
		)
		return workspaceId
	}

	private fun insertWritingBlock(
		id: UUID,
		workspaceId: UUID = devContext.devWorkspaceId,
		title: String,
		createdAt: Instant,
	) {
		val timestamp = Timestamp.from(createdAt)
		jdbcTemplate.update(
			"""
			insert into writing_blocks (
				id,
				workspace_id,
				source_origin,
				source_kind,
				title,
				body,
				url,
				canonical_url,
				author,
				platform,
				metadata,
				content_hash,
				source_created_at,
				source_updated_at,
				ingested_at,
				status,
				created_by_user_id,
				created_at,
				updated_at
			)
			values (?, ?, 'manual', 'note', ?, 'Body', null, null, null, null, null, 'hash', null, null, ?, 'ACTIVE', ?, ?, ?)
			""".trimIndent(),
			id,
			workspaceId,
			title,
			timestamp,
			devContext.devUserId,
			timestamp,
			timestamp,
		)
	}
}
