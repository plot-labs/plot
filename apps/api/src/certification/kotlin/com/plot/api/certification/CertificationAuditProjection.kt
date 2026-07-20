package com.plot.api.certification

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper

data class CertificationAttemptIdentity(
	val campaignId: String,
	val campaignManifestHash: String,
	val modelExecutionId: String,
	val modelExecutionManifestHash: String,
	val attemptId: String,
	val scenarioId: String,
	val ordinal: Int,
	val workspaceId: UUID,
	val runId: UUID,
	val idempotencyKey: String,
	val sourceSnapshotSetHash: String,
)

data class CertificationInvocationAudit(
	val invocationIdHash: String,
	val role: String,
	val logicalCallIndex: Int,
	val status: String,
	val providerRequestIdHash: String?,
	val totalTokens: Int?,
	val latencyMs: Int?,
)

data class CertificationExportAudit(
	val exportIdHash: String,
	val disposition: String,
	val status: String,
	val unresolvedCount: Int,
	val warningAcknowledged: Boolean,
	val outputContentHash: String?,
)

data class CertificationAuditEnvelope(
	val schemaVersion: String = "certification-audit-v2",
	val campaignId: String,
	val campaignManifestHash: String,
	val modelExecutionId: String,
	val modelExecutionManifestHash: String,
	val attemptId: String,
	val scenarioId: String,
	val ordinal: Int,
	val recordedAt: String,
	val runStatus: String,
	val transitionVersion: Long,
	val sourceSnapshotSetHash: String,
	val invocations: List<CertificationInvocationAudit>,
	val artifactTypes: List<String>,
	val sentenceVerdictCounts: Map<String, Int>,
	val currentRevisionOriginCounts: Map<String, Int>,
	val citationStatusCounts: Map<String, Int>,
	val citationCount: Int,
	val interventionCount: Int,
	val resolvedInterventionCount: Int,
	val rewriteCount: Int,
	val exports: List<CertificationExportAudit>,
	val invocationAttributions: List<CertificationInvocationAttributionAudit> = emptyList(),
)

class CertificationAuditReconciliationException : IllegalStateException("CERTIFICATION_AUDIT_RECONCILIATION_FAILED")
class CertificationAuditWriteException : IllegalStateException("CERTIFICATION_AUDIT_WRITE_FAILED")

