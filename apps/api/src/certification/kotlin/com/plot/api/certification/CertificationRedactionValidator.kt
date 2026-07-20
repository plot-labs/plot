package com.plot.api.certification

import java.time.Instant
import tools.jackson.databind.JsonNode

class CertificationRedactionException(code: String = "CERTIFICATION_REDACTION_REJECTED") : IllegalArgumentException(code)

/** Enforces the committed-report allow-list. It intentionally has no generic notes or metadata map. */
class CertificationRedactionValidator {
	fun validate(input: CertificationReportInput) {
		if (input.schemaVersion != "generation-certification-report-v1" ||
			!REVISION.matches(input.sourceRevision) || !CAMPAIGN_ID.matches(input.campaignId) ||
			!HASH.matches(input.campaignManifestHash) || !ALIAS.matches(input.environmentAlias) ||
			input.sourceAliases.isEmpty() || input.sourceAliases.any { !SOURCE_ALIAS.matches(it) } ||
			input.sourceAliases.distinct().size != input.sourceAliases.size || !HASH.matches(input.sourceSnapshotSetHash) || !HASH.matches(input.corpusHash) ||
			!HASH.matches(input.profileHash) || !OPERATOR_ALIAS.matches(input.operator.operatorAlias) ||
			runCatching { Instant.parse(input.operator.decidedAt) }.isFailure ||
			input.operator.rubric.factualUsefulness !in 1..5 || input.operator.rubric.changelogClarity !in 1..5 ||
			input.operator.rubric.citationPlacement !in 1..5
		) reject()
		if (input.models.map { it.requestedModel }.toSet() != REQUIRED_MODELS ||
			input.models.any { model ->
				!MODEL_EXECUTION_ID.matches(model.modelExecutionId) || !HASH.matches(model.modelExecutionManifestHash) ||
				!HASH.matches(model.modelProfileHash) || !HASH.matches(model.routePolicyHash) ||
				!MODEL_ID.matches(model.servedModel) || !UPSTREAM.matches(model.observedUpstream) ||
				model.attempts.isEmpty() || model.attempts.map { it.attemptId }.distinct().size != model.attempts.size ||
				model.attempts.any { attempt -> invalidAttempt(attempt) || attempt.scenarios.isEmpty() ||
					attempt.scenarios.map { it.scenarioId }.distinct().size != attempt.scenarios.size ||
					attempt.scenarios.any(::invalidScenario)
				}
			}
		) reject()
		if (input.selectedModelExecutionId != null && !MODEL_EXECUTION_ID.matches(input.selectedModelExecutionId)) reject()
		val modelsById = input.models.associateBy { it.modelExecutionId }
		if (input.sourceSnapshotSetHash != input.processRestart.sourceSnapshotSetHash ||
			input.processRestart.campaignId != input.campaignId || input.processRestart.campaignManifestHash != input.campaignManifestHash ||
			input.processRestart.modelExecutionId !in input.models.map { it.modelExecutionId } ||
			input.models.singleOrNull { it.modelExecutionId == input.processRestart.modelExecutionId }?.modelExecutionManifestHash != input.processRestart.modelExecutionManifestHash ||
			!ATTEMPT_ID.matches(input.processRestart.attemptId) ||
			input.browserReconciliations.map { it.modelExecutionId to it.attemptId }.distinct().size != input.browserReconciliations.size ||
			input.browserReconciliations.any {
			val attempt = modelsById[it.modelExecutionId]?.attempts?.singleOrNull { candidate -> candidate.attemptId == it.attemptId }
			!CAMPAIGN_ID.matches(it.campaignId) || !HASH.matches(it.campaignManifestHash) ||
				!MODEL_EXECUTION_ID.matches(it.modelExecutionId) || !HASH.matches(it.modelExecutionManifestHash) || !HASH.matches(it.sourceSnapshotSetHash) ||
				it.campaignId != input.campaignId || it.modelExecutionId !in input.models.map { model -> model.modelExecutionId } ||
				it.campaignManifestHash != input.campaignManifestHash || it.sourceSnapshotSetHash != input.sourceSnapshotSetHash ||
				modelsById[it.modelExecutionId]?.modelExecutionManifestHash != it.modelExecutionManifestHash ||
				!ATTEMPT_ID.matches(it.attemptId) || !SCENARIO_ID.matches(it.scenarioId) || it.ordinal !in 1..3 ||
				(it.replacesAttemptId != null && !ATTEMPT_ID.matches(it.replacesAttemptId)) || attempt == null ||
				(it.replacesAttemptId == null) != (it.replacementModelResultHash == null) ||
				(it.replacementModelResultHash != null && !HASH.matches(it.replacementModelResultHash)) ||
				it.scenarioId != "real-github-journey" || attempt.ordinal != it.ordinal
			}) reject()
		input.browserReconciliations.groupBy { it.modelExecutionId }.values.forEach { results ->
			if (results.any { result ->
				result.replacesAttemptId != null && results.singleOrNull { prior ->
					prior.attemptId == result.replacesAttemptId && prior.ordinal == result.ordinal &&
						prior.outcome == EvidenceOutcome.INCONCLUSIVE
				} == null
			} || results.filter { it.outcome == EvidenceOutcome.INCONCLUSIVE }.any { prior ->
				!terminatesInValidReconciliation(results, prior)
			}) reject()
		}
		val restartCounts = input.processRestart.let {
			listOf(it.modelCallCount, it.citationCount, it.interventionCount, it.contentPackCount, it.exportEventCount)
		}
		if (restartCounts.any { it < 0 } || !HASH.matches(input.processRestart.sourceSnapshotSetHash)) reject()
		if (input.cleanup.campaignId != input.campaignId || input.cleanup.campaignManifestHash != input.campaignManifestHash ||
			input.cleanup.sourceRevision != input.sourceRevision || input.cleanup.attestedByOperatorAlias != input.operator.operatorAlias ||
			runCatching { Instant.parse(input.cleanup.recordedAt) }.isFailure ||
			runCatching { Instant.parse(input.cleanup.attestedAt) }.isFailure
		) reject()
	}

