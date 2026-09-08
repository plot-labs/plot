package com.plot.api.artifact

import com.plot.api.common.ApiException
import org.springframework.dao.DuplicateKeyException
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
import com.plot.api.content.ContentBrief
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
class ArtifactRevisionService(
    private val sqlExecutor: JooqSqlExecutor,
    private val transactionExecutor: JooqTransactionExecutor,
    private val devContext: DevContext,
    private val uuidGenerator: UuidGenerator,
    private val query: ArtifactQueryService,
    private val materializer: ArtifactRevisionMaterializer,
	private val validator: ArtifactLexicalDocumentValidator,
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun saveVariant(
		variantId: UUID,
		expectedRevisionNumber: Int,
		lexicalContent: JsonNode,
		statements: List<ContentStatementInput>,
	): ArtifactResponse = transactionExecutor.execute {
		saveVariantInTransaction(variantId, expectedRevisionNumber, lexicalContent, statements)
	}

	/**
	 * Compatibility endpoint for older clients. It still creates an artifact
	 * revision, so the old sentence operation cannot bypass optimistic locking
	 * or the public-source projection.
	 */
	fun editSentence(variantId: UUID, sentenceId: UUID, expectedRevisionNumber: Int, body: String): ArtifactResponse =
		transactionExecutor.execute {
			lockVariant(variantId)
			val currentSentence = sqlExecutor.query(
				"""
				select r.revision_no
				from content_variant_sentence_revisions r
				where r.workspace_id = ? and r.content_variant_id = ? and r.sentence_id = ? and r.is_current
				for update
				""".trimIndent(),
				{ rs, _ -> rs.getInt(1) },
				devContext.devWorkspaceId, variantId, sentenceId,
			).firstOrNull() ?: notFound()
			if (currentSentence != expectedRevisionNumber) {
				throw ApiException(HttpStatus.CONFLICT, "STALE_SENTENCE_REVISION", "Sentence revision is stale", sentenceId)
			}
			val current = materializer.ensureArtifactRevision(variantId)
			val replacement = body.trim().takeIf { it.isNotBlank() }
				?: throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Sentence body is required")
			val statements = materializer.loadCurrentStatements(current.id, variantId).map { statement ->
				ContentStatementInput(statement.id, statement.orderIndex, if (statement.id == sentenceId) replacement else statement.body)
			}
			// This compatibility operation still goes through the whole-artifact
			// contract. V2 edits patch the addressed leaf so heading/list/CTA layout
			// survives; legacy V1 documents keep their canonical projection.
			val lexicalContent = if (current.lexicalContent.get("documentVersion")?.asInt() == 2) {
				materializer.replaceStatementBody(current.lexicalContent, sentenceId, replacement)
			} else {
				materializer.lexicalContentForStatements(statements)
			}
			saveVariantInTransaction(variantId, current.revisionNumber, lexicalContent, statements)
		}

	private fun saveVariantInTransaction(
		variantId: UUID,
		expectedRevisionNumber: Int,
		lexicalContent: JsonNode,
		statements: List<ContentStatementInput>,
	): ArtifactResponse {
		var normalized = validator.normalizeStatements(statements)
			val sanitizedLexicalContent = validator.validateAndSanitizeLexicalContent(
				lexicalContent,
				normalized,
				confirmedCtaDestinations(variantId),
			)
		lockVariant(variantId)
		val currentRevision = materializer.currentArtifactRevisionForUpdate(variantId)
		if (currentRevision.revisionNumber != expectedRevisionNumber) throw staleArtifactRevision(variantId)
		val now = clock.instant()
		val previousStatements = materializer.loadCurrentStatements(currentRevision.id, variantId)
		val currentDocumentVersion = currentRevision.lexicalContent.get("documentVersion")
			?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
			?.asInt()
			?: 1
		val incomingDocumentVersion = sanitizedLexicalContent.get("documentVersion")
			?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
			?.asInt()
			?: 1
		if (currentDocumentVersion == 2 && incomingDocumentVersion != 2) {
			throw ApiException(
				HttpStatus.CONFLICT,
				"DOCUMENT_VERSION_CONFLICT",
				"V2 artifacts require a V2 save payload",
				variantId,
			)
		}
		val previousById = previousStatements.associateBy { it.id }
		val allSentenceIds = sqlExecutor.query(
			"select id from content_variant_sentences where workspace_id = ? and content_variant_id = ?",
			{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) },
			devContext.devWorkspaceId, variantId,
		).toSet()
		val currentRevisionBySentence = sqlExecutor.query(
			"select sentence_id, id, revision_no, body, origin from content_variant_sentence_revisions where workspace_id = ? and content_variant_id = ? and is_current",
				{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) to StatementRow(requireNotNull(rs.getObject(1, UUID::class.java)), requireNotNull(rs.getObject(2, UUID::class.java)), rs.getInt(3), 0, requireNotNull(rs.getString(4)), requireNotNull(rs.getString(5))) },
			devContext.devWorkspaceId, variantId,
		).toMap()
		validator.validateStatementOwnership(normalized, allSentenceIds)
		normalized
			.filter { it.id in allSentenceIds && it.id !in previousById }
			.forEach { statement ->
				throw ApiException(
					HttpStatus.BAD_REQUEST,
					"STATEMENT_ID_REUSE",
					"A removed statement identity cannot be reused",
					statement.id,
				)
			}
		normalized = validator.normalizeStatementLineage(normalized, previousById.keys)
		val priorCitations = loadLineageCitations(variantId)
		val previousContent = previousStatements
			.sortedBy { it.orderIndex }
			.map { listOf(it.id, it.orderIndex, it.body, emptyList<UUID>()) }
		val nextContent = normalized
			.sortedBy { it.orderIndex }
			.map { statement -> listOf(statement.id, statement.orderIndex, statement.body, statement.lineage.filter { it != statement.id }) }
		if (sanitizedLexicalContent == currentRevision.lexicalContent && previousContent == nextContent) {
			return query.getVariant(variantId)
		}

		val nextRevisionBySentence = linkedMapOf<UUID, UUID>()
		normalized.sortedBy { it.orderIndex }.forEach { statement ->
			val previous = previousById[statement.id]
			val existingRevision = currentRevisionBySentence[statement.id]
			if (previous == null && existingRevision == null) {
				insertNewSentence(variantId, statement.id, now)
				val revisionId = insertSentenceRevision(variantId, statement.id, 1, statement.body, now)
				nextRevisionBySentence[statement.id] = revisionId
				copyLineageCitations(
					variantId = variantId,
					generationRunId = currentRevision.artifactWorkflowRunId,
					statementId = statement.id,
					sentenceRevisionId = revisionId,
					lineage = statement.lineage,
					priorCitations = priorCitations,
					now = now,
				)
			} else if (
				(previous?.body ?: existingRevision?.body) != statement.body ||
				(statement.lineage.isNotEmpty() && statement.lineage.any { it != statement.id })
			) {
				val previousRevisionId = previous?.revisionId ?: existingRevision!!.revisionId
				val previousRevisionNumber = previous?.revisionNumber ?: existingRevision!!.revisionNumber
				sqlExecutor.update(
					"update content_variant_sentence_revisions set is_current = false where workspace_id = ? and id = ? and is_current",
					devContext.devWorkspaceId, previousRevisionId,
				)
				val revisionId = insertSentenceRevision(variantId, statement.id, previousRevisionNumber + 1, statement.body, now)
				nextRevisionBySentence[statement.id] = revisionId
				copyLineageCitations(
					variantId = variantId,
					generationRunId = currentRevision.artifactWorkflowRunId,
					statementId = statement.id,
					sentenceRevisionId = revisionId,
					lineage = statement.lineage,
					priorCitations = priorCitations,
					now = now,
				)
				sqlExecutor.update(
					"update sentence_citations set status = 'STALE', stale_reason = 'STATEMENT_CHANGED', updated_at = ? where workspace_id = ? and sentence_id = ? and status = 'ACTIVE'",
					Timestamp.from(now), devContext.devWorkspaceId, statement.id,
				)
			} else {
				nextRevisionBySentence[statement.id] = previous?.revisionId ?: existingRevision!!.revisionId
			}
		}

		val retainedIds = normalized.mapTo(linkedSetOf()) { it.id }
		previousStatements.filter { it.id !in retainedIds }.forEach { removed ->
			sqlExecutor.update(
				"update sentence_citations set status = 'REMOVED', stale_reason = 'STATEMENT_REMOVED', updated_at = ? where workspace_id = ? and sentence_id = ? and status = 'ACTIVE'",
				Timestamp.from(now), devContext.devWorkspaceId, removed.id,
			)
		}
		val nextRevisionId = uuidGenerator.next()
		val nextRevisionNumber = currentRevision.revisionNumber + 1
		sqlExecutor.update(
			"update content_variant_revisions set is_current = false where workspace_id = ? and id = ? and is_current",
			devContext.devWorkspaceId, currentRevision.id,
		)
		sqlExecutor.update(
			"""
			insert into content_variant_revisions (
			 id, workspace_id, generation_run_id, content_variant_id, revision_no,
			 lexical_content, is_current, created_by_user_id, created_at
			) values (?, ?, ?, ?, ?, ?::jsonb, true, ?, ?)
			""".trimIndent(),
			nextRevisionId,
			devContext.devWorkspaceId,
			currentRevision.artifactWorkflowRunId,
			variantId,
			nextRevisionNumber,
			sanitizedLexicalContent.toString(),
			devContext.devUserId,
			Timestamp.from(now),
		)
		normalized.sortedBy { it.orderIndex }.forEach { statement ->
			sqlExecutor.update(
				"""
				insert into content_variant_revision_sentences (
				 id, workspace_id, content_variant_revision_id, generation_run_id,
				 content_variant_id, sentence_id, sentence_revision_id, order_index
				) values (?, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent(),
				uuidGenerator.next(), devContext.devWorkspaceId, nextRevisionId,
				currentRevision.artifactWorkflowRunId, variantId, statement.id,
				nextRevisionBySentence.getValue(statement.id), statement.orderIndex,
			)
		}
		sqlExecutor.update(
			"update content_variants set updated_at = ? where workspace_id = ? and id = ?",
			Timestamp.from(now), devContext.devWorkspaceId, variantId,
		)
		sqlExecutor.update(
			"update content_packs set updated_at = ? where workspace_id = ? and id = (select content_pack_id from content_variants where workspace_id = ? and id = ?)",
			Timestamp.from(now), devContext.devWorkspaceId, devContext.devWorkspaceId, variantId,
		)
		return query.getVariant(variantId)
	}

	private fun loadLineageCitations(variantId: UUID): List<LineageCitationRow> = sqlExecutor.query(
		"""
		select sentence_id, generation_input_id, status
		from sentence_citations
		where workspace_id = ? and content_variant_id = ?
		order by sentence_id, citation_order
		""".trimIndent(),
		{ rs, _ ->
			LineageCitationRow(
				sentenceId = requireNotNull(rs.getObject(1, UUID::class.java)),
				evidenceId = requireNotNull(rs.getObject(2, UUID::class.java)),
				status = requireNotNull(rs.getString(3)),
			)
		},
		devContext.devWorkspaceId,
		variantId,
	)

	private fun copyLineageCitations(
		variantId: UUID,
		generationRunId: UUID,
		statementId: UUID,
		sentenceRevisionId: UUID,
		lineage: List<UUID>,
		priorCitations: List<LineageCitationRow>,
		now: java.time.Instant,
	) {
		if (lineage.isEmpty()) return
		priorCitations
			.filter { it.sentenceId in lineage && it.status != "REMOVED" }
			.distinctBy { it.evidenceId }
			.forEachIndexed { index, citation ->
				sqlExecutor.update(
					"""
					insert into sentence_citations (
					 id, workspace_id, generation_run_id, content_variant_id, sentence_id,
					 sentence_revision_id, generation_input_id, citation_order, status, stale_reason, created_at, updated_at
					) values (?, ?, ?, ?, ?, ?, ?, ?, 'STALE', 'LINEAGE_DERIVED', ?, ?)
					""".trimIndent(),
					uuidGenerator.next(), devContext.devWorkspaceId, generationRunId, variantId, statementId,
					sentenceRevisionId, citation.evidenceId, index, Timestamp.from(now), Timestamp.from(now),
				)
			}
	}

	private fun confirmedCtaDestinations(variantId: UUID): Map<UUID, com.plot.api.content.ContentBriefDestination> {
		val snapshot = sqlExecutor.query(
			"""
			select ar.content_brief_snapshot::text
			from content_variants cv
			join generation_runs gr on gr.workspace_id = cv.workspace_id and gr.id = cv.generation_run_id
			join agent_runs ar on ar.workspace_id = gr.workspace_id and ar.id = gr.agent_run_id
			where cv.workspace_id = ? and cv.id = ?
			""".trimIndent(),
			{ rs, _ -> rs.getString(1) },
			devContext.devWorkspaceId,
			variantId,
		).firstOrNull()?.takeIf { it.isNotBlank() } ?: return emptyMap()
		return runCatching {
			objectMapper.readValue(snapshot, ContentBrief::class.java).destinations.associateBy { it.id }
		}.getOrDefault(emptyMap())
	}

	private fun insertNewSentence(variantId: UUID, sentenceId: UUID, now: java.time.Instant) {
		val artifactWorkflowRunId = sqlExecutor.queryForObject(
			"select generation_run_id from content_variants where workspace_id = ? and id = ?",
			UUID::class.java, devContext.devWorkspaceId, variantId,
		) ?: notFound()
		val orderIndex = (sqlExecutor.queryForObject(
			"select coalesce(max(order_index), -1) + 1 from content_variant_sentences where workspace_id = ? and content_variant_id = ?",
			Int::class.java, devContext.devWorkspaceId, variantId,
		) ?: 0)
		try {
			sqlExecutor.update(
				"insert into content_variant_sentences (id, workspace_id, generation_run_id, content_variant_id, stable_key, order_index, created_at) values (?, ?, ?, ?, ?, ?, ?)",
				sentenceId, devContext.devWorkspaceId, artifactWorkflowRunId, variantId, sentenceId.toString(), orderIndex, Timestamp.from(now),
			)
		} catch (_: DuplicateKeyException) {
			// The ownership check is workspace-scoped, so this can only be a
			// globally duplicated id from outside the workspace. Answer with a
			// generic rejection rather than a 500 or any existence signal.
			throw ApiException(HttpStatus.BAD_REQUEST, "STATEMENT_SAVE_FAILED", "Statement could not be saved")
		}
	}

	private fun lockVariant(variantId: UUID) {
		sqlExecutor.query(
			"select id from content_variants where workspace_id = ? and id = ? for update",
			{ rs, _ -> rs.getObject(1, UUID::class.java) },
			devContext.devWorkspaceId, variantId,
		).firstOrNull() ?: notFound()
	}

	private fun insertSentenceRevision(variantId: UUID, sentenceId: UUID, revisionNumber: Int, body: String, now: java.time.Instant): UUID {
		val artifactWorkflowRunId = sqlExecutor.queryForObject(
			"select generation_run_id from content_variants where workspace_id = ? and id = ?",
			UUID::class.java, devContext.devWorkspaceId, variantId,
		) ?: notFound()
		val revisionId = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into content_variant_sentence_revisions (
			 id, workspace_id, generation_run_id, content_variant_id, sentence_id,
			 revision_no, origin, body, is_current, created_by_user_id, created_at
			) values (?, ?, ?, ?, ?, ?, 'USER_MODIFIED', ?, true, ?, ?)
			""".trimIndent(),
			revisionId, devContext.devWorkspaceId, artifactWorkflowRunId, variantId, sentenceId,
			revisionNumber, body, devContext.devUserId, Timestamp.from(now),
		)
		return revisionId
	}

	private fun staleArtifactRevision(variantId: UUID): ApiException =
		ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT_REVISION", "Artifact revision is stale", variantId)



    private fun notFound(): Nothing = throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Content pack not found")
}

private data class LineageCitationRow(
	val sentenceId: UUID,
	val evidenceId: UUID,
	val status: String,
)