/** Reads certification evidence directly from durable production tables using an explicit allow-list. */
class CertificationAuditProjection(
	private val authorization: AuthorizedCertification,
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun project(identity: CertificationAttemptIdentity): CertificationAuditEnvelope {
		validateIdentity(identity)
		val runRows = jdbcTemplate.query(
			"select status, transition_version from generation_runs where workspace_id = ? and id = ? and idempotency_key = ?",
			{ rs, _ -> rs.getString(1) to rs.getLong(2) },
			identity.workspaceId, identity.runId, identity.idempotencyKey,
		)
		if (runRows.size != 1) reject()
		if (runRows.single().first !in RUN_STATUSES || runRows.single().second < 0) reject()
		val observedSnapshotHash = CertificationSourceSnapshotSetHash.compute(
			jdbcTemplate,
			identity.workspaceId,
			identity.runId,
		)
		if (!MessageDigest.isEqual(observedSnapshotHash.toByteArray(), identity.sourceSnapshotSetHash.toByteArray())) reject()

		val invocations = jdbcTemplate.query(
			"""
			select id, role, logical_call_index, status, provider_request_id, total_token_count, latency_ms
			from model_invocations where workspace_id = ? and generation_run_id = ? order by logical_call_index
			""".trimIndent(),
			{ rs, _ ->
				CertificationInvocationAudit(
					invocationIdHash = hashIdentifier(rs.getObject(1, UUID::class.java).toString()),
					role = rs.getString(2),
					logicalCallIndex = rs.getInt(3),
					status = rs.getString(4),
					providerRequestIdHash = rs.getString(5)?.let(::hashIdentifier),
					totalTokens = rs.getInt(6).let { value -> if (rs.wasNull()) null else value },
					latencyMs = rs.getInt(7).let { value -> if (rs.wasNull()) null else value },
				)
			},
			identity.workspaceId, identity.runId,
		)
		if (invocations.map { it.logicalCallIndex } != invocations.indices.toList() ||
			invocations.any { it.role !in INVOCATION_ROLES || it.status !in INVOCATION_STATUSES }
		) reject()

		val artifactTypes = jdbcTemplate.queryForList(
			"select artifact_type from generation_artifacts where workspace_id = ? and generation_run_id = ? order by sequence_no",
			String::class.java, identity.workspaceId, identity.runId,
		).filterNotNull()
		if (artifactTypes.any { it !in ARTIFACT_TYPES }) reject()
		val verdictCounts = jdbcTemplate.query(
			"select verdict, count(*) from sentence_evaluations where workspace_id = ? and generation_run_id = ? group by verdict order by verdict",
			{ rs, _ -> rs.getString(1) to rs.getInt(2) },
			identity.workspaceId, identity.runId,
		).toMap()
		if (verdictCounts.keys.any { it !in SENTENCE_VERDICTS }) reject()
		val revisionOriginCounts = jdbcTemplate.query(
			"""
			select origin, count(*) from content_variant_sentence_revisions
			where workspace_id = ? and generation_run_id = ? and is_current group by origin order by origin
			""".trimIndent(),
			{ rs, _ -> rs.getString(1) to rs.getInt(2) },
			identity.workspaceId, identity.runId,
		).toMap()
		if (revisionOriginCounts.keys.any { it !in REVISION_ORIGINS }) reject()
		val citationStatusCounts = jdbcTemplate.query(
			"""
			select status, count(*) from sentence_citations
			where workspace_id = ? and generation_run_id = ? group by status order by status
			""".trimIndent(),
			{ rs, _ -> rs.getString(1) to rs.getInt(2) },
			identity.workspaceId, identity.runId,
		).toMap()
		if (citationStatusCounts.keys.any { it !in CITATION_STATUSES }) reject()
		val intervention = jdbcTemplate.queryForMap(
			"""
			select count(*) as total, count(*) filter (where status = 'RESOLVED') as resolved
			from generation_interventions where workspace_id = ? and generation_run_id = ?
			""".trimIndent(),
			identity.workspaceId, identity.runId,
		)
		val exportRows = jdbcTemplate.query(
			"""
			select id, content_variant_id, disposition, status, unresolved_count, warning_acknowledged,
			       output_content_hash, failure_code, created_by_user_id, created_at
			from generation_export_events where workspace_id = ? and generation_run_id = ? order by created_at, id
			""".trimIndent(),
			{ rs, _ -> CertificationExportRow(
				exportId = rs.getObject(1, UUID::class.java),
				variantId = rs.getObject(2, UUID::class.java),
				disposition = rs.getString(3),
				status = rs.getString(4),
				unresolvedCount = rs.getInt(5),
				warningAcknowledged = rs.getBoolean(6),
				outputContentHash = rs.getString(7),
				failureCode = rs.getString(8),
				createdByUserId = rs.getObject(9, UUID::class.java),
				createdAt = rs.getTimestamp(10).toInstant(),
			) },
			identity.workspaceId, identity.runId,
		)
		CertificationExportSequenceValidator.validate(exportRows)
		val exports = exportRows.map { row ->
			CertificationExportAudit(
				exportIdHash = hashIdentifier(row.exportId.toString()),
				disposition = row.disposition,
				status = row.status,
				unresolvedCount = row.unresolvedCount,
				warningAcknowledged = row.warningAcknowledged,
				outputContentHash = row.outputContentHash,
			)
		}
		return CertificationAuditEnvelope(
			campaignId = identity.campaignId,
			campaignManifestHash = identity.campaignManifestHash,
			modelExecutionId = identity.modelExecutionId,
			modelExecutionManifestHash = identity.modelExecutionManifestHash,
			attemptId = identity.attemptId,
			scenarioId = identity.scenarioId,
			ordinal = identity.ordinal,
			recordedAt = clock.instant().toString(),
			runStatus = runRows.single().first,
			transitionVersion = runRows.single().second,
			sourceSnapshotSetHash = observedSnapshotHash,
			invocations = invocations,
			artifactTypes = artifactTypes,
			sentenceVerdictCounts = verdictCounts,
			currentRevisionOriginCounts = revisionOriginCounts,
			citationStatusCounts = citationStatusCounts,
			citationCount = citationStatusCounts.values.sum(),
			interventionCount = (intervention["total"] as Number).toInt(),
			resolvedInterventionCount = (intervention["resolved"] as Number).toInt(),
			rewriteCount = invocations.count { it.role == "REWRITER" && it.status == "SUCCEEDED" },
			exports = exports,
		)
	}

	private fun validateIdentity(identity: CertificationAttemptIdentity) {
		if (identity.campaignId != authorization.certificationId ||
			!HASH.matches(identity.campaignManifestHash) ||
			!Regex("^model-execution-[a-f0-9]{16,64}$").matches(identity.modelExecutionId) ||
			!HASH.matches(identity.modelExecutionManifestHash) ||
			!Regex("^attempt-[a-f0-9]{16,64}$").matches(identity.attemptId) ||
			!SCENARIO_ID.matches(identity.scenarioId) || identity.ordinal !in 1..3 ||
			!Regex("^sha256:[a-f0-9]{64}$").matches(identity.sourceSnapshotSetHash) ||
			!IDEMPOTENCY_KEY.matches(identity.idempotencyKey) ||
			!identity.idempotencyKey.endsWith(":${identity.attemptId}")
		) reject()
	}

	private fun reject(): Nothing = throw CertificationAuditReconciliationException()

	companion object {
		private val HASH = Regex("^sha256:[a-f0-9]{64}$")
		private val SCENARIO_ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
		private val IDEMPOTENCY_KEY = Regex("^namespace-[a-f0-9]{16,64}:attempt-[a-f0-9]{16,64}$")
		private val RUN_STATUSES = setOf("QUEUED", "WRITING", "REVIEWING", "REWRITING", "READY", "NEEDS_YOUR_CALL", "NEEDS_REVIEW", "FAILED")
		private val INVOCATION_ROLES = setOf("WRITER", "REVIEWER", "REWRITER")
		private val INVOCATION_STATUSES = setOf("PENDING", "RUNNING", "SUCCEEDED", "FAILED")
		private val ARTIFACT_TYPES = setOf("EVIDENCE_SET", "WRITER_OUTPUT", "REVIEWER_OUTPUT", "REWRITER_OUTPUT", "CONFLICT_DECISION", "FINAL_OUTPUT")
		private val SENTENCE_VERDICTS = setOf("SUPPORTED", "NOT_REQUIRED", "NEEDS_SUPPORT", "CONFLICT")
		private val REVISION_ORIGINS = setOf("GENERATED", "REWRITTEN", "USER_MODIFIED")
		private val CITATION_STATUSES = setOf("ACTIVE", "STALE", "REMOVED")
	}
}

