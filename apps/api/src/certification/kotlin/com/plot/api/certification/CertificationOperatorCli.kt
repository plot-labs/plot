package com.plot.api.certification

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

object CertificationReconcileCli {
	@JvmStatic
	fun main(args: Array<String>) {
		requireNoArgumentsOrSecrets(args)
		val mapper = certificationMapper()
		val contract = CertificationArtifactContract(mapper)
		val campaign = contract.sealCampaign(readJson(mapper, required("PLOT_CERTIFICATION_CAMPAIGN_MANIFEST")))
		val execution = contract.sealModelExecution(
			readJson(mapper, required("PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST")), campaign,
		)
		val browser = contract.readEvidence(
			readJson(mapper, required("PLOT_CERTIFICATION_BROWSER_OBSERVATION")), campaign, execution,
		)
		val replaces = System.getenv("PLOT_CERTIFICATION_REPLACES_ATTEMPT_ID")?.takeIf(String::isNotBlank)
		val replacementResultPath = System.getenv("PLOT_CERTIFICATION_MODEL_REPLACEMENT_RESULT")?.takeIf(String::isNotBlank)
		val replacementResultHash = when {
			replaces == null && replacementResultPath == null -> null
			replaces == null || replacementResultPath == null -> throw CertificationOperatorCliException("CERTIFICATION_REPLACEMENT_PROOF_REJECTED")
			else -> verifyReplacementProof(mapper, replacementResultPath, replaces, browser, execution)
		}
		val reconciler = CertificationEvidenceReconciler()
		val result = if (System.getenv("PLOT_CERTIFICATION_BROWSER_TERMINAL_ONLY") == "true") {
			reconciler.reconcileBrowserTerminal(campaign, execution, browser, replaces, replacementResultHash)
		} else {
			val audit = mapper.treeToValue(
				readJson(mapper, required("PLOT_CERTIFICATION_AUDIT_ENVELOPE")), CertificationAuditEnvelope::class.java,
			)
			reconciler.reconcile(campaign, execution, browser, audit, replaces, replacementResultHash)
		}
		writeNew(required("PLOT_CERTIFICATION_RECONCILIATION_OUTPUT"), mapper.writeValueAsBytes(result))
	}

	private fun verifyReplacementProof(
		mapper: ObjectMapper,
		path: String,
		replaces: String,
		browser: EvidenceEnvelope,
		execution: SealedArtifact<ModelExecutionManifest>,
	): String {
		val file = absoluteRegularFile(path)
		val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readAllBytes() }
		val node = mapper.readTree(bytes)
		if (!node.isObject || node.propertyNames().toSet() != setOf(
				"schemaVersion", "selectedAttemptId", "triggeredByBrowserAttemptId", "modelExecutionId", "ordinal",
			) || node.required("schemaVersion").stringValue() != "certification-model-replacement-v1" ||
			node.required("selectedAttemptId").stringValue() != browser.attemptId ||
			node.required("triggeredByBrowserAttemptId").stringValue() != replaces ||
			node.required("modelExecutionId").stringValue() != execution.artifact.modelExecutionId ||
			node.required("ordinal").intValue() != browser.ordinal
		) throw CertificationOperatorCliException("CERTIFICATION_REPLACEMENT_PROOF_REJECTED")
		return "sha256:${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))}"
	}
}

object CertificationReportCli {
	@JvmStatic
	fun main(args: Array<String>) {
		requireNoArgumentsOrSecrets(args)
		val mapper = certificationMapper()
		val phase = runCatching { ReportPhase.valueOf(required("PLOT_CERTIFICATION_REPORT_PHASE")) }
			.getOrElse { throw CertificationOperatorCliException("CERTIFICATION_REPORT_PHASE_REJECTED") }
		val input = readReportInput(mapper, phase)
		val validator = CertificationRedactionValidator()
		val report = CertificationReportRenderer(validator).render(input)
		writeNew(required("PLOT_CERTIFICATION_REPORT_OUTPUT"), report.markdown.toByteArray(Charsets.UTF_8))
	}
}

