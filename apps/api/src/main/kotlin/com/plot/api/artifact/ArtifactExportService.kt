package com.plot.api.artifact

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.artifact.dto.ContentExportResponse
import com.plot.api.artifact.dto.ExportDisposition
import com.plot.api.artifact.dto.ExportWarningResponse
import com.plot.api.dev.DevContext
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.util.HexFormat
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class ArtifactExportService(
	private val sqlExecutor: JooqSqlExecutor,
	private val transactionExecutor: JooqTransactionExecutor,
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val objectMapper: ObjectMapper,
	private val query: ArtifactQueryService,
	private val deliveryGate: ArtifactDeliveryGate,
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
			when (val gate = deliveryGate.prepare(
				variantId,
				expectedRevisionNumber,
				includeSources,
				acknowledge,
				acknowledgedWarningKeys,
				legacyAcknowledgedRevisionIds,
			)) {
				is DeliveryGateOutcome.ConfirmationRequired -> {
					recordExport(
						gate.revision,
						gate.artifactWorkflowRunId,
						variantId,
						disposition,
						includeSources,
						gate.exportSentences,
						acknowledgedWarningKeys.ifEmpty { gate.warningKeys },
						false,
						"REJECTED",
						null,
						null,
					)
					return@execute ExportAttempt.ConfirmationRequired(gate.warnings)
				}
				is DeliveryGateOutcome.Ready -> {
					val projection = query.getVariant(variantId)
					val outputHash = sha256(gate.rendered.markdown)
					val sourceInputs = gate.sources
						.sortedBy { it.originalUrl }
						.joinToString("|") { "${it.originalUrl}|${it.provider}|${it.sourceLabel}" }
					val inputHash = sha256(
						listOf(
							gate.revision.id,
							gate.revision.revisionNumber,
							MARKDOWN_RENDERER_VERSION,
							includeSources,
							gate.rendered.warningAcknowledged,
							gate.warningKeys.sorted(),
							sourceInputs,
						).joinToString("|"),
					)
					val exportId = findSuccessfulExport(
						gate.revision,
						gate.artifactWorkflowRunId,
						variantId,
						disposition,
						includeSources,
						gate.rendered.unresolvedCount,
						gate.rendered.warningAcknowledged,
						inputHash,
						outputHash,
					) ?: recordExport(
						gate.revision,
						gate.artifactWorkflowRunId,
						variantId,
						disposition,
						includeSources,
						gate.exportSentences,
						gate.warningKeys,
						gate.rendered.warningAcknowledged,
						"SUCCEEDED",
						inputHash,
						outputHash,
					)
					ExportAttempt.Completed(ContentExportResponse(
						exportId,
						gate.revision.id,
						gate.revision.revisionNumber,
						disposition,
						"plot-changelog-${projection.id}.md",
						"text/markdown;charset=UTF-8",
						gate.rendered.markdown,
						gate.rendered.unresolvedCount,
						gate.rendered.warningAcknowledged,
						includeSources,
					))
				}
			}
		}
		return when (outcome) {
			is ExportAttempt.Completed -> outcome.response
			is ExportAttempt.ConfirmationRequired -> throw ExportConfirmationRequiredException(outcome.warnings)
		}
	}

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
		sentences: List<com.plot.api.artifact.workflow.model.ExportSentence>,
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

	private fun sha256(value: String): String = HexFormat.of().formatHex(
		MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
	)

	private companion object {
		const val MARKDOWN_RENDERER_VERSION = "markdown-v2"
	}
}

private sealed interface ExportAttempt {
	data class Completed(val response: ContentExportResponse) : ExportAttempt
	data class ConfirmationRequired(val warnings: List<ExportWarningResponse>) : ExportAttempt
}

class ExportConfirmationRequiredException(
	val warnings: List<ExportWarningResponse>,
) : IllegalStateException("Export requires explicit confirmation")
