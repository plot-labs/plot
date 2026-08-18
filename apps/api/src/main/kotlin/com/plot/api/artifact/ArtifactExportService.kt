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
class ArtifactExportService(
    private val sqlExecutor: JooqSqlExecutor,
    private val transactionExecutor: JooqTransactionExecutor,
    private val devContext: DevContext,
    private val uuidGenerator: UuidGenerator,
    private val objectMapper: ObjectMapper,
    private val query: ArtifactQueryService,
    private val materializer: ArtifactRevisionMaterializer,
    private val markdownExportService: ArtifactMarkdownExportService,
    private val clock: Clock = Clock.systemUTC(),
) {
	fun export(
		variantId: UUID,
		expectedRevisionNumber: Int,
		includeSources: Boolean,
		acknowledge: Boolean,
		acknowledgedWarningKeys: List<String>,
		legacyAcknowledgedRevisionIds: List<UUID>,
		disposition: ExportDisposition,
	): ContentExportResponse {
		val outcome = transactionExecutor.execute {
			sqlExecutor.query(
				"select id from content_variants where workspace_id = ? and id = ? for update",
				{ rs, _ -> rs.getObject(1, UUID::class.java) },
				devContext.devWorkspaceId,
				variantId,
			).firstOrNull() ?: notFound()
			val revision = materializer.ensureArtifactRevision(variantId)
			if (expectedRevisionNumber != revision.revisionNumber) {
				throw staleArtifactRevision(variantId)
			}
			val projection = query.getVariant(variantId)
			val artifactWorkflowRunId = query.artifactWorkflowRunIdForVariant(variantId)
			val evidence = query.loadEvidence(artifactWorkflowRunId)
			val publicCitations = query.loadPublicCitations(variantId, revision.id)
			val exportSentences = query.loadExportSentences(variantId, revision.id, publicCitations)
			val unresolved = exportSentences.filter { it.status.isUnresolved }
			val warnings = unresolved.map { sentence ->
				ExportWarningResponse(
					warningKey(revision.id, sentence),
					sentence.orderIndex + 1,
					sentence.body.trim().replace(WHITESPACE, " ").take(MAX_WARNING_EXCERPT),
				)
			}
			val expectedWarningKeys = warnings.mapTo(linkedSetOf()) { it.key }
			val legacyRevisionSet = legacyAcknowledgedRevisionIds.toSet()
			val legacyMatches = legacyRevisionSet == unresolved.map { it.revisionId }.toSet()
			val keyMatches = acknowledgedWarningKeys.toSet() == expectedWarningKeys
			if (unresolved.isNotEmpty() && !acknowledge) {
				recordExport(
					revision, artifactWorkflowRunId, variantId, disposition, includeSources,
					unresolved, warnings.map { it.key },  false, "REJECTED", null, null,
				)
				return@execute ExportAttempt.ConfirmationRequired(warnings, unresolved.map { it.id }, unresolved.map { it.revisionId })
			}
			if (unresolved.isNotEmpty() && acknowledge && !keyMatches && !legacyMatches) {
				recordExport(
					revision, artifactWorkflowRunId, variantId, disposition, includeSources,
					unresolved, acknowledgedWarningKeys, false, "REJECTED", null, null,
				)
				return@execute ExportAttempt.ConfirmationRequired(warnings, unresolved.map { it.id }, unresolved.map { it.revisionId })
			}

			val rendered = markdownExportService.render(
				exportSentences,
				evidence,
				acknowledgeUnresolved = acknowledge && unresolved.isNotEmpty(),
				includeSources = includeSources,
				 sources = publicCitations.values.flatten()
					.distinctBy { it.originalUrl }
					.map { ExportSource(it.evidenceId, it.provider, it.sourceLabel, it.originalUrl) },
			)
			val outputHash = sha256(rendered.markdown)
			val sourceInputs = publicCitations.values.flatten()
				.distinctBy { it.originalUrl }
				.sortedBy { it.originalUrl }
				.joinToString("|") { "${it.originalUrl}|${it.provider}|${it.sourceLabel}" }
			val inputHash = sha256(
				listOf(
					revision.id,
					revision.revisionNumber,
					MARKDOWN_RENDERER_VERSION,
					includeSources,
					acknowledge && unresolved.isNotEmpty(),
					warnings.map { it.key }.sorted(),
					sourceInputs,
				).joinToString("|"),
			)
			val exportId = findSuccessfulExport(
				revision, artifactWorkflowRunId, variantId, disposition, includeSources,
				rendered.unresolvedCount, rendered.warningAcknowledged, inputHash, outputHash,
			) ?: recordExport(
				revision, artifactWorkflowRunId, variantId, disposition, includeSources,
				exportSentences, warnings.map { it.key }, rendered.warningAcknowledged,
				"SUCCEEDED", inputHash, outputHash,
			)
			ExportAttempt.Completed(ContentExportResponse(
				exportId,
				revision.id,
				revision.revisionNumber,
				disposition,
				"plot-changelog-${projection.id}.md",
				"text/markdown;charset=UTF-8",
				rendered.markdown,
				rendered.unresolvedCount,
				rendered.warningAcknowledged,
				includeSources,
			))
		}
		return when (outcome) {
			is ExportAttempt.Completed -> outcome.response
			is ExportAttempt.ConfirmationRequired -> throw ExportConfirmationRequiredException(
				outcome.warnings,
				outcome.sentenceIds,
				outcome.revisionIds,
			)
		}
	}

	private fun staleArtifactRevision(variantId: UUID): ApiException =
		ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT_REVISION", "Artifact revision is stale", variantId)

	private fun notFound(): Nothing = throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Content pack not found")

	private fun findSuccessfulExport(
		revision: CurrentArtifactRevision,
		runId: UUID,
		variantId: UUID,
		disposition: ExportDisposition,
		includeSources: Boolean,
		unresolved: Int,
		acknowledged: Boolean,
		inputHash: String,
		outputHash: String,
	): UUID? = sqlExecutor.query(
		"""
		select id from generation_export_events
		where workspace_id = ? and generation_run_id = ? and content_variant_id = ?
		  and artifact_revision_id = ? and artifact_revision_no = ?
		  and format = 'MARKDOWN' and disposition = ? and status = 'SUCCEEDED'
		  and unresolved_count = ? and warning_acknowledged = ?
		  and include_sources = ? and renderer_version = ?
		  and export_input_hash = ? and output_content_hash = ? and created_by_user_id = ?
		order by created_at, id limit 1
		""".trimIndent(),
		{ rs, _ -> rs.getObject(1, UUID::class.java) },
		devContext.devWorkspaceId, runId, variantId, revision.id, revision.revisionNumber,
		disposition.name, unresolved, acknowledged, includeSources, MARKDOWN_RENDERER_VERSION,
		inputHash, outputHash, devContext.devUserId,
	).firstOrNull()

	private fun recordExport(
		revision: CurrentArtifactRevision,
		runId: UUID,
		variantId: UUID,
		disposition: ExportDisposition,
		includeSources: Boolean,
		sentences: List<ExportSentence>,
		warningKeys: List<String>,
		acknowledged: Boolean,
		status: String,
		inputHash: String?,
		outputHash: String?,
	): UUID = uuidGenerator.next().also { id ->
			sqlExecutor.update(
				"""
				insert into generation_export_events (
				 id, workspace_id, generation_run_id, content_variant_id, artifact_revision_id,
				 artifact_revision_no, format, disposition, status, unresolved_count,
				 warning_acknowledged, sentence_ids, acknowledged_warning_keys, include_sources,
				 renderer_version, export_input_hash, output_content_hash, failure_code,
				 created_by_user_id, created_at
				) values (?, ?, ?, ?, ?, ?, 'MARKDOWN', ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent(),
				id, devContext.devWorkspaceId, runId, variantId, revision.id, revision.revisionNumber,
				disposition.name, status, sentences.count { it.status.isUnresolved }, acknowledged,
				objectMapper.writeValueAsString(sentences.map { it.id }), objectMapper.writeValueAsString(warningKeys),
				includeSources, MARKDOWN_RENDERER_VERSION, inputHash, outputHash,
				if (status == "REJECTED") "EXPORT_CONFIRMATION_REQUIRED" else null,
				devContext.devUserId, Timestamp.from(clock.instant()),
			)
		}

	private fun warningKey(revisionId: UUID, sentence: ExportSentence): String = sha256(
		listOf(revisionId, sentence.id, sentence.revisionId, sentence.orderIndex, sentence.body).joinToString("|"),
	)

	private fun sha256(value: String): String = HexFormat.of().formatHex(
		MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
	)



    private companion object {
        const val MARKDOWN_RENDERER_VERSION = "markdown-v2"
        const val MAX_WARNING_EXCERPT = 240
        val WHITESPACE = Regex("\\s+")
    }
}

private sealed interface ExportAttempt {
    data class Completed(val response: ContentExportResponse) : ExportAttempt
    data class ConfirmationRequired(
        val warnings: List<ExportWarningResponse>,
        val sentenceIds: List<UUID>,
        val revisionIds: List<UUID>,
    ) : ExportAttempt
}

class ExportConfirmationRequiredException(
    val warnings: List<ExportWarningResponse>,
    val sentenceIds: List<UUID>,
    val revisionIds: List<UUID>,
) : IllegalStateException("Export requires explicit confirmation")
