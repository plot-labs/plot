package com.plot.api.certification

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class CertificationDeterministicResult(
	val schemaVersion: String = "certification-deterministic-result-v1",
	val sourceRevision: String,
	val campaignId: String,
	val campaignManifestHash: String,
	val corpusHash: String,
	val profileHash: String,
	val outcome: EvidenceOutcome,
)

data class CertificationRestartSelectionResult(
	val schemaVersion: String = "certification-restart-selection-v1",
	val campaignId: String,
	val campaignManifestHash: String,
	val eligibleModelExecutionId: String?,
	val restartModelExecutionId: String,
	val restartRequestedModel: String,
)

class CertificationReportAssemblyException(code: String) : IllegalArgumentException(code)

/** Builds the report input exclusively from sealed manifests and immutable machine envelopes. */
class CertificationReportAssembler(private val mapper: ObjectMapper) {
	private val contract = CertificationArtifactContract(mapper)

	fun assemble(
		phase: ReportPhase,
		campaignNode: JsonNode,
		executionNodes: List<JsonNode>,
		evidenceNodes: List<JsonNode>,
		reconciliations: List<CertificationReconciliationResult>,
		deterministic: CertificationDeterministicResult,
		restart: CertificationProcessRestartResult,
		cleanup: CertificationCleanupResult?,
		operator: CertificationOperatorDecision,
	): CertificationReportInput {
		val core = assembleCore(campaignNode, executionNodes, evidenceNodes, reconciliations)
		val campaign = core.campaign
		val executions = core.executions
		val models = core.models
		if (deterministic.schemaVersion != "certification-deterministic-result-v1" ||
			deterministic.sourceRevision != campaign.artifact.sourceRevision ||
			deterministic.campaignId != campaign.artifact.campaignId || deterministic.campaignManifestHash != campaign.hash ||
			deterministic.corpusHash != campaign.artifact.corpusHash || deterministic.profileHash != campaign.artifact.profileHash ||
			deterministic.outcome != EvidenceOutcome.PASS ||
			restart.schemaVersion != "certification-process-restart-result-v1" ||
			restart.sourceSnapshotSetHash != campaign.artifact.sourceSnapshotSetHash || !validRestartResult(restart)
		) reject("CERTIFICATION_SYSTEM_EVIDENCE_REJECTED")
		val effectiveCleanup = when (phase) {
			ReportPhase.DRAFT -> {
				if (cleanup != null) reject("CERTIFICATION_DRAFT_CLEANUP_REJECTED")
				pendingCleanup(campaign, operator)
			}
			ReportPhase.FINAL -> cleanup?.takeIf {
				it.outcome == EvidenceOutcome.PASS && it.codes == listOf(CleanupCode.CLEANUP_COMPLETE)
			} ?: reject("CERTIFICATION_FINAL_CLEANUP_REJECTED")
		}
		val selected = selectCostFirst(models, reconciliations)
		val restartExecution = executions.singleOrNull { it.artifact.modelExecutionId == restart.modelExecutionId }
		if (restart.campaignId != campaign.artifact.campaignId || restart.campaignManifestHash != campaign.hash ||
			restartExecution?.hash != restart.modelExecutionManifestHash || (selected != null && restart.modelExecutionId != selected)
		) reject("CERTIFICATION_RESTART_IDENTITY_REJECTED")
		if (effectiveCleanup.campaignId != campaign.artifact.campaignId || effectiveCleanup.campaignManifestHash != campaign.hash ||
			effectiveCleanup.sourceRevision != campaign.artifact.sourceRevision ||
			effectiveCleanup.attestedByOperatorAlias != operator.operatorAlias ||
			(phase == ReportPhase.FINAL && Instant.parse(operator.decidedAt).isBefore(Instant.parse(effectiveCleanup.recordedAt)))
		) reject("CERTIFICATION_CLEANUP_IDENTITY_REJECTED")
		return CertificationReportInput(
			phase = phase,
			sourceRevision = campaign.artifact.sourceRevision,
			campaignId = campaign.artifact.campaignId,
			campaignManifestHash = campaign.hash,
			environmentAlias = "env-${campaign.artifact.environmentFingerprint.removePrefix("sha256:").take(16)}",
			sourceAliases = campaign.artifact.approvedSourceAliases,
			sourceSnapshotSetHash = campaign.artifact.sourceSnapshotSetHash,
			corpusHash = campaign.artifact.corpusHash,
			profileHash = campaign.artifact.profileHash,
			selectedModelExecutionId = selected,
			models = models,
			deterministicOutcome = deterministic.outcome,
			browserReconciliations = reconciliations.sortedWith(compareBy({ it.modelExecutionId }, { it.ordinal }, { it.attemptId })),
			processRestart = restart,
			cleanup = effectiveCleanup,
			operator = operator,
		)
	}