/** The single canonical digest algorithm for a run's immutable source snapshot set. */
data class CertificationSourceSnapshotIdentity(
	val writingBlockId: UUID,
	val sourceProvider: String,
	val sourceKind: String,
	val originalUrl: String,
	val contentHash: String,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
)

object CertificationSourceSnapshotSetHash {
	fun compute(jdbcTemplate: JdbcTemplate, workspaceId: UUID, runId: UUID): String {
		val rows = jdbcTemplate.query(
			"""
			select writing_block_id, source_provider, source_kind, original_url, content_hash,
			       source_created_at, source_updated_at
			from generation_inputs
			where workspace_id = ? and generation_run_id = ?
			""".trimIndent(),
			{ rs, _ -> CertificationSourceSnapshotIdentity(
				rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getTimestamp(6)?.toInstant(), rs.getTimestamp(7)?.toInstant(),
			) },
			workspaceId, runId,
		)
		return compute(rows)
	}

	fun compute(rows: List<CertificationSourceSnapshotIdentity>): String {
		if (rows.isEmpty() || rows.map { it.writingBlockId }.distinct().size != rows.size || rows.any {
			it.sourceProvider.isBlank() || it.sourceKind.isBlank() || it.originalUrl.isBlank() || it.contentHash.isBlank()
		}) throw CertificationAuditReconciliationException()
		return sha256(rows.map { row -> listOf(
			row.writingBlockId.toString(), row.sourceProvider, row.sourceKind, row.originalUrl, row.contentHash,
			row.sourceCreatedAt?.toString().orEmpty(), row.sourceUpdatedAt?.toString().orEmpty(),
		).joinToString("\u0000") }.sorted().joinToString("\n"))
	}
}