object CertificationRestartSelectionCli {
	@JvmStatic
	fun main(args: Array<String>) {
		requireNoArgumentsOrSecrets(args)
		val mapper = certificationMapper()
		val reconciliations = readCertificationJsonFiles(
			mapper, required("PLOT_CERTIFICATION_RECONCILIATION_DIRECTORY"),
		).map { mapper.treeToValue(it, CertificationReconciliationResult::class.java) }
		val result = CertificationReportAssembler(mapper).selectRestartCandidate(
			readJson(mapper, required("PLOT_CERTIFICATION_CAMPAIGN_MANIFEST")),
			listOf(
				readJson(mapper, required("PLOT_CERTIFICATION_NANO_MANIFEST")),
				readJson(mapper, required("PLOT_CERTIFICATION_MINI_MANIFEST")),
			),
			readCertificationJsonFiles(mapper, required("PLOT_CERTIFICATION_MODEL_EVIDENCE_DIRECTORY")),
			reconciliations,
		)
		writeNew(required("PLOT_CERTIFICATION_RESTART_SELECTION_OUTPUT"), mapper.writeValueAsBytes(result))
	}
}

data class CertificationReportSnapshot(
	val schemaVersion: String = "certification-report-snapshot-v1",
	val input: CertificationReportInput,
)

object CertificationReportSnapshotCli {
	@JvmStatic
	fun main(args: Array<String>) {
		requireNoArgumentsOrSecrets(args)
		val mapper = certificationMapper()
		val input = readReportInput(mapper, ReportPhase.DRAFT)
		CertificationRedactionValidator().validate(input)
		writeNew(
			required("PLOT_CERTIFICATION_REPORT_SNAPSHOT"),
			mapper.writeValueAsBytes(CertificationReportSnapshot(input = input)),
		)
	}
}

object CertificationFinalReportCli {
	@JvmStatic
	fun main(args: Array<String>) {
		requireNoArgumentsOrSecrets(args)
		val mapper = certificationMapper()
		val snapshotPath = absoluteRegularFile(required("PLOT_CERTIFICATION_REPORT_SNAPSHOT"))
		val bytes = Files.newInputStream(snapshotPath, LinkOption.NOFOLLOW_LINKS).use { it.readAllBytes() }
		val expectedHash = required("PLOT_CERTIFICATION_REPORT_SNAPSHOT_HASH")
		val actualHash = "sha256:${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))}"
		if (!Regex("^sha256:[a-f0-9]{64}$").matches(expectedHash) ||
			!MessageDigest.isEqual(expectedHash.toByteArray(), actualHash.toByteArray())
		) throw CertificationOperatorCliException("CERTIFICATION_REPORT_SNAPSHOT_HASH_REJECTED")
		val snapshotNode = mapper.readTree(bytes)
		if (!snapshotNode.isObject || snapshotNode.propertyNames().toSet() != setOf("schemaVersion", "input") ||
			snapshotNode.required("schemaVersion").stringValue() != "certification-report-snapshot-v1"
		) throw CertificationOperatorCliException("CERTIFICATION_REPORT_SNAPSHOT_REJECTED")
		val snapshot = mapper.treeToValue(snapshotNode.required("input"), CertificationReportInput::class.java)
		val cleanup = mapper.treeToValue(
			readJson(mapper, required("PLOT_CERTIFICATION_CLEANUP_OUTPUT")), CertificationCleanupResult::class.java,
		)
		val operator = readOperatorDecision(mapper)
		val input = CertificationReportSnapshotFinalizer.finalize(snapshot, cleanup, operator)
		val validator = CertificationRedactionValidator()
		validator.validate(input)
		val report = CertificationReportRenderer(validator).render(input)
		writeNew(required("PLOT_CERTIFICATION_REPORT_OUTPUT"), report.markdown.toByteArray(Charsets.UTF_8))
	}
}

object CertificationReportSnapshotFinalizer {
	fun finalize(
		snapshot: CertificationReportInput,
		cleanup: CertificationCleanupResult,
		operator: CertificationOperatorDecision,
	): CertificationReportInput {
		if (snapshot.phase != ReportPhase.DRAFT || cleanup.schemaVersion != "certification-cleanup-v1" ||
			cleanup.outcome != EvidenceOutcome.PASS || cleanup.codes != listOf(CleanupCode.CLEANUP_COMPLETE) ||
			cleanup.campaignId != snapshot.campaignId || cleanup.campaignManifestHash != snapshot.campaignManifestHash ||
			cleanup.sourceRevision != snapshot.sourceRevision || cleanup.attestedByOperatorAlias != operator.operatorAlias ||
			Instant.parse(operator.decidedAt).isBefore(Instant.parse(cleanup.recordedAt))
		) throw CertificationOperatorCliException("CERTIFICATION_FINAL_SNAPSHOT_REJECTED")
		return snapshot.copy(phase = ReportPhase.FINAL, cleanup = cleanup, operator = operator)
	}
}