	fun selectRestartCandidate(
		campaignNode: JsonNode,
		executionNodes: List<JsonNode>,
		evidenceNodes: List<JsonNode>,
		reconciliations: List<CertificationReconciliationResult>,
	): CertificationRestartSelectionResult {
		val core = assembleCore(campaignNode, executionNodes, evidenceNodes, reconciliations)
		val eligible = selectCostFirst(core.models, reconciliations)
		val restart = core.executions.single {
			it.artifact.modelExecutionId == eligible ||
				(eligible == null && it.artifact.requestedModel == PREFERRED_MODELS.first())
		}
		return CertificationRestartSelectionResult(
			campaignId = core.campaign.artifact.campaignId,
			campaignManifestHash = core.campaign.hash,
			eligibleModelExecutionId = eligible,
			restartModelExecutionId = restart.artifact.modelExecutionId,
			restartRequestedModel = restart.artifact.requestedModel,
		)
	}

	private fun assembleCore(
		campaignNode: JsonNode,
		executionNodes: List<JsonNode>,
		evidenceNodes: List<JsonNode>,
		reconciliations: List<CertificationReconciliationResult>,
	): CertificationReportCore {
		val campaign = contract.sealCampaign(campaignNode)
		val executions = executionNodes.map { contract.sealModelExecution(it, campaign) }
		if (executions.map { it.artifact.requestedModel }.toSet() != REQUIRED_MODELS ||
			executions.map { it.artifact.modelExecutionId }.distinct().size != 2
		) reject("CERTIFICATION_EXECUTION_SET_REJECTED")
		val evidence = readEvidence(campaign, executions, evidenceNodes)
		if (evidence.any { it.evidenceType != EvidenceType.MODEL_ATTEMPT }) reject("CERTIFICATION_EVIDENCE_TYPE_REJECTED")
		val models = executions.map { execution ->
			modelReport(execution, evidence.filter { it.modelExecutionId == execution.artifact.modelExecutionId })
		}
		if (evidence.size != models.sumOf { model -> model.attempts.sumOf { it.scenarios.size } }) {
			reject("CERTIFICATION_EVIDENCE_COVERAGE_REJECTED")
		}
		if (reconciliations.isEmpty() || reconciliations.any { result ->
			val execution = executions.singleOrNull { it.artifact.modelExecutionId == result.modelExecutionId }
			result.schemaVersion != "certification-reconciliation-v1" ||
				result.campaignId != campaign.artifact.campaignId || result.campaignManifestHash != campaign.hash ||
				execution == null || result.modelExecutionManifestHash != execution.hash ||
				result.sourceSnapshotSetHash != campaign.artifact.sourceSnapshotSetHash || !validReconciliationResult(result)
		}) reject("CERTIFICATION_RECONCILIATION_SET_REJECTED")
		models.forEach { model ->
			val browser = reconciliations.filter { it.modelExecutionId == model.modelExecutionId }
			val valid = browser.filter { it.outcome != EvidenceOutcome.INCONCLUSIVE }
			if (valid.size != 3 || valid.map { it.ordinal }.sorted() != listOf(1, 2, 3) ||
				browser.any { it.scenarioId != "real-github-journey" } || browser.any { result ->
					val attempt = model.attempts.singleOrNull { it.attemptId == result.attemptId }
					attempt == null || attempt.ordinal != result.ordinal
				} || browser.any { result ->
					result.replacesAttemptId != null && browser.singleOrNull { prior ->
						prior.attemptId == result.replacesAttemptId && prior.ordinal == result.ordinal &&
							prior.outcome == EvidenceOutcome.INCONCLUSIVE
					} == null
				} || browser.filter { it.outcome == EvidenceOutcome.INCONCLUSIVE }.any { prior ->
					!terminatesInValidReconciliation(browser, prior)
				}
			) reject("CERTIFICATION_BROWSER_ATTEMPT_COVERAGE_REJECTED")
		}
		return CertificationReportCore(campaign, executions, models)
	}