	/** Rejects unknown/free-form fields before a caller maps an external report input into typed data. */
	fun validateSerializedReportInput(node: JsonNode) {
		exact(node, ROOT_FIELDS, "report")
		array(node, "sourceAliases").forEach { scalar(it, "sourceAliases") }
		array(node, "models").forEach { model ->
			exact(model, MODEL_FIELDS, "model")
			array(model, "attempts").forEach { attempt ->
				exact(attempt, ATTEMPT_FIELDS, "attempt")
				exact(attempt.required("metrics"), METRIC_FIELDS, "attempt metrics")
				array(attempt, "hardGateCodes").forEach { scalar(it, "hardGateCodes") }
				array(attempt, "infrastructureCodes").forEach { scalar(it, "infrastructureCodes") }
				array(attempt, "scenarios").forEach { scenario ->
					exact(scenario, SCENARIO_FIELDS, "scenario")
					exact(scenario.required("metrics"), METRIC_FIELDS, "scenario metrics")
					array(scenario, "hardGateCodes").forEach { scalar(it, "scenario hardGateCodes") }
					array(scenario, "infrastructureCodes").forEach { scalar(it, "scenario infrastructureCodes") }
				}
			}
		}
		array(node, "browserReconciliations").forEach { reconciliation ->
			exact(reconciliation, RECONCILIATION_FIELDS, "browser reconciliation")
			array(reconciliation, "codes").forEach { scalar(it, "reconciliation codes") }
		}
		exact(node.required("processRestart"), RESTART_FIELDS, "process restart")
		exact(node.required("cleanup"), CLEANUP_FIELDS, "cleanup")
		array(node.required("cleanup"), "codes").forEach { scalar(it, "cleanup codes") }
		exact(node.required("operator"), OPERATOR_FIELDS, "operator")
		exact(node.required("operator").required("rubric"), RUBRIC_FIELDS, "operator rubric")
	}

