package com.plot.api.certification

import com.plot.api.dev.DevContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

object CertificationRestartEvidenceExtractor {
	@JvmStatic
	fun main(args: Array<String>) {
		if (args.isNotEmpty()) reject()
		val env = System.getenv()
		if (PROVIDER_CREDENTIALS.any(env::containsKey)) reject()
		fun required(name: String) = env[name]?.takeIf(String::isNotBlank) ?: reject()
		val databaseUrl = required("PLOT_CERTIFICATION_DATABASE_URL")
		val jdbc = JdbcTemplate(DriverManagerDataSource(
			databaseUrl, required("PLOT_CERTIFICATION_DATABASE_USERNAME"),
			required("PLOT_CERTIFICATION_DATABASE_PASSWORD"),
		))
		CertificationDatabaseTargetPolicy.validate(
			databaseUrl,
			required("PLOT_CERTIFICATION_DATABASE_NAME"),
			required("PLOT_CERTIFICATION_DATABASE_FINGERPRINT"),
			required("PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN"),
			jdbc,
		)
		val mapper = jacksonObjectMapper()
		val contract = CertificationArtifactContract(mapper)
		val campaign = contract.sealCampaign(readJson(mapper, required("PLOT_CERTIFICATION_CAMPAIGN_MANIFEST")))
		val execution = contract.sealModelExecution(
			readJson(mapper, required("PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST")),
			campaign,
		)
		if (campaign.artifact.campaignId != required("PLOT_CERTIFICATION_CAMPAIGN_ID") ||
			campaign.hash != required("PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH") ||
			execution.artifact.modelExecutionId != required("PLOT_CERTIFICATION_MODEL_EXECUTION_ID") ||
			execution.hash != required("PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH")
		) reject()
		val workspaceId = DevContext().devWorkspaceId
		val key = required("PLOT_CERTIFICATION_IDEMPOTENCY_KEY")
		val runRows = jdbc.query(
			"""
			select id, created_by_user_id, idempotency_key, request_fingerprint, status, provider, model_name
			from generation_runs where workspace_id = ? and idempotency_key = ?
			""".trimIndent(),
			{ rs, _ -> RestartRunRow(
				runId = rs.getObject(1, UUID::class.java),
				createdByUserId = rs.getObject(2, UUID::class.java),
				idempotencyKey = rs.getString(3),
				requestFingerprint = rs.getString(4),
				status = rs.getString(5),
				provider = rs.getString(6),
				modelName = rs.getString(7),
			) }, workspaceId, key,
		)
		if (runRows.size != 1) reject()
		val run = runRows.single()
		val runId = run.runId
		val attemptId = required("PLOT_CERTIFICATION_ATTEMPT_ID")
		if (!Regex("^attempt-[a-f0-9]{16,64}$").matches(attemptId) || !key.endsWith(":$attemptId") ||
			run.idempotencyKey != key || run.provider != "OPENROUTER" ||
			run.modelName != execution.artifact.requestedModel
		) reject()
		val observedSourceHash = runCatching {
			CertificationSourceSnapshotSetHash.compute(jdbc, workspaceId, runId)
		}.getOrElse { reject() }
		val expectedSourceHash = required("PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH")
		if (!MessageDigest.isEqual(observedSourceHash.toByteArray(), expectedSourceHash.toByteArray()) ||
			observedSourceHash != campaign.artifact.sourceSnapshotSetHash
		) reject()
		fun count(sql: String, vararg args: Any): Int = jdbc.queryForObject(sql, Int::class.java, *args) ?: 0
		fun roleCount(role: String) = count(
			"select count(*)::int from model_invocations where workspace_id = ? and generation_run_id = ? and role = ? and status = 'SUCCEEDED'",
			workspaceId, runId, role,
		)
		if (count(
			"""
			select count(*)::int from model_invocations where workspace_id = ? and generation_run_id = ?
			and (provider <> ? or model_name <> ?)
			""".trimIndent(),
			workspaceId, runId, run.provider, run.modelName,
		) != 0) reject()
		val exports = exportRows(jdbc, workspaceId, runId)
		runCatching { CertificationExportSequenceValidator.validate(exports) }.getOrElse { reject() }
		val checkpoint = runCatching {
			RestartCheckpointArtifact.valueOf(required("PLOT_CERTIFICATION_RESTART_CHECKPOINT"))
		}.getOrElse { reject() }
		val uniqueness = CertificationRestartDistinctKeys(
			sourceInputRows = countRows(jdbc, "generation_inputs", workspaceId, runId),
			sourceInputDistinctKeys = count(
				"""
				select least(count(distinct order_index), count(distinct writing_block_id))::int
				from generation_inputs where workspace_id = ? and generation_run_id = ?
				""".trimIndent(), workspaceId, runId,
			),
			workflowStepRows = countRows(jdbc, "generation_workflow_steps", workspaceId, runId),
			workflowStepDistinctKeys = count(
				"""
				select least(count(distinct sequence_no), count(distinct (step_kind, semantic_attempt)))::int
				from generation_workflow_steps where workspace_id = ? and generation_run_id = ?
				""".trimIndent(), workspaceId, runId,
			),
			modelInvocationRows = countRows(jdbc, "model_invocations", workspaceId, runId),
			modelInvocationDistinctKeys = count(
				"select count(distinct logical_call_index)::int from model_invocations where workspace_id = ? and generation_run_id = ?",
				workspaceId, runId,
			),
			artifactRows = countRows(jdbc, "generation_artifacts", workspaceId, runId),
			artifactDistinctKeys = count(
				"""
				select least(count(distinct sequence_no), count(distinct (artifact_type, artifact_version)))::int
				from generation_artifacts where workspace_id = ? and generation_run_id = ?
				""".trimIndent(), workspaceId, runId,
			),
			citationRows = countRows(jdbc, "sentence_citations", workspaceId, runId),
			citationDistinctKeys = count(
				"""
				select least(count(distinct (sentence_revision_id, generation_input_id)),
				             count(distinct (sentence_revision_id, citation_order)))::int
				from sentence_citations where workspace_id = ? and generation_run_id = ?
				""".trimIndent(), workspaceId, runId,
			),
			interventionRows = countRows(jdbc, "generation_interventions", workspaceId, runId),
			interventionDistinctKeys = count(
				"select count(distinct sentence_id)::int from generation_interventions where workspace_id = ? and generation_run_id = ?",
				workspaceId, runId,
			),
			contentPackRows = countRows(jdbc, "content_packs", workspaceId, runId),
			contentPackDistinctKeys = count(
				"select count(distinct generation_run_id)::int from content_packs where workspace_id = ? and generation_run_id = ?",
				workspaceId, runId,
			),
			exportEventRows = exports.size,
			exportEventDistinctKeys = exports.map { it.status }.distinct().size,
		)
		val state = CertificationRestartDurableState(
			campaignId = campaign.artifact.campaignId,
			campaignManifestHash = campaign.hash,
			modelExecutionId = execution.artifact.modelExecutionId,
			modelExecutionManifestHash = execution.hash,
			attemptId = attemptId,
			sourceSnapshotSetHash = observedSourceHash,
			workspaceIdHash = sha256(workspaceId.toString()),
			runIdHash = sha256(runId.toString()),
			createdByUserIdHash = sha256(run.createdByUserId.toString()),
			idempotencyKeyHash = sha256(run.idempotencyKey),
			requestFingerprintHash = sha256(run.requestFingerprint),
			provider = run.provider,
			modelName = run.modelName,
			checkpointArtifact = checkpoint,
			checkpointArtifactCount = count(
				"select count(*)::int from generation_artifacts where workspace_id = ? and generation_run_id = ? and artifact_type = ?",
				workspaceId, runId, checkpoint.name,
			),
			runStatus = run.status,
			writerSucceededCount = roleCount("WRITER"),
			reviewerSucceededCount = roleCount("REVIEWER"),
			rewriterSucceededCount = roleCount("REWRITER"),
			citationCount = uniqueness.citationRows,
			interventionCount = uniqueness.interventionRows,
			contentPackCount = uniqueness.contentPackRows,
			exportEventCount = uniqueness.exportEventRows,
			uniqueness = uniqueness,
		)
		val target = Path.of(required("PLOT_CERTIFICATION_RESTART_STATE_OUTPUT"))
		if (!target.isAbsolute || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) reject()
		Files.createDirectories(target.parent)
		if (Files.isSymbolicLink(target.parent)) reject()
		Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
			mapper.writeValue(it, state)
		}
	}

	private fun readJson(mapper: ObjectMapper, value: String) = Path.of(value).let { path ->
		if (!path.isAbsolute || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) !in 1..MAX_INPUT_SIZE) reject()
		Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(mapper::readTree)
	}

	private fun countRows(jdbc: JdbcTemplate, table: String, workspaceId: UUID, runId: UUID): Int =
		jdbc.queryForObject(
			"select count(*)::int from $table where workspace_id = ? and generation_run_id = ?",
			Int::class.java, workspaceId, runId,
		) ?: 0

	private fun exportRows(jdbc: JdbcTemplate, workspaceId: UUID, runId: UUID): List<CertificationExportRow> = jdbc.query(
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
		) }, workspaceId, runId,
	)

	private fun reject(): Nothing = throw CertificationRestartEvidenceException()
	private data class RestartRunRow(
		val runId: UUID,
		val createdByUserId: UUID,
		val idempotencyKey: String,
		val requestFingerprint: String,
		val status: String,
		val provider: String,
		val modelName: String,
	)

	private const val MAX_INPUT_SIZE = 1024L * 1024L
	private val PROVIDER_CREDENTIALS = setOf(
		"OPENROUTER_API_KEY", "OPENAI_API_KEY", "SPRING_AI_OPENAI_API_KEY", "GITHUB_TOKEN", "GH_TOKEN",
		"GITHUB_APP_PRIVATE_KEY", "GITHUB_INSTALLATION_TOKEN", "PLOT_GITHUB_PRIVATE_KEY", "PLOT_GITHUB_STATE_SECRET",
	)
}