data class CertificationExportRow(
	val exportId: UUID,
	val variantId: UUID?,
	val disposition: String,
	val status: String,
	val unresolvedCount: Int,
	val warningAcknowledged: Boolean,
	val outputContentHash: String?,
	val failureCode: String?,
	val createdByUserId: UUID?,
	val createdAt: Instant,
)

object CertificationExportSequenceValidator {
	fun validate(exports: List<CertificationExportRow>) {
		if (exports.size > 2 || exports.any {
			it.disposition !in EXPORT_DISPOSITIONS || it.status !in EXPORT_STATUSES ||
				it.unresolvedCount < 0 || it.createdByUserId == null || it.variantId == null
		}) reject()
		exports.forEach { export ->
			val contentHashValid = export.outputContentHash?.matches(Regex("^(?:sha256:)?[a-f0-9]{64}$")) == true
			when (export.status) {
				"REJECTED" -> if (export.unresolvedCount == 0 || export.warningAcknowledged ||
					export.outputContentHash != null || export.failureCode != "EXPORT_CONFIRMATION_REQUIRED"
				) reject()
				"SUCCEEDED" -> if (!contentHashValid || export.failureCode != null ||
					(export.unresolvedCount > 0 && !export.warningAcknowledged)
				) reject()
			}
		}
		if (exports.size == 2) {
			val rejected = exports[0]
			val succeeded = exports[1]
			if (rejected.status != "REJECTED" || succeeded.status != "SUCCEEDED" ||
				rejected.variantId != succeeded.variantId || rejected.createdByUserId != succeeded.createdByUserId ||
				rejected.disposition != succeeded.disposition || rejected.unresolvedCount != succeeded.unresolvedCount ||
				!succeeded.warningAcknowledged || !rejected.createdAt.isBefore(succeeded.createdAt)
			) reject()
		}
	}

	private fun reject(): Nothing = throw CertificationAuditReconciliationException()
	private val EXPORT_DISPOSITIONS = setOf("COPY", "DOWNLOAD")
	private val EXPORT_STATUSES = setOf("SUCCEEDED", "REJECTED")
}

class CertificationAuditEnvelopeWriter(
	private val root: Path,
	private val mapper: ObjectMapper = ObjectMapper(),
) {
	fun write(envelope: CertificationAuditEnvelope): Path {
		if (!Regex("^campaign-[a-f0-9]{16,64}$").matches(envelope.campaignId) ||
			!Regex("^attempt-[a-f0-9]{16,64}$").matches(envelope.attemptId)
		) throw CertificationAuditWriteException()
		val normalizedRoot = root.toAbsolutePath().normalize()
		val campaignDirectory = normalizedRoot.resolve(envelope.campaignId).resolve("audit").normalize()
		val target = campaignDirectory.resolve("${envelope.attemptId}.json").normalize()
		if (!campaignDirectory.startsWith(normalizedRoot) || !target.startsWith(campaignDirectory)) {
			throw CertificationAuditWriteException()
		}
		try {
			Files.createDirectories(campaignDirectory)
			Files.newBufferedWriter(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
				mapper.writeValue(it, envelope)
			}
		} catch (_: Exception) {
			throw CertificationAuditWriteException()
		}
		return target
	}
}

private fun hashIdentifier(value: String): String = sha256(value)