	fun validateMarkdown(markdown: String, allowedHashes: Set<String> = emptySet()) {
		if (markdown.length !in 1..MAX_REPORT_BYTES || FORBIDDEN_TEXT.any { it.containsMatchIn(markdown) }) reject()
		val hashes = HASH.findAll(markdown).map { it.value }.toSet()
		if (!allowedHashes.containsAll(hashes)) reject()
	}

	private fun invalidAttempt(attempt: CertificationAttemptReport): Boolean {
		return !ATTEMPT_ID.matches(attempt.attemptId) || !SCENARIO_ID.matches(attempt.scenarioId) ||
			attempt.ordinal !in 1..3 || (attempt.replacesAttemptId != null && !ATTEMPT_ID.matches(attempt.replacesAttemptId)) ||
			invalidMetrics(attempt.metrics)
	}

	private fun invalidScenario(scenario: CertificationScenarioReport): Boolean =
		!SCENARIO_ID.matches(scenario.scenarioId) || invalidMetrics(scenario.metrics)

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

	private fun invalidMetrics(metrics: CertificationAttemptMetrics): Boolean {
		val counts = listOf(
			metrics.promptTokens, metrics.completionTokens, metrics.reasoningTokens, metrics.cachedTokens,
			metrics.costUsdMicros, metrics.latencyMs, metrics.rewriteCount, metrics.modelCallCount,
		)
		val basisPoints = listOf(
			metrics.citationPrecisionBasisPoints, metrics.citationRecallBasisPoints,
			metrics.supportedClaimRecallBasisPoints, metrics.unsupportedClaimRecallBasisPoints,
			metrics.conflictRecallBasisPoints, metrics.notRequiredFalsePositiveBasisPoints,
		)
		return counts.any { it < 0 } || basisPoints.any { it !in 0..10_000 }
	}

	private fun exact(node: JsonNode, fields: Set<String>, name: String) {
		if (!node.isObject || node.propertyNames().toSet() != fields) throw CertificationRedactionException("${name.uppercase().replace(' ', '_')}_FIELDS_REJECTED")
	}

	private fun array(node: JsonNode, field: String): List<JsonNode> {
		val value = node.get(field) ?: reject()
		if (!value.isArray) reject()
		return value.asArray().values().toList()
	}

	private fun scalar(node: JsonNode, name: String) {
		if (!node.isString) throw CertificationRedactionException("${name.uppercase().replace(' ', '_')}_REJECTED")
	}

	private fun reject(): Nothing = throw CertificationRedactionException()