object CertificationRestartReconcileCli {
	@JvmStatic
	fun main(args: Array<String>) {
		if (args.isNotEmpty() || PROVIDER_CREDENTIALS.any(System.getenv()::containsKey)) throw CertificationRestartEvidenceException()
		fun required(name: String) = System.getenv(name)?.takeIf(String::isNotBlank) ?: throw CertificationRestartEvidenceException()
		val mapper = jacksonObjectMapper()
		val before = mapper.readValue(Path.of(required("PLOT_CERTIFICATION_RESTART_BEFORE")).toFile(), CertificationRestartDurableState::class.java)
		val after = mapper.readValue(Path.of(required("PLOT_CERTIFICATION_RESTART_AFTER")).toFile(), CertificationRestartDurableState::class.java)
		val result = CertificationProcessRestartReconciler().reconcile(before, after)
		val target = Path.of(required("PLOT_CERTIFICATION_RESTART_RESULT"))
		if (!target.isAbsolute) throw CertificationRestartEvidenceException()
		Files.createDirectories(target.parent)
		Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { mapper.writeValue(it, result) }
	}

	private val PROVIDER_CREDENTIALS = setOf(
		"OPENROUTER_API_KEY", "OPENAI_API_KEY", "SPRING_AI_OPENAI_API_KEY", "GITHUB_TOKEN", "GH_TOKEN",
		"GITHUB_APP_PRIVATE_KEY", "GITHUB_INSTALLATION_TOKEN", "PLOT_GITHUB_PRIVATE_KEY", "PLOT_GITHUB_STATE_SECRET",
	)
}

class CertificationRestartEvidenceException : IllegalStateException("CERTIFICATION_RESTART_EVIDENCE_REJECTED")
