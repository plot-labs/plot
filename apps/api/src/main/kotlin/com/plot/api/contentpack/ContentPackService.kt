package com.plot.api.contentpack

import com.plot.api.common.ApiException
import com.plot.api.common.JdbcTime.timestamp
import com.plot.api.common.UuidGenerator
import com.plot.api.contentpack.dto.ContentCitationResponse
import com.plot.api.contentpack.dto.ContentExportResponse
import com.plot.api.contentpack.dto.ContentPackResponse
import com.plot.api.contentpack.dto.ContentPackPageResponse
import com.plot.api.contentpack.dto.ContentPackSummaryResponse
import com.plot.api.contentpack.dto.ContentSentenceResponse
import com.plot.api.contentpack.dto.ContentVariantResponse
import com.plot.api.contentpack.dto.ExportDisposition
import com.plot.api.dev.DevContext
import com.plot.api.generation.model.CitationStatus
import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ExportSentence
import com.plot.api.generation.model.ExportSentenceStatus
import com.plot.api.generation.model.SentenceCitation
import com.plot.api.generation.model.SourceProvider
import java.time.Clock
import java.util.UUID
import java.security.MessageDigest
import java.util.HexFormat
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class ContentPackService(
	private val jdbcTemplate: JdbcTemplate,
	private val transactionTemplate: TransactionTemplate,
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val markdownExportService: MarkdownExportService,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun list(page: Int, size: Int): ContentPackPageResponse {
		require(page >= 0) { "Page must not be negative" }
		require(size in 1..100) { "Size must be between 1 and 100" }
		val total = jdbcTemplate.queryForObject(
			"select count(*) from content_packs where workspace_id = ?", Long::class.java, devContext.devWorkspaceId,
		) ?: 0L
		val items = jdbcTemplate.query(
			"""
			select id, generation_run_id, status, title from content_packs
			where workspace_id = ? order by created_at desc, id desc limit ? offset ?
			""".trimIndent(),
			{ rs, _ -> ContentPackSummaryResponse(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4)) },
			devContext.devWorkspaceId, size, page * size,
		)
		return ContentPackPageResponse(items, page, size, total, if (total == 0L) 0 else ((total + size - 1) / size).toInt())
	}

	fun get(packId: UUID): ContentPackResponse = loadPack("cp.id = ?", packId)

	fun findByRun(runId: UUID): ContentPackResponse? = try {
		loadPack("cp.generation_run_id = ?", runId)
	} catch (_: ApiException) {
		null
	}

	fun editSentence(variantId: UUID, sentenceId: UUID, expectedRevisionNumber: Int, body: String): ContentPackResponse =
		transactionTemplate.execute {
			val current = jdbcTemplate.query(
				"""
				select r.id, r.revision_no, r.generation_run_id, r.body
				from content_variant_sentence_revisions r
				where r.workspace_id = ? and r.content_variant_id = ? and r.sentence_id = ? and r.is_current
				for update
				""".trimIndent(),
				{ rs, _ -> arrayOf(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getObject(3, UUID::class.java), rs.getString(4)) },
				devContext.devWorkspaceId, variantId, sentenceId,
			).firstOrNull() ?: notFound()
			val revisionNo = current[1] as Int
			if (revisionNo != expectedRevisionNumber) throw ApiException(HttpStatus.CONFLICT, "STALE_SENTENCE_REVISION", "Sentence revision is stale", sentenceId)
			val trimmed = body.trim()
			if (trimmed.isBlank()) throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Sentence body is required")
			val now = clock.instant()
			jdbcTemplate.update(
				"update content_variant_sentence_revisions set is_current = false where workspace_id = ? and id = ? and is_current",
				devContext.devWorkspaceId, current[0],
			)
			val revisionId = uuidGenerator.next()
			jdbcTemplate.update(
				"""
				insert into content_variant_sentence_revisions (id, workspace_id, generation_run_id, content_variant_id,
				 sentence_id, revision_no, origin, body, is_current, created_by_user_id, created_at)
				values (?, ?, ?, ?, ?, ?, 'USER_MODIFIED', ?, true, ?, ?)
				""".trimIndent(),
				revisionId, devContext.devWorkspaceId, current[2], variantId, sentenceId, revisionNo + 1,
				trimmed, devContext.devUserId, timestamp(now),
			)
			jdbcTemplate.update(
				"update sentence_citations set status = 'STALE', updated_at = ? where workspace_id = ? and sentence_id = ? and status = 'ACTIVE'",
				timestamp(now), devContext.devWorkspaceId, sentenceId,
			)
			loadPack("cv.id = ?", variantId)
		}

	fun export(variantId: UUID, acknowledge: Boolean, acknowledgedRevisionIds: List<UUID>, disposition: ExportDisposition): ContentExportResponse = transactionTemplate.execute {
		val projection = loadPack("cv.id = ?", variantId)
		val runId = projection.generationRunId
		val evidence = loadEvidence(runId)
		val evidenceIds = evidence.map { it.id }.toSet()
		val exportSentences = projection.variant.sentences.map { sentence ->
			ExportSentence(
				sentence.id, sentence.revisionId, sentence.orderIndex, sentence.body,
				ExportSentenceStatus.valueOf(sentence.verdict),
				sentence.citations.filter { it.evidenceId in evidenceIds }.mapIndexed { index, citation ->
					SentenceCitation(
						sentence.id, sentence.revisionId, citation.evidenceId, index, CitationStatus.valueOf(citation.status),
					)
				},
			)
		}
		val unresolved = exportSentences.filter { it.status.isUnresolved }
		val unresolvedIds = unresolved.map { it.id }
		val unresolvedRevisionIds = unresolved.map { it.revisionId }
		if (unresolvedIds.isNotEmpty() && !acknowledge) {
			recordExport(runId, variantId, disposition, unresolvedIds.size, false, "REJECTED", null)
			throw ExportConfirmationRequiredException(unresolvedIds, unresolvedRevisionIds)
		}
		if (acknowledge && acknowledgedRevisionIds.toSet() != unresolvedRevisionIds.toSet()) {
			recordExport(runId, variantId, disposition, unresolvedIds.size, false, "REJECTED", null)
			throw ExportConfirmationRequiredException(unresolvedIds, unresolvedRevisionIds)
		}
		val rendered = markdownExportService.render(exportSentences, evidence, acknowledge)
		val exportId = recordExport(runId, variantId, disposition, rendered.unresolvedCount, rendered.warningAcknowledged, "SUCCEEDED", rendered.markdown)
		ContentExportResponse(
			exportId, disposition, "plot-changelog-${projection.id}.md", "text/markdown;charset=UTF-8", rendered.markdown,
			rendered.unresolvedCount, rendered.warningAcknowledged,
		)
	}

	private fun loadPack(predicate: String, id: UUID): ContentPackResponse {
		val header = jdbcTemplate.query(
			"""
			select cp.id, cp.generation_run_id, cp.status, cp.title, cv.id, cv.status
			from content_packs cp join content_variants cv on cv.workspace_id = cp.workspace_id and cv.content_pack_id = cp.id
			where cp.workspace_id = ? and $predicate and cv.variant_index = 0
			""".trimIndent(),
			{ rs, _ -> listOf(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getObject(5, UUID::class.java), rs.getString(6)) },
			devContext.devWorkspaceId, id,
		).firstOrNull() ?: notFound()
		val variantId = header[4] as UUID
		return ContentPackResponse(
			header[0] as UUID, header[1] as UUID, header[2] as String, header[3] as String?,
			ContentVariantResponse(variantId, header[5] as String, loadSentences(variantId)),
		)
	}

	private fun loadSentences(variantId: UUID): List<ContentSentenceResponse> {
		val citationsBySentence = loadCitations(variantId)
		return jdbcTemplate.query(
		"""
		select s.id, r.id, r.revision_no, s.order_index, r.body, r.origin,
		       case when r.origin = 'USER_MODIFIED' then 'USER_MODIFIED' else coalesce(e.verdict, 'NEEDS_SUPPORT') end,
		       e.reason
		from content_variant_sentences s
		join content_variant_sentence_revisions r on r.workspace_id=s.workspace_id and r.sentence_id=s.id and r.is_current
		left join lateral (
		 select verdict, reason from sentence_evaluations se
		 where se.workspace_id=s.workspace_id and se.sentence_revision_id=r.id
		 order by se.review_attempt desc limit 1
		) e on true
		where s.workspace_id = ? and s.content_variant_id = ? order by s.order_index
		""".trimIndent(),
		{ rs, _ ->
			val sentenceId = rs.getObject(1, UUID::class.java)
			val revisionId = rs.getObject(2, UUID::class.java)
			ContentSentenceResponse(
				sentenceId, revisionId, rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6),
				rs.getString(7), rs.getString(8), citationsBySentence[sentenceId].orEmpty(),
			)
		}, devContext.devWorkspaceId, variantId,
	)
	}

	private fun loadCitations(variantId: UUID): Map<UUID, List<ContentCitationResponse>> = jdbcTemplate.query(
		"""
		select c.sentence_id, c.generation_input_id, i.source_provider, i.source_label, i.original_url, i.snapshot_excerpt, c.status
		from sentence_citations c join generation_inputs i on i.workspace_id=c.workspace_id and i.id=c.generation_input_id
		where c.workspace_id = ? and c.content_variant_id = ? order by c.sentence_id, c.citation_order
		""".trimIndent(),
		{ rs, _ -> rs.getObject(1, UUID::class.java) to ContentCitationResponse(rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)) },
		devContext.devWorkspaceId, variantId,
	).groupBy({ it.first }, { it.second })

	private fun loadEvidence(runId: UUID): List<EvidenceSnapshot> = jdbcTemplate.query(
		"""
		select id, writing_block_id, order_index, source_provider, source_kind, source_label, snapshot_title,
		 snapshot_body, snapshot_excerpt, original_url, source_created_at, source_updated_at, content_hash, captured_at
		from generation_inputs where workspace_id = ? and generation_run_id = ? order by order_index
		""".trimIndent(),
		{ rs, _ -> EvidenceSnapshot(
			rs.getObject(1, UUID::class.java), runId, rs.getObject(2, UUID::class.java), rs.getInt(3), SourceProvider.valueOf(rs.getString(4)),
			rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
			rs.getTimestamp(11)?.toInstant(), rs.getTimestamp(12)?.toInstant(), rs.getString(13), rs.getTimestamp(14).toInstant(),
		) }, devContext.devWorkspaceId, runId,
	)

	private fun recordExport(runId: UUID, variantId: UUID, disposition: ExportDisposition, unresolved: Int, acknowledged: Boolean, status: String, output: String?): UUID =
		uuidGenerator.next().also { id ->
			jdbcTemplate.update(
				"""
				insert into generation_export_events (id, workspace_id, generation_run_id, content_variant_id, format,
				 disposition, status, unresolved_count, warning_acknowledged, output_content_hash, failure_code, created_by_user_id, created_at)
				values (?, ?, ?, ?, 'MARKDOWN', ?, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent(),
				id, devContext.devWorkspaceId, runId, variantId, disposition.name, status, unresolved, acknowledged,
				output?.let(::sha256), if (status == "REJECTED") "EXPORT_CONFIRMATION_REQUIRED" else null,
				devContext.devUserId, timestamp(clock.instant()),
			)
		}

	private fun notFound(): Nothing = throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Content pack not found")

	private fun sha256(value: String): String = HexFormat.of().formatHex(
		MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
	)
}

class ExportConfirmationRequiredException(val sentenceIds: List<UUID>, val revisionIds: List<UUID>) :
	IllegalStateException("Export requires explicit confirmation")