	companion object {
		private const val MAX_REPORT_BYTES = 512 * 1024
		private val REVISION = Regex("(?<![a-f0-9])[a-f0-9]{40}(?![a-f0-9])")
		private val HASH = Regex("sha256:[a-f0-9]{64}")
		private val CAMPAIGN_ID = Regex("^campaign-[a-f0-9]{16,64}$")
		private val MODEL_EXECUTION_ID = Regex("^model-execution-[a-f0-9]{16,64}$")
		private val ATTEMPT_ID = Regex("^attempt-[a-f0-9]{16,64}$")
		private val SCENARIO_ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
		private val ALIAS = Regex("^env-[a-f0-9]{16,64}$")
		private val SOURCE_ALIAS = Regex("^source-[a-f0-9]{16,64}$")
		private val OPERATOR_ALIAS = Regex("^operator-[a-f0-9]{16,64}$")
		private val MODEL_ID = Regex("^[a-z0-9][a-z0-9./:_-]{2,127}$")
		private val UPSTREAM = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
		private val REQUIRED_MODELS = setOf("openai/gpt-5.4-nano", "openai/gpt-4o-mini-2024-07-18")
		private val FORBIDDEN_TEXT = listOf(
			Regex("(?i)https?://|(?:javascript|data):"),
			Regex("(?i)-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----"),
			Regex("(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{20,}|sk-[A-Za-z0-9_-]{20,}|AKIA[A-Z0-9]{16})"),
			Regex("(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9.-])"),
			Regex("(?i)\\b(?:snapshot|source|prompt|completion)[ _-]?(?:excerpt|body|text|content)\\b"),
			Regex("(?i)[\"'](?:title|name|url|rawRequestId|providerRequestId)[\"']\\s*:"),
			Regex("(?i)\\b(?:req_|chatcmpl-|raw[-_ ]?request[-_ ]?id)"),
			Regex("(?i)\\b(?:trace\\.zip|playwright-report|test-results|screenshot|video\\.(?:webm|mp4)|storageState)\\b"),
			Regex("(?<![a-f0-9])[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}(?![a-f0-9])"),
			Regex("(?m)^\\s*(?:operator notes?|source notes?|free[- ]form)\\s*:", RegexOption.IGNORE_CASE),
		)
		private val ROOT_FIELDS = setOf(
			"schemaVersion", "phase", "sourceRevision", "campaignId", "campaignManifestHash", "environmentAlias",
			"sourceAliases", "sourceSnapshotSetHash", "corpusHash", "profileHash", "selectedModelExecutionId", "models", "deterministicOutcome",
			"browserReconciliations", "processRestart", "cleanup", "operator",
		)
		private val MODEL_FIELDS = setOf("modelExecutionId", "modelExecutionManifestHash", "modelProfileHash", "routePolicyHash", "requestedModel", "servedModel", "observedUpstream", "attempts")
		private val ATTEMPT_FIELDS = setOf("attemptId", "scenarioId", "ordinal", "outcome", "replacesAttemptId", "hardGateCodes", "infrastructureCodes", "metrics", "scenarios")
		private val SCENARIO_FIELDS = setOf("scenarioId", "outcome", "hardGateCodes", "infrastructureCodes", "metrics")
		private val METRIC_FIELDS = setOf(
			"coldStart", "promptTokens", "completionTokens", "reasoningTokens", "cachedTokens", "costUsdMicros", "latencyMs", "rewriteCount",
			"modelCallCount", "citationPrecisionBasisPoints", "citationRecallBasisPoints", "supportedClaimRecallBasisPoints",
			"unsupportedClaimRecallBasisPoints", "conflictRecallBasisPoints", "notRequiredFalsePositiveBasisPoints",
		)
		private val RECONCILIATION_FIELDS = setOf(
			"schemaVersion", "campaignId", "campaignManifestHash", "modelExecutionId", "modelExecutionManifestHash", "sourceSnapshotSetHash",
			"attemptId", "scenarioId", "ordinal", "replacesAttemptId", "outcome", "codes",
			"durableModelCallCount", "durableCitationCount", "durableInterventionCount", "durableExportEventCount",
			"replacementModelResultHash",
		)
		private val RESTART_FIELDS = setOf(
			"schemaVersion", "campaignId", "campaignManifestHash", "modelExecutionId", "modelExecutionManifestHash", "attemptId",
			"outcome", "code", "checkpointArtifact", "sourceSnapshotSetHash", "modelCallCount", "citationCount",
			"interventionCount", "contentPackCount", "exportEventCount",
		)
		private val CLEANUP_FIELDS = setOf(
			"schemaVersion", "campaignId", "campaignManifestHash", "sourceRevision", "recordedAt",
			"attestedByOperatorAlias", "attestedAt", "outcome", "codes", "listenerCount",
			"githubCredentialRevoked", "openRouterCredentialRevoked",
			"stateSecretDisposed", "rawArtifactsDeleted", "browserArtifactsDeleted", "databaseDisposition",
			"retainedOwnerAlias", "retainedExpiresAt",
		)
		private val OPERATOR_FIELDS = setOf("operatorAlias", "decidedAt", "requestedDecision", "rubric")
		private val RUBRIC_FIELDS = setOf("factualUsefulness", "changelogClarity", "citationPlacement", "hedging")
	}
}

private fun JsonNode.required(field: String): JsonNode = get(field) ?: throw CertificationRedactionException()