private fun readReportInput(mapper: ObjectMapper, phase: ReportPhase): CertificationReportInput {
	val campaignNode = readJson(mapper, required("PLOT_CERTIFICATION_CAMPAIGN_MANIFEST"))
	val executionNodes = listOf(
		readJson(mapper, required("PLOT_CERTIFICATION_NANO_MANIFEST")),
		readJson(mapper, required("PLOT_CERTIFICATION_MINI_MANIFEST")),
	)
	val evidenceNodes = readCertificationJsonFiles(mapper, required("PLOT_CERTIFICATION_MODEL_EVIDENCE_DIRECTORY"))
	val reconciliations = readCertificationJsonFiles(mapper, required("PLOT_CERTIFICATION_RECONCILIATION_DIRECTORY")).map {
		mapper.treeToValue(it, CertificationReconciliationResult::class.java)
	}
	val deterministic = mapper.treeToValue(
		readJson(mapper, required("PLOT_CERTIFICATION_DETERMINISTIC_RESULT")), CertificationDeterministicResult::class.java,
	)
	val restart = mapper.treeToValue(
		readJson(mapper, required("PLOT_CERTIFICATION_RESTART_RESULT")), CertificationProcessRestartResult::class.java,
	)
	val cleanup = if (phase == ReportPhase.FINAL) mapper.treeToValue(
		readJson(mapper, required("PLOT_CERTIFICATION_CLEANUP_OUTPUT")), CertificationCleanupResult::class.java,
	) else null
	return CertificationReportAssembler(mapper).assemble(
		phase, campaignNode, executionNodes, evidenceNodes, reconciliations, deterministic, restart, cleanup,
		readOperatorDecision(mapper),
	)
}

private fun readOperatorDecision(mapper: ObjectMapper): CertificationOperatorDecision {
	val operatorNode = readJson(mapper, required("PLOT_CERTIFICATION_OPERATOR_DECISION"))
	if (!operatorNode.isObject || operatorNode.propertyNames().toSet() != OPERATOR_DECISION_FIELDS ||
		!operatorNode.required("rubric").isObject || operatorNode.required("rubric").propertyNames().toSet() != OPERATOR_RUBRIC_FIELDS
	) throw CertificationOperatorCliException("CERTIFICATION_OPERATOR_DECISION_FIELDS_REJECTED")
	return mapper.treeToValue(operatorNode, CertificationOperatorDecision::class.java)
}

object CertificationCleanupCli {
	@JvmStatic
	fun main(args: Array<String>) {
		requireNoArgumentsOrSecrets(args)
		val mapper = certificationMapper()
		fun boolean(name: String) = when (required(name)) {
			"true" -> true
			"false" -> false
			else -> throw CertificationOperatorCliException("CERTIFICATION_CLEANUP_BOOLEAN_REJECTED")
		}
		val disposition = runCatching { DatabaseDisposition.valueOf(required("PLOT_CERTIFICATION_DATABASE_DISPOSITION")) }
			.getOrElse { throw CertificationOperatorCliException("CERTIFICATION_DATABASE_DISPOSITION_REJECTED") }
		val observation = CertificationCleanupObservation(
			campaignId = required("PLOT_CERTIFICATION_CAMPAIGN_ID"),
			campaignManifestHash = required("PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH"),
			sourceRevision = required("PLOT_GENERATION_SOURCE_REVISION"),
			recordedAt = required("PLOT_CERTIFICATION_CLEANUP_RECORDED_AT"),
			attestedByOperatorAlias = required("PLOT_CERTIFICATION_OPERATOR_ALIAS"),
			attestedAt = required("PLOT_CERTIFICATION_CLEANUP_ATTESTED_AT"),
			listenerCount = required("PLOT_CERTIFICATION_LISTENER_COUNT").toIntOrNull()
				?: throw CertificationOperatorCliException("CERTIFICATION_LISTENER_COUNT_REJECTED"),
			githubCredentialRevoked = boolean("PLOT_CERTIFICATION_GITHUB_CREDENTIAL_REVOKED"),
			openRouterCredentialRevoked = boolean("PLOT_CERTIFICATION_OPENROUTER_CREDENTIAL_REVOKED"),
			stateSecretDisposed = boolean("PLOT_CERTIFICATION_STATE_SECRET_DISPOSED"),
			rawArtifactsDeleted = boolean("PLOT_CERTIFICATION_RAW_ARTIFACTS_DELETED"),
			browserArtifactsDeleted = boolean("PLOT_CERTIFICATION_BROWSER_ARTIFACTS_DELETED"),
			databaseDisposition = disposition,
			retainedOwnerAlias = System.getenv("PLOT_CERTIFICATION_RETAINED_OWNER_ALIAS")?.takeIf(String::isNotBlank),
			retainedExpiresAt = System.getenv("PLOT_CERTIFICATION_RETAINED_EXPIRES_AT")?.takeIf(String::isNotBlank),
		)
		val result = CertificationCleanupGate().evaluate(observation)
		writeNew(required("PLOT_CERTIFICATION_CLEANUP_OUTPUT"), mapper.writeValueAsBytes(result))
	}
}

