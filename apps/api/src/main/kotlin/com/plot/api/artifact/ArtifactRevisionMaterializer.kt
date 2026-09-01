package com.plot.api.artifact

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.artifact.dto.ContentCitationResponse
import com.plot.api.artifact.dto.ContentExportResponse
import com.plot.api.artifact.dto.ArtifactPageResponse
import com.plot.api.artifact.dto.ArtifactResponse
import com.plot.api.artifact.dto.ArtifactSummaryResponse
import com.plot.api.artifact.dto.ContentSentenceResponse
import com.plot.api.artifact.dto.ContentSourceResponse
import com.plot.api.artifact.dto.ContentStatementInput
import com.plot.api.artifact.dto.ContentVariantResponse
import com.plot.api.artifact.dto.ContentVariantHistoryItemResponse
import com.plot.api.artifact.dto.ContentVariantHistoryDetailResponse
import com.plot.api.artifact.dto.ExportDisposition
import com.plot.api.artifact.dto.ExportWarningResponse
import com.plot.api.dev.DevContext
import com.plot.api.artifact.workflow.model.CitationStatus
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ExportSentence
import com.plot.api.artifact.workflow.model.ExportSentenceStatus
import com.plot.api.artifact.workflow.model.SentenceCitation
import com.plot.api.artifact.workflow.model.SourceProvider
import com.plot.api.artifact.workflow.model.ExportSource
import java.net.URI
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.util.HexFormat
import java.util.UUID
import org.springframework.http.HttpStatus
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import com.plot.api.persistence.SqlRow
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper



@Service
class ArtifactRevisionMaterializer(
    private val sqlExecutor: JooqSqlExecutor,
    private val devContext: DevContext,
    private val uuidGenerator: UuidGenerator,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
	internal fun ensureArtifactRevision(variantId: UUID): CurrentArtifactRevision {
		val existing = currentArtifactRevisionOrNull(variantId)
		if (existing != null) return existing
		val runId = sqlExecutor.query(
			"select generation_run_id from content_variants where workspace_id = ? and id = ?",
			{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) },
			devContext.devWorkspaceId, variantId,
		).firstOrNull() ?: notFound()
		val rows = sqlExecutor.query(
			"""
			select s.id, r.id, r.revision_no, s.order_index, r.body, r.origin
			from content_variant_sentences s
			join content_variant_sentence_revisions r on r.workspace_id = s.workspace_id and r.sentence_id = s.id and r.is_current
			where s.workspace_id = ? and s.content_variant_id = ? order by s.order_index
			""".trimIndent(),
			{ rs, _ -> StatementRow(
				requireNotNull(rs.getObject(1, UUID::class.java)),
				requireNotNull(rs.getObject(2, UUID::class.java)),
				rs.getInt(3),
				rs.getInt(4),
				requireNotNull(rs.getString(5)),
				requireNotNull(rs.getString(6)),
			) },
			devContext.devWorkspaceId, variantId,
		)
		val revisionId = variantId
		val now = clock.instant()
		sqlExecutor.update(
			"insert into content_variant_revisions (id, workspace_id, generation_run_id, content_variant_id, revision_no, lexical_content, is_current, created_at) values (?, ?, ?, ?, 1, ?::jsonb, true, ?)",
			revisionId, devContext.devWorkspaceId, runId, variantId, lexicalContentFor(rows).toString(), Timestamp.from(now),
		)
		rows.forEach { row ->
			sqlExecutor.update(
				"insert into content_variant_revision_sentences (id, workspace_id, content_variant_revision_id, generation_run_id, content_variant_id, sentence_id, sentence_revision_id, order_index) values (?, ?, ?, ?, ?, ?, ?, ?)",
				uuidGenerator.next(), devContext.devWorkspaceId, revisionId, runId, variantId, row.id, row.revisionId, row.orderIndex,
			)
		}
		return CurrentArtifactRevision(revisionId, runId, 1, lexicalContentFor(rows))
	}

	internal fun currentArtifactRevision(variantId: UUID): CurrentArtifactRevision = currentArtifactRevisionOrNull(variantId) ?: ensureArtifactRevision(variantId)

	internal fun currentArtifactRevisionForUpdate(variantId: UUID): CurrentArtifactRevision =
		currentArtifactRevisionOrNull(variantId, forUpdate = true) ?: ensureArtifactRevision(variantId)

	internal fun currentArtifactRevisionOrNull(variantId: UUID, forUpdate: Boolean = false): CurrentArtifactRevision? = sqlExecutor.query(
		"select id, generation_run_id, revision_no, lexical_content::text from content_variant_revisions where workspace_id = ? and content_variant_id = ? and is_current${if (forUpdate) " for update" else ""}",
		{ rs, _ -> CurrentArtifactRevision(
			requireNotNull(rs.getObject(1, UUID::class.java)),
			requireNotNull(rs.getObject(2, UUID::class.java)),
			rs.getInt(3),
			objectMapper.readTree(requireNotNull(rs.getString(4))),
		) },
		devContext.devWorkspaceId, variantId,
	).firstOrNull()

	internal fun loadCurrentStatements(revisionId: UUID, variantId: UUID): List<StatementRow> {
		val artifactRevisionExists = sqlExecutor.queryForObject(
			"select exists (select 1 from content_variant_revisions where workspace_id = ? and id = ? and content_variant_id = ?)",
			Boolean::class.java,
			devContext.devWorkspaceId, revisionId, variantId,
		) ?: false
		if (artifactRevisionExists) {
			return sqlExecutor.query(
				"""
				select rs.sentence_id, rs.sentence_revision_id, r.revision_no, rs.order_index, r.body, r.origin
				from content_variant_revision_sentences rs
				join content_variant_sentence_revisions r on r.workspace_id = rs.workspace_id and r.id = rs.sentence_revision_id
				where rs.workspace_id = ? and rs.content_variant_revision_id = ? and rs.content_variant_id = ? order by rs.order_index
				""".trimIndent(),
					{ rs, _ -> StatementRow(
						requireNotNull(rs.getObject(1, UUID::class.java)),
						requireNotNull(rs.getObject(2, UUID::class.java)),
						rs.getInt(3),
						rs.getInt(4),
						requireNotNull(rs.getString(5)),
						requireNotNull(rs.getString(6)),
					) },
				devContext.devWorkspaceId, revisionId, variantId,
			)
		}
		return sqlExecutor.query(
			"""
			select s.id, r.id, r.revision_no, s.order_index, r.body, r.origin
			from content_variant_sentences s
			join content_variant_sentence_revisions r on r.workspace_id = s.workspace_id and r.sentence_id = s.id and r.is_current
			where s.workspace_id = ? and s.content_variant_id = ? order by s.order_index
			""".trimIndent(),
			{ rs, _ -> StatementRow(
				requireNotNull(rs.getObject(1, UUID::class.java)),
				requireNotNull(rs.getObject(2, UUID::class.java)),
				rs.getInt(3),
				rs.getInt(4),
				requireNotNull(rs.getString(5)),
				requireNotNull(rs.getString(6)),
			) },
			devContext.devWorkspaceId, variantId,
		)
	}

	internal fun lexicalContentForStatements(statements: List<ContentStatementInput>): JsonNode {
		val root = objectMapper.createObjectNode()
		val rootNode = root.putObject("root")
		val children = rootNode.putArray("children")
		statements.sortedBy { it.orderIndex ?: Int.MAX_VALUE }.forEach { statement ->
			val paragraph = children.addObject()
			val paragraphChildren = paragraph.putArray("children")
			paragraphChildren.addObject().apply {
				put("detail", 0)
				put("format", 0)
				put("mode", "normal")
				put("style", "")
				put("text", statement.body?.trim().orEmpty())
				put("type", "text")
				put("version", 1)
			}
			paragraph.putNull("direction")
			paragraph.put("format", "")
			paragraph.put("indent", 0)
			paragraph.put("type", "paragraph")
			paragraph.put("version", 1)
		}
		rootNode.putNull("direction")
		rootNode.put("format", "")
		rootNode.put("indent", 0)
		rootNode.put("type", "root")
		rootNode.put("version", 1)
		return root
	}

	internal fun lexicalContentFor(rows: List<StatementRow>): JsonNode {
		val root = objectMapper.createObjectNode()
		val rootNode = root.putObject("root")
		val children = rootNode.putArray("children")
		rows.sortedBy { it.orderIndex }.forEach { row ->
			val paragraph = children.addObject()
			val paragraphChildren = paragraph.putArray("children")
			paragraphChildren.addObject().apply {
				put("detail", 0)
				put("format", 0)
				put("mode", "normal")
				put("style", "")
				put("text", row.body)
				put("type", "text")
				put("version", 1)
			}
			paragraph.putNull("direction")
			paragraph.put("format", "")
			paragraph.put("indent", 0)
			paragraph.put("type", "paragraph")
			paragraph.put("version", 1)
		}
		rootNode.putNull("direction")
		rootNode.put("format", "")
		rootNode.put("indent", 0)
		rootNode.put("type", "root")
		rootNode.put("version", 1)
		return root
	}



    private fun notFound(): Nothing = throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Content pack not found")
}

data class CurrentArtifactRevision(
    val id: UUID,
    val artifactWorkflowRunId: UUID,
    val revisionNumber: Int,
    val lexicalContent: JsonNode,
)

internal data class StatementRow(
    val id: UUID,
    val revisionId: UUID,
    val revisionNumber: Int,
    val orderIndex: Int,
    val body: String,
    val origin: String,
)