	private data class CertificationReportCore(
		val campaign: SealedArtifact<CampaignManifest>,
		val executions: List<SealedArtifact<ModelExecutionManifest>>,
		val models: List<CertificationModelReport>,
	)

	private fun readEvidence(
		campaign: SealedArtifact<CampaignManifest>,
		executions: List<SealedArtifact<ModelExecutionManifest>>,
		nodes: List<JsonNode>,
	): List<EvidenceEnvelope> {
		if (nodes.isEmpty()) reject("CERTIFICATION_EVIDENCE_EMPTY")
		val pending = nodes.associateBy { text(it, "artifactId") }.toMutableMap()
		if (pending.size != nodes.size) reject("CERTIFICATION_EVIDENCE_DUPLICATE")
		val resolved = linkedMapOf<String, EvidenceEnvelope>()
		while (pending.isNotEmpty()) {
			var progressed = false
			pending.entries.toList().forEach { (artifactId, node) ->
				val executionId = text(node, "modelExecutionId")
				val execution = executions.singleOrNull { it.artifact.modelExecutionId == executionId }
					?: reject("CERTIFICATION_EVIDENCE_EXECUTION_REJECTED")
				val priorArtifactId = node.get("lineage")?.get("priorArtifactId")?.takeIf { it.isString }?.stringValue()
				val prior = priorArtifactId?.let { resolved[it] }
				if (priorArtifactId != null && prior == null) return@forEach
				resolved[artifactId] = contract.readEvidence(node, campaign, execution, prior)
				pending.remove(artifactId)
				progressed = true
			}
			if (!progressed) reject("CERTIFICATION_EVIDENCE_LINEAGE_REJECTED")
		}
		contract.validateBundle(campaign, executions, resolved.values.toList())
		return resolved.values.toList()
	}

	private fun modelReport(
		execution: SealedArtifact<ModelExecutionManifest>,
		evidence: List<EvidenceEnvelope>,
	): CertificationModelReport {
		val expectedScenarios = execution.artifact.scenarioIds.filterNot { it in SYSTEM_SCENARIOS }.toSet()
		if (expectedScenarios.isEmpty()) reject("CERTIFICATION_SCENARIO_SET_REJECTED")
		val attempts = evidence.groupBy { it.attemptId ?: reject("CERTIFICATION_ATTEMPT_ID_REJECTED") }.map { (attemptId, records) ->
			if (records.mapNotNull { it.scenarioId }.toSet() != expectedScenarios || records.size != expectedScenarios.size ||
				records.map { it.ordinal }.distinct().size != 1 || records.map { it.lineage?.priorAttemptId }.distinct().size != 1
			) reject("CERTIFICATION_ATTEMPT_COVERAGE_REJECTED")
			val scenarios = records.sortedBy { it.scenarioId }.map(::scenarioReport)
			CertificationAttemptReport(
				attemptId = attemptId,
				scenarioId = "contract-corpus",
				ordinal = records.first().ordinal ?: reject("CERTIFICATION_ATTEMPT_ORDINAL_REJECTED"),
				outcome = aggregate(scenarios.map { it.outcome }),
				replacesAttemptId = records.first().lineage?.priorAttemptId,
				hardGateCodes = scenarios.flatMap { it.hardGateCodes }.distinct().sortedBy(Enum<*>::name),
				infrastructureCodes = scenarios.flatMap { it.infrastructureCodes }.distinct().sortedBy(Enum<*>::name),
				metrics = aggregateMetrics(scenarios.map { it.metrics }),
				scenarios = scenarios,
			)
		}.sortedWith(compareBy({ it.ordinal }, { it.attemptId }))
		val valid = attempts.filter { it.outcome != EvidenceOutcome.INCONCLUSIVE }
		if (attempts.size > MAX_MODEL_ATTEMPTS || valid.map { it.ordinal }.toSet() != setOf(1, 2, 3) ||
			(1..3).any { ordinal -> valid.none { it.ordinal == ordinal } }
		) reject("CERTIFICATION_VALID_ATTEMPTS_REJECTED")
		attempts.filter { it.outcome == EvidenceOutcome.INCONCLUSIVE }.forEach { inconclusive ->
			if (!hasReplacement(attempts, inconclusive)) reject("CERTIFICATION_REPLACEMENT_LINEAGE_REJECTED")
		}
		return CertificationModelReport(
			modelExecutionId = execution.artifact.modelExecutionId,
			modelExecutionManifestHash = execution.hash,
			modelProfileHash = execution.artifact.modelProfileHash,
			routePolicyHash = execution.artifact.routePolicyHash,
			requestedModel = execution.artifact.requestedModel,
			servedModel = execution.artifact.servedModel,
			observedUpstream = execution.artifact.pinnedUpstream,
			attempts = attempts,
		)
	}

