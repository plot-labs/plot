package com.plot.api.artifact

import com.plot.api.common.ApiException
import com.plot.api.artifact.dto.ExportWarningResponse
import com.plot.api.artifact.workflow.model.ExportSource
import com.plot.api.dev.DevContext
import com.plot.api.persistence.JooqSqlExecutor
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class ArtifactDeliveryGate(
	private val sqlExecutor: JooqSqlExecutor,
	private val devContext: DevContext,
	private val query: ArtifactQueryService,
	private val materializer: ArtifactRevisionMaterializer,
	private val markdownExportService: ArtifactMarkdownExportService,
) {
	internal fun prepare(
		variantId: UUID,
		expectedRevisionNumber: Int,
		includeSources: Boolean,
		acknowledge: Boolean,
		acknowledgedWarningKeys: List<String>,
		legacyAcknowledgedRevisionIds: List<UUID>,
	): DeliveryGateOutcome {
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
			return rejectionOutcome(revision, artifactWorkflowRunId, exportSentences, warnings)
		}
		if (unresolved.isNotEmpty() && acknowledge && !keyMatches && !legacyMatches) {
			return rejectionOutcome(revision, artifactWorkflowRunId, exportSentences, warnings)
		}
		val sources = publicCitations.values.flatten()
			.distinctBy { it.originalUrl }
			.map { ExportSource(it.evidenceId, it.provider, it.sourceLabel, it.originalUrl) }
		val rendered = markdownExportService.render(
			exportSentences,
			evidence,
			acknowledgeUnresolved = acknowledge && unresolved.isNotEmpty(),
			includeSources = includeSources,
			sources = sources,
		)
		return DeliveryGateOutcome.Ready(
			revision = revision,
			artifactWorkflowRunId = artifactWorkflowRunId,
			exportSentences = exportSentences,
			rendered = rendered,
			publicCitations = publicCitations,
			warnings = warnings,
			warningKeys = warnings.map { it.key },
			sources = sources,
		)
	}

	private fun staleArtifactRevision(variantId: UUID): ApiException =
		ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT_REVISION", "Artifact revision is stale", variantId)

	private fun notFound(): Nothing = throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Content pack not found")

	private fun rejectionOutcome(
		revision: CurrentArtifactRevision,
		artifactWorkflowRunId: UUID,
		exportSentences: List<com.plot.api.artifact.workflow.model.ExportSentence>,
		warnings: List<ExportWarningResponse>,
	): DeliveryGateOutcome.ConfirmationRequired = DeliveryGateOutcome.ConfirmationRequired(
		warnings = warnings,
		revision = revision,
		artifactWorkflowRunId = artifactWorkflowRunId,
		exportSentences = exportSentences,
		warningKeys = warnings.map { it.key },
	)

	internal fun warningKey(revisionId: UUID, sentence: com.plot.api.artifact.workflow.model.ExportSentence): String = sha256(
		listOf(revisionId, sentence.id, sentence.revisionId, sentence.orderIndex, sentence.body).joinToString("|"),
	)

	private fun sha256(value: String): String = HexFormat.of().formatHex(
		MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
	)

	private companion object {
		const val MAX_WARNING_EXCERPT = 240
		val WHITESPACE = Regex("\\s+")
	}
}

internal sealed interface DeliveryGateOutcome {
	data class ConfirmationRequired(
		val warnings: List<ExportWarningResponse>,
		val revision: CurrentArtifactRevision,
		val artifactWorkflowRunId: UUID,
		val exportSentences: List<com.plot.api.artifact.workflow.model.ExportSentence>,
		val warningKeys: List<String>,
	) : DeliveryGateOutcome
	data class Ready(
		val revision: CurrentArtifactRevision,
		val artifactWorkflowRunId: UUID,
		val exportSentences: List<com.plot.api.artifact.workflow.model.ExportSentence>,
		val rendered: com.plot.api.artifact.workflow.model.MarkdownExport,
		val publicCitations: Map<UUID, List<PublicCitation>>,
		val warnings: List<ExportWarningResponse>,
		val warningKeys: List<String>,
		val sources: List<ExportSource>,
	) : DeliveryGateOutcome
}

class PublishConfirmationRequiredException(
	val warnings: List<ExportWarningResponse>,
) : IllegalStateException("Publish requires explicit confirmation")