data class CertificationSealedBundle(
	val schemaVersion: String = "certification-sealed-bundle-v1",
	val campaignId: String,
	val campaignManifestHash: String,
	val sourceSnapshotSetHash: String,
	val nanoModelExecutionId: String,
	val nanoModelExecutionManifestHash: String,
	val miniModelExecutionId: String,
	val miniModelExecutionManifestHash: String,
)

data class CertificationSealedCampaign(
	val schemaVersion: String = "certification-sealed-campaign-v1",
	val campaignId: String,
	val campaignManifestHash: String,
	val sourceSnapshotSetHash: String,
)

object CertificationCampaignSealVerificationCli {
	@JvmStatic
	fun main(args: Array<String>) {
		requireNoArgumentsOrSecrets(args)
		val mapper = certificationMapper()
		val imported = mapper.treeToValue(
			readJson(mapper, required("PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT")),
			ImportedSourcePreflightResult::class.java,
		)
		if (!imported.eligible || imported.codes.isNotEmpty()) {
			throw CertificationOperatorCliException("CERTIFICATION_SOURCE_PREFLIGHT_REJECTED")
		}
		val campaign = CertificationArtifactContract(mapper)
			.sealCampaign(readJson(mapper, required("PLOT_CERTIFICATION_CAMPAIGN_MANIFEST")))
		if (campaign.artifact.campaignId != required("PLOT_CERTIFICATION_CAMPAIGN_ID") ||
			campaign.artifact.sourceSnapshotSetHash != imported.sourceSnapshotSetHash ||
			campaign.artifact.approvedSourceAliases != listOf(imported.sourceAlias) ||
			campaign.artifact.corpusHash != required("PLOT_CERTIFICATION_CORPUS_HASH") ||
			campaign.artifact.profileHash != required("PLOT_CERTIFICATION_PROFILE_HASH") ||
			campaign.artifact.environmentFingerprint != required("PLOT_CERTIFICATION_ENVIRONMENT_FINGERPRINT")
		) throw CertificationOperatorCliException("CERTIFICATION_SOURCE_SNAPSHOT_SET_MISMATCH")
		writeNew(
			required("PLOT_CERTIFICATION_SEALED_CAMPAIGN_OUTPUT"),
			mapper.writeValueAsBytes(CertificationSealedCampaign(
				campaignId = campaign.artifact.campaignId,
				campaignManifestHash = campaign.hash,
				sourceSnapshotSetHash = campaign.artifact.sourceSnapshotSetHash,
			)),
		)
	}
}

object CertificationSealVerificationCli {
	@JvmStatic
	fun main(args: Array<String>) {
		requireNoArgumentsOrSecrets(args)
		val mapper = certificationMapper()
		val contract = CertificationArtifactContract(mapper)
		val imported = mapper.treeToValue(
			readJson(mapper, required("PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT")),
			ImportedSourcePreflightResult::class.java,
		)
		if (!imported.eligible || imported.codes.isNotEmpty()) throw CertificationOperatorCliException("CERTIFICATION_SOURCE_PREFLIGHT_REJECTED")
		val campaign = contract.sealCampaign(readJson(mapper, required("PLOT_CERTIFICATION_CAMPAIGN_MANIFEST")))
		if (campaign.artifact.campaignId != required("PLOT_CERTIFICATION_CAMPAIGN_ID") ||
			campaign.artifact.sourceSnapshotSetHash != imported.sourceSnapshotSetHash ||
			campaign.artifact.approvedSourceAliases != listOf(imported.sourceAlias)
		) {
			throw CertificationOperatorCliException("CERTIFICATION_SOURCE_SNAPSHOT_SET_MISMATCH")
		}
		val nano = contract.sealModelExecution(readJson(mapper, required("PLOT_CERTIFICATION_NANO_MANIFEST")), campaign)
		val mini = contract.sealModelExecution(readJson(mapper, required("PLOT_CERTIFICATION_MINI_MANIFEST")), campaign)
		if (nano.artifact.requestedModel != "openai/gpt-5.4-nano" ||
			mini.artifact.requestedModel != "openai/gpt-4o-mini-2024-07-18"
		) throw CertificationOperatorCliException("CERTIFICATION_MODEL_PROFILE_REJECTED")
		writeNew(
			required("PLOT_CERTIFICATION_SEALED_BUNDLE_OUTPUT"),
			mapper.writeValueAsBytes(CertificationSealedBundle(
				campaignId = campaign.artifact.campaignId,
				campaignManifestHash = campaign.hash,
				sourceSnapshotSetHash = campaign.artifact.sourceSnapshotSetHash,
				nanoModelExecutionId = nano.artifact.modelExecutionId,
				nanoModelExecutionManifestHash = nano.hash,
				miniModelExecutionId = mini.artifact.modelExecutionId,
				miniModelExecutionManifestHash = mini.hash,
			)),
		)
	}
}