	private fun scenarioReport(envelope: EvidenceEnvelope): CertificationScenarioReport {
		val hard = mutableListOf<HardGateCode>()
		val infrastructure = mutableListOf<CertificationFailureCode>()
		envelope.codes.forEach { code ->
			runCatching { HardGateCode.valueOf(code) }.getOrNull()?.let(hard::add) ?: infrastructureCode(code)?.let(infrastructure::add)
				?: reject("CERTIFICATION_DEFECT_CODE_REJECTED")
		}
		return CertificationScenarioReport(
			scenarioId = envelope.scenarioId ?: reject("CERTIFICATION_SCENARIO_ID_REJECTED"),
			outcome = envelope.outcome,
			hardGateCodes = hard.distinct(),
			infrastructureCodes = infrastructure.distinct(),
			metrics = metrics(envelope.metrics),
		)
	}

	private fun metrics(values: Map<String, Any>) = CertificationAttemptMetrics(
		coldStart = values["coldStart"] as? Boolean ?: reject("CERTIFICATION_METRICS_REJECTED"),
		promptTokens = metric(values, "promptTokens"),
		completionTokens = metric(values, "completionTokens"),
		reasoningTokens = metric(values, "reasoningTokens"),
		cachedTokens = metric(values, "cachedTokens"),
		costUsdMicros = metric(values, "costUsdMicros"),
		latencyMs = metric(values, "latencyMs"),
		rewriteCount = metric(values, "rewriteCount"),
		modelCallCount = metric(values, "modelCallCount"),
		citationPrecisionBasisPoints = basisPoint(values, "citationPrecisionBasisPoints"),
		citationRecallBasisPoints = basisPoint(values, "citationRecallBasisPoints"),
		supportedClaimRecallBasisPoints = basisPoint(values, "supportedClaimRecallBasisPoints"),
		unsupportedClaimRecallBasisPoints = basisPoint(values, "unsupportedClaimRecallBasisPoints"),
		conflictRecallBasisPoints = basisPoint(values, "conflictRecallBasisPoints"),
		notRequiredFalsePositiveBasisPoints = basisPoint(values, "notRequiredFalsePositiveBasisPoints"),
	)

	private fun aggregateMetrics(metrics: List<CertificationAttemptMetrics>): CertificationAttemptMetrics {
		if (metrics.isEmpty()) reject("CERTIFICATION_METRICS_EMPTY")
		fun sum(value: (CertificationAttemptMetrics) -> Int) = metrics.fold(0) { total, item -> Math.addExact(total, value(item)) }
		fun average(value: (CertificationAttemptMetrics) -> Int) = sum(value) / metrics.size
		return CertificationAttemptMetrics(
			coldStart = metrics.any { it.coldStart },
			promptTokens = sum { it.promptTokens }, completionTokens = sum { it.completionTokens },
			reasoningTokens = sum { it.reasoningTokens }, cachedTokens = sum { it.cachedTokens },
			costUsdMicros = sum { it.costUsdMicros }, latencyMs = sum { it.latencyMs },
			rewriteCount = sum { it.rewriteCount }, modelCallCount = sum { it.modelCallCount },
			citationPrecisionBasisPoints = average { it.citationPrecisionBasisPoints },
			citationRecallBasisPoints = average { it.citationRecallBasisPoints },
			supportedClaimRecallBasisPoints = average { it.supportedClaimRecallBasisPoints },
			unsupportedClaimRecallBasisPoints = average { it.unsupportedClaimRecallBasisPoints },
			conflictRecallBasisPoints = average { it.conflictRecallBasisPoints },
			notRequiredFalsePositiveBasisPoints = average { it.notRequiredFalsePositiveBasisPoints },
		)
	}

	private fun selectCostFirst(models: List<CertificationModelReport>, reconciliations: List<CertificationReconciliationResult>): String? =
		PREFERRED_MODELS.firstNotNullOfOrNull { requested ->
			models.singleOrNull { it.requestedModel == requested }?.takeIf { model ->
				val browser = reconciliations.filter { it.modelExecutionId == model.modelExecutionId }
				model.attempts.filter { it.outcome != EvidenceOutcome.INCONCLUSIVE }.all { it.outcome == EvidenceOutcome.PASS } &&
				browserEligible(browser) && browser.filter { it.outcome != EvidenceOutcome.INCONCLUSIVE }.all { result ->
					model.attempts.singleOrNull { it.attemptId == result.attemptId }?.outcome == EvidenceOutcome.PASS
				}
			}?.modelExecutionId
		}

	private fun browserEligible(results: List<CertificationReconciliationResult>): Boolean {
		val valid = results.filter { it.outcome != EvidenceOutcome.INCONCLUSIVE }
		return valid.size == 3 && valid.map { it.ordinal }.sorted() == listOf(1, 2, 3) && valid.all { it.outcome == EvidenceOutcome.PASS } &&
			results.filter { it.outcome == EvidenceOutcome.INCONCLUSIVE }.all { terminatesInValidReconciliation(results, it) }
	}

	private fun hasReplacement(attempts: List<CertificationAttemptReport>, prior: CertificationAttemptReport): Boolean =
		terminatesInValidAttempt(attempts, prior)

	private fun terminatesInValidAttempt(
		attempts: List<CertificationAttemptReport>,
		prior: CertificationAttemptReport,
	): Boolean {
		val visited = mutableSetOf(prior.attemptId)
		var current = prior
		while (current.outcome == EvidenceOutcome.INCONCLUSIVE) {
			val replacement = attempts.singleOrNull {
				it.replacesAttemptId == current.attemptId && it.ordinal == prior.ordinal
			} ?: return false
			if (!visited.add(replacement.attemptId)) return false
			current = replacement
		}
		return true
	}

	private fun terminatesInValidReconciliation(
		results: List<CertificationReconciliationResult>,
		prior: CertificationReconciliationResult,
	): Boolean {
		val visited = mutableSetOf(prior.attemptId)
		var current = prior
		while (current.outcome == EvidenceOutcome.INCONCLUSIVE) {
			val replacement = results.singleOrNull {
				it.replacesAttemptId == current.attemptId && it.ordinal == prior.ordinal
			} ?: return false
			if (!visited.add(replacement.attemptId)) return false
			current = replacement
		}
		return true
	}

	private fun validReconciliationResult(result: CertificationReconciliationResult): Boolean {
		if (listOf(result.durableModelCallCount, result.durableCitationCount, result.durableInterventionCount,
			result.durableExportEventCount).any { it < 0 }
		) return false
		if ((result.replacesAttemptId == null) != (result.replacementModelResultHash == null) ||
			(result.replacementModelResultHash != null && !HASH.matches(result.replacementModelResultHash))
		) return false
		return when (result.outcome) {
			EvidenceOutcome.PASS -> result.codes == listOf(ReconciliationCode.RECONCILED)
			EvidenceOutcome.INCONCLUSIVE -> result.codes in setOf(
				listOf(ReconciliationCode.ROUTE_ATTRIBUTION_INCONCLUSIVE),
				listOf(ReconciliationCode.BROWSER_INFRASTRUCTURE_INCONCLUSIVE),
			)
			EvidenceOutcome.HARD_GATE_FAIL -> result.codes.isNotEmpty() && ReconciliationCode.RECONCILED !in result.codes
		}
	}

	private fun validRestartResult(result: CertificationProcessRestartResult): Boolean {
		if (listOf(result.modelCallCount, result.citationCount, result.interventionCount, result.contentPackCount,
			result.exportEventCount).any { it < 0 }
		) return false
		return when (result.outcome) {
			EvidenceOutcome.PASS -> result.code == ProcessRestartCode.PROCESS_RESTART_RECONCILED
			EvidenceOutcome.HARD_GATE_FAIL -> result.code == ProcessRestartCode.PROCESS_RESTART_FAILED
			EvidenceOutcome.INCONCLUSIVE -> result.code == ProcessRestartCode.PROCESS_RESTART_INCONCLUSIVE
		}
	}