private fun certificationMapper(): ObjectMapper = jacksonObjectMapper()

private fun readJson(mapper: ObjectMapper, value: String): JsonNode {
	val file = absoluteRegularFile(value)
	if (Files.size(file) !in 1..MAX_INPUT_BYTES) throw CertificationOperatorCliException("CERTIFICATION_INPUT_SIZE_REJECTED")
	return try {
		Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use(mapper::readTree)
	} catch (_: Exception) {
		throw CertificationOperatorCliException("CERTIFICATION_INPUT_REJECTED")
	}
}

private fun absoluteRegularFile(value: String): Path {
	val path = Path.of(value)
	if (!path.isAbsolute || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
		throw CertificationOperatorCliException("CERTIFICATION_INPUT_PATH_REJECTED")
	}
	return path.normalize()
}

private fun writeNew(value: String, bytes: ByteArray) {
	val target = Path.of(value)
	if (!target.isAbsolute || bytes.isEmpty() || bytes.size > MAX_OUTPUT_BYTES) {
		throw CertificationOperatorCliException("CERTIFICATION_OUTPUT_PATH_REJECTED")
	}
	val normalized = target.normalize()
	val parent = normalized.parent ?: throw CertificationOperatorCliException("CERTIFICATION_OUTPUT_PATH_REJECTED")
	Files.createDirectories(parent)
	if (Files.isSymbolicLink(parent)) throw CertificationOperatorCliException("CERTIFICATION_OUTPUT_PATH_REJECTED")
	try {
		Files.newOutputStream(normalized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(bytes) }
	} catch (_: Exception) {
		throw CertificationOperatorCliException("CERTIFICATION_OUTPUT_WRITE_REJECTED")
	}
}

private fun required(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
	?: throw CertificationOperatorCliException("CERTIFICATION_ENV_REJECTED")

private fun requireNoArgumentsOrSecrets(args: Array<String>) {
	if (args.isNotEmpty()) throw CertificationOperatorCliException("CERTIFICATION_CLI_ARGUMENT_REJECTED")
	if (SECRET_KEYS.any(System.getenv()::containsKey)) throw CertificationOperatorCliException("CERTIFICATION_CREDENTIAL_SCOPE_REJECTED")
}

class CertificationOperatorCliException(code: String) : IllegalStateException(code)

private const val MAX_INPUT_BYTES = 1024L * 1024L
private const val MAX_OUTPUT_BYTES = 1024 * 1024
private val SECRET_KEYS = setOf(
	"OPENROUTER_API_KEY", "OPENAI_API_KEY", "GITHUB_TOKEN", "GH_TOKEN", "GITHUB_APP_PRIVATE_KEY",
	"GITHUB_INSTALLATION_TOKEN", "GITHUB_WEBHOOK_SECRET", "PLOT_GITHUB_PRIVATE_KEY", "PLOT_GITHUB_STATE_SECRET",
	"SPRING_AI_OPENAI_API_KEY", "SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL",
	"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
	"PLOT_CERTIFICATION_DATABASE_URL", "PLOT_CERTIFICATION_DATABASE_USERNAME", "PLOT_CERTIFICATION_DATABASE_PASSWORD",
	"PLOT_CERTIFICATION_DATABASE_FINGERPRINT", "PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN",
)
private val OPERATOR_DECISION_FIELDS = setOf("operatorAlias", "decidedAt", "requestedDecision", "rubric")
private val OPERATOR_RUBRIC_FIELDS = setOf("factualUsefulness", "changelogClarity", "citationPlacement", "hedging")