	private fun pendingCleanup(
		campaign: SealedArtifact<CampaignManifest>,
		operator: CertificationOperatorDecision,
	) = CertificationCleanupResult(
		campaignId = campaign.artifact.campaignId,
		campaignManifestHash = campaign.hash,
		sourceRevision = campaign.artifact.sourceRevision,
		recordedAt = operator.decidedAt,
		attestedByOperatorAlias = operator.operatorAlias,
		attestedAt = operator.decidedAt,
		outcome = EvidenceOutcome.HARD_GATE_FAIL,
		codes = listOf(CleanupCode.LISTENER_STILL_RUNNING, CleanupCode.CREDENTIAL_NOT_REVOKED, CleanupCode.RAW_ARTIFACT_REMAINS,
			CleanupCode.BROWSER_ARTIFACT_REMAINS, CleanupCode.DATABASE_UNDISPOSED),
		listenerCount = 1, githubCredentialRevoked = false, openRouterCredentialRevoked = false,
		stateSecretDisposed = false, rawArtifactsDeleted = false, browserArtifactsDeleted = false,
		databaseDisposition = DatabaseDisposition.UNRESOLVED, retainedOwnerAlias = null, retainedExpiresAt = null,
	)

	private fun aggregate(outcomes: List<EvidenceOutcome>) = when {
		outcomes.any { it == EvidenceOutcome.HARD_GATE_FAIL } -> EvidenceOutcome.HARD_GATE_FAIL
		outcomes.any { it == EvidenceOutcome.INCONCLUSIVE } -> EvidenceOutcome.INCONCLUSIVE
		outcomes.isNotEmpty() -> EvidenceOutcome.PASS
		else -> reject("CERTIFICATION_OUTCOME_EMPTY")
	}

	private fun metric(values: Map<String, Any>, name: String): Int = (values[name] as? Number)?.toInt()?.takeIf { it >= 0 }
		?: reject("CERTIFICATION_METRICS_REJECTED")
	private fun basisPoint(values: Map<String, Any>, name: String): Int = metric(values, name).takeIf { it <= 10_000 }
		?: reject("CERTIFICATION_METRICS_REJECTED")
	private fun infrastructureCode(code: String): CertificationFailureCode? {
		val normalized = code.removePrefix("EXTERNAL_").removePrefix("MODEL_")
		return runCatching { CertificationFailureCode.valueOf(normalized) }.getOrNull()
	}
	private fun text(node: JsonNode, field: String): String = node.get(field)?.takeIf { it.isString }?.stringValue()
		?: reject("CERTIFICATION_EVIDENCE_FIELD_REJECTED")
	private fun reject(code: String): Nothing = throw CertificationReportAssemblyException(code)

	companion object {
		private const val MAX_MODEL_ATTEMPTS = 48
		private val REQUIRED_MODELS = setOf("openai/gpt-5.4-nano", "openai/gpt-4o-mini-2024-07-18")
		private val PREFERRED_MODELS = listOf("openai/gpt-5.4-nano", "openai/gpt-4o-mini-2024-07-18")
		private val SYSTEM_SCENARIOS = setOf("real-github-journey", "process-restart")
		private val HASH = Regex("^sha256:[a-f0-9]{64}$")
	}
}

fun readCertificationJsonFiles(mapper: ObjectMapper, directoryValue: String): List<JsonNode> {
	val directory = Path.of(directoryValue)
	if (!directory.isAbsolute || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
		throw CertificationReportAssemblyException("CERTIFICATION_EVIDENCE_DIRECTORY_REJECTED")
	}
	val files = Files.list(directory).use { stream -> stream.sorted().toList() }
	if (files.isEmpty() || files.size > 512 || files.any {
		!Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(it) || Files.size(it) !in 1..1024L * 1024L
	}) throw CertificationReportAssemblyException("CERTIFICATION_EVIDENCE_FILES_REJECTED")
	return files.map { file -> Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use(mapper::readTree) }
}
