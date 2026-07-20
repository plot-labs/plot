package com.plot.api.certification

enum class ReportPhase { DRAFT, FINAL }
enum class OperatorDecision { GO, NO_GO }
enum class HedgingRating { TOO_LITTLE, APPROPRIATE, TOO_MUCH }
enum class ProcessRestartCode { PROCESS_RESTART_RECONCILED, PROCESS_RESTART_FAILED, PROCESS_RESTART_INCONCLUSIVE }
enum class RestartCheckpointArtifact { WRITER_OUTPUT, REVIEWER_OUTPUT, REWRITER_OUTPUT }

data class CertificationAttemptMetrics(
	val coldStart: Boolean,
	val promptTokens: Int,
	val completionTokens: Int,
	val reasoningTokens: Int,
	val cachedTokens: Int,
	val costUsdMicros: Int,
	val latencyMs: Int,
	val rewriteCount: Int,
	val modelCallCount: Int,
	val citationPrecisionBasisPoints: Int,
	val citationRecallBasisPoints: Int,
	val supportedClaimRecallBasisPoints: Int,
	val unsupportedClaimRecallBasisPoints: Int,
	val conflictRecallBasisPoints: Int,
	val notRequiredFalsePositiveBasisPoints: Int,
)

data class CertificationAttemptReport(
	val attemptId: String,
	val scenarioId: String,
	val ordinal: Int,
	val outcome: EvidenceOutcome,
	val replacesAttemptId: String?,
	val hardGateCodes: List<HardGateCode>,
	val infrastructureCodes: List<CertificationFailureCode>,
	val metrics: CertificationAttemptMetrics,
	val scenarios: List<CertificationScenarioReport>,
)

data class CertificationScenarioReport(
	val scenarioId: String,
	val outcome: EvidenceOutcome,
	val hardGateCodes: List<HardGateCode>,
	val infrastructureCodes: List<CertificationFailureCode>,
	val metrics: CertificationAttemptMetrics,
)

data class CertificationModelReport(
	val modelExecutionId: String,
	val modelExecutionManifestHash: String,
	val modelProfileHash: String,
	val routePolicyHash: String,
	val requestedModel: String,
	val servedModel: String,
	val observedUpstream: String,
	val attempts: List<CertificationAttemptReport>,
)

data class CertificationProcessRestartResult(
	val campaignId: String,
	val campaignManifestHash: String,
	val modelExecutionId: String,
	val modelExecutionManifestHash: String,
	val attemptId: String,
	val outcome: EvidenceOutcome,
	val code: ProcessRestartCode,
	val checkpointArtifact: RestartCheckpointArtifact,
	val sourceSnapshotSetHash: String,
	val modelCallCount: Int,
	val citationCount: Int,
	val interventionCount: Int,
	val contentPackCount: Int,
	val exportEventCount: Int,
	val schemaVersion: String = "certification-process-restart-result-v1",
)

data class CertificationOperatorRubric(
	val factualUsefulness: Int,
	val changelogClarity: Int,
	val citationPlacement: Int,
	val hedging: HedgingRating,
)

data class CertificationOperatorDecision(
	val operatorAlias: String,
	val decidedAt: String,
	val requestedDecision: OperatorDecision,
	val rubric: CertificationOperatorRubric,
)

data class CertificationReportInput(
	val schemaVersion: String = "generation-certification-report-v1",
	val phase: ReportPhase,
	val sourceRevision: String,
	val campaignId: String,
	val campaignManifestHash: String,
	val environmentAlias: String,
	val sourceAliases: List<String>,
	val sourceSnapshotSetHash: String,
	val corpusHash: String,
	val profileHash: String,
	val selectedModelExecutionId: String?,
	val models: List<CertificationModelReport>,
	val deterministicOutcome: EvidenceOutcome,
	val browserReconciliations: List<CertificationReconciliationResult>,
	val processRestart: CertificationProcessRestartResult,
	val cleanup: CertificationCleanupResult,
	val operator: CertificationOperatorDecision,
)

data class RenderedCertificationReport(
	val markdown: String,
	val computedEligible: Boolean,
	val finalDecision: OperatorDecision,
)

class CertificationReportRenderer(
	private val redactionValidator: CertificationRedactionValidator = CertificationRedactionValidator(),
) {
	fun render(input: CertificationReportInput): RenderedCertificationReport {
		redactionValidator.validate(input)
		val eligibleBeforeCleanup = eligibleBeforeCleanup(input)
		val computedEligible = eligibleBeforeCleanup && input.cleanup.outcome == EvidenceOutcome.PASS
		if (input.phase == ReportPhase.FINAL && input.operator.requestedDecision == OperatorDecision.GO && !computedEligible) {
			throw CertificationReportException("CERTIFICATION_GO_INELIGIBLE")
		}
		val finalDecision = if (input.phase == ReportPhase.FINAL && computedEligible &&
			input.operator.requestedDecision == OperatorDecision.GO
		) OperatorDecision.GO else OperatorDecision.NO_GO
		val markdown = buildString {
			appendLine("# Production generation certification")
			appendLine()
			appendLine("- Schema: `${input.schemaVersion}`")
			appendLine("- Phase: `${input.phase}`")
			appendLine("- Certified source revision: `${input.sourceRevision}`")
			appendLine("- Campaign: `${input.campaignId}`")
			appendLine("- Campaign manifest: `${input.campaignManifestHash}`")
			appendLine("- Environment: `${input.environmentAlias}`")
			appendLine("- Approved sources: ${input.sourceAliases.joinToString { "`$it`" }}")
			appendLine("- Corpus hash: `${input.corpusHash}`")
			appendLine("- Combined profile hash: `${input.profileHash}`")
			appendLine("- Selected model execution: `${input.selectedModelExecutionId ?: "none"}`")
			appendLine()
			appendLine("## Gate summary")
			appendLine()
			appendLine("| Gate | Outcome |")
			appendLine("|---|---|")
			appendLine("| Deterministic workflow | ${input.deterministicOutcome} |")
			val selectedBrowser = input.browserReconciliations.filter { it.modelExecutionId == input.selectedModelExecutionId }
			appendLine("| Selected-model browser and persisted audit | ${aggregate(selectedBrowser.map { it.outcome })} |")
			appendLine("| All-candidate browser evidence | ${aggregate(input.browserReconciliations.map { it.outcome })} |")
			appendLine("| Process-level restart | ${input.processRestart.outcome} |")
			appendLine("| Cleanup and disposition | ${input.cleanup.outcome} |")
			appendLine("| Eligible before cleanup | ${if (eligibleBeforeCleanup) "YES" else "NO"} |")
			appendLine("| Eligible for final GO | ${if (computedEligible) "YES" else "NO"} |")
			input.models.forEach { model -> appendModel(model) }
			appendLine()
			appendLine("## Browser and persisted-audit reconciliation")
			appendLine()
			appendLine("| Model execution | Attempt | Scenario | Ordinal | Outcome | Codes |")
			appendLine("|---|---|---|---:|---|---|")
			input.browserReconciliations.forEach { result ->
			appendLine("| `${result.modelExecutionId}` | `${result.attemptId}` | `${result.scenarioId}` | " +
					"${result.ordinal} | ${result.outcome} | ${result.codes.joinToString()} |")
			}
			appendLine()
			appendLine("## Process restart")
			appendLine()
			appendLine("- Campaign/model execution: `${input.processRestart.campaignId}` / `${input.processRestart.modelExecutionId}`")
			appendLine("- Restart attempt: `${input.processRestart.attemptId}`")
			appendLine("- Outcome/code: `${input.processRestart.outcome}` / `${input.processRestart.code}`")
			appendLine("- Durable checkpoint: `${input.processRestart.checkpointArtifact}`")
			appendLine("- Durable counts (model/citation/intervention/content-pack/export): " +
				"`${input.processRestart.modelCallCount}/${input.processRestart.citationCount}/" +
				"${input.processRestart.interventionCount}/${input.processRestart.contentPackCount}/" +
				"${input.processRestart.exportEventCount}`")
			appendLine()
			appendLine("## Cleanup")
			appendLine()
			appendLine("- Outcome: `${input.cleanup.outcome}`")
			appendLine("- Recorded/attested: `${input.cleanup.recordedAt}` / `${input.cleanup.attestedAt}` by `${input.cleanup.attestedByOperatorAlias}`")
			appendLine("- Codes: ${input.cleanup.codes.joinToString { "`$it`" }}")
			appendLine("- Listener count: `${input.cleanup.listenerCount}`")
			appendLine("- GitHub/OpenRouter credentials revoked: `${input.cleanup.githubCredentialRevoked}/${input.cleanup.openRouterCredentialRevoked}`")
			appendLine("- State secret disposed: `${input.cleanup.stateSecretDisposed}`")
			appendLine("- Raw/browser artifacts deleted: `${input.cleanup.rawArtifactsDeleted}/${input.cleanup.browserArtifactsDeleted}`")
			appendLine("- Database disposition: `${input.cleanup.databaseDisposition}`")
			appendLine("- Retention owner/expiry: `${input.cleanup.retainedOwnerAlias ?: "none"}` / `${input.cleanup.retainedExpiresAt ?: "none"}`")
			appendLine()
			appendLine("## Operator decision")
			appendLine()
			appendLine("- Operator: `${input.operator.operatorAlias}`")
			appendLine("- Timestamp: `${input.operator.decidedAt}`")
			appendLine("- Requested decision: `${input.operator.requestedDecision}`")
			appendLine("- Rubric (factual usefulness / clarity / citation placement): " +
				"`${input.operator.rubric.factualUsefulness}/${input.operator.rubric.changelogClarity}/" +
				"${input.operator.rubric.citationPlacement}`")
			appendLine("- Hedging: `${input.operator.rubric.hedging}`")
			appendLine("- Final decision: `$finalDecision`")
		}
		redactionValidator.validateMarkdown(markdown, buildSet {
			add(input.campaignManifestHash)
			add(input.corpusHash)
			add(input.profileHash)
			input.models.forEach {
				add(it.modelExecutionManifestHash)
				add(it.modelProfileHash)
				add(it.routePolicyHash)
			}
		})
		return RenderedCertificationReport(markdown, computedEligible, finalDecision)
	}

	private fun StringBuilder.appendModel(model: CertificationModelReport) {
		appendLine()
		appendLine("## Model `${model.modelExecutionId}`")
		appendLine()
		appendLine("- Requested model: `${model.requestedModel}`")
		appendLine("- Served model: `${model.servedModel}`")
		appendLine("- Observed upstream: `${model.observedUpstream}`")
		appendLine("- Model execution manifest: `${model.modelExecutionManifestHash}`")
		appendLine("- Model profile hash: `${model.modelProfileHash}`")
		appendLine("- Route policy hash: `${model.routePolicyHash}`")
		appendLine()
		appendLine("| Attempt | Scenario | Ordinal | Outcome | Replaces | Start | Prompt | Completion | Reasoning | Cached | Cost µUSD | Latency ms | Rewrites | Calls | Citation P/R bp | Supported/unsupported bp | Conflict bp | Not-required FP bp | Codes |")
		appendLine("|---|---|---:|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---:|---:|---|")
		model.attempts.forEach { attempt ->
			val codes = (attempt.hardGateCodes.map(Enum<*>::name) + attempt.infrastructureCodes.map(Enum<*>::name))
				.joinToString().ifEmpty { "NONE" }
			appendLine("| `${attempt.attemptId}` | `${attempt.scenarioId}` | ${attempt.ordinal} | ${attempt.outcome} | " +
				"`${attempt.replacesAttemptId ?: "none"}` | ${if (attempt.metrics.coldStart) "COLD" else "WARM"} | ${attempt.metrics.promptTokens} | " +
				"${attempt.metrics.completionTokens} | ${attempt.metrics.reasoningTokens} | ${attempt.metrics.cachedTokens} | " +
				"${attempt.metrics.costUsdMicros} | ${attempt.metrics.latencyMs} | ${attempt.metrics.rewriteCount} | " +
				"${attempt.metrics.modelCallCount} | ${attempt.metrics.citationPrecisionBasisPoints}/${attempt.metrics.citationRecallBasisPoints} | " +
				"${attempt.metrics.supportedClaimRecallBasisPoints}/${attempt.metrics.unsupportedClaimRecallBasisPoints} | " +
				"${attempt.metrics.conflictRecallBasisPoints} | ${attempt.metrics.notRequiredFalsePositiveBasisPoints} | $codes |")
		}
		appendLine()
		appendLine("### Per-scenario evidence")
		appendLine()
		appendLine("| Attempt | Scenario | Outcome | Citation P/R bp | Supported/unsupported bp | Conflict bp | Not-required FP bp | Codes |")
		appendLine("|---|---|---|---|---|---:|---:|---|")
		model.attempts.forEach { attempt ->
			attempt.scenarios.forEach { scenario ->
				val codes = (scenario.hardGateCodes.map(Enum<*>::name) + scenario.infrastructureCodes.map(Enum<*>::name))
					.joinToString().ifEmpty { "NONE" }
				appendLine("| `${attempt.attemptId}` | `${scenario.scenarioId}` | ${scenario.outcome} | " +
					"${scenario.metrics.citationPrecisionBasisPoints}/${scenario.metrics.citationRecallBasisPoints} | " +
					"${scenario.metrics.supportedClaimRecallBasisPoints}/${scenario.metrics.unsupportedClaimRecallBasisPoints} | " +
					"${scenario.metrics.conflictRecallBasisPoints} | ${scenario.metrics.notRequiredFalsePositiveBasisPoints} | $codes |")
			}
		}
	}

	private fun eligibleBeforeCleanup(input: CertificationReportInput): Boolean {
		if (input.models.size != 2 || input.models.map { it.modelExecutionId }.distinct().size != 2) return false
		if (input.deterministicOutcome != EvidenceOutcome.PASS || input.processRestart.outcome != EvidenceOutcome.PASS) return false
		if (input.browserReconciliations.isEmpty() || input.sourceSnapshotSetHash != input.processRestart.sourceSnapshotSetHash) return false
		if (!input.models.all { model ->
			val valid = model.attempts.filter { it.outcome != EvidenceOutcome.INCONCLUSIVE }
			valid.map { it.ordinal }.toSet() == setOf(1, 2, 3) &&
				model.attempts.filter { it.outcome == EvidenceOutcome.INCONCLUSIVE }.all { inconclusive ->
					terminatesInValidAttempt(model.attempts, inconclusive)
				}
		}) return false
		val preferred = PREFERRED_MODELS.firstNotNullOfOrNull { requestedModel ->
			input.models.singleOrNull { it.requestedModel == requestedModel }?.takeIf { model ->
				val browserResults = input.browserReconciliations.filter { it.modelExecutionId == model.modelExecutionId }
				model.attempts.filter { it.outcome != EvidenceOutcome.INCONCLUSIVE }.all { it.outcome == EvidenceOutcome.PASS } &&
					browserEligible(browserResults) && browserResults.filter { it.outcome != EvidenceOutcome.INCONCLUSIVE }.all { result ->
						model.attempts.singleOrNull { it.attemptId == result.attemptId }?.outcome == EvidenceOutcome.PASS
					}
			}
		}
		return preferred != null && input.selectedModelExecutionId != null &&
			preferred.modelExecutionId == input.selectedModelExecutionId
	}

	private fun browserEligible(results: List<CertificationReconciliationResult>): Boolean {
		val valid = results.filter { it.outcome != EvidenceOutcome.INCONCLUSIVE }
		return valid.size == 3 && valid.map { it.ordinal }.sorted() == listOf(1, 2, 3) &&
			valid.all { it.outcome == EvidenceOutcome.PASS } &&
			results.filter { it.outcome == EvidenceOutcome.INCONCLUSIVE }.all { inconclusive ->
				val visited = mutableSetOf(inconclusive.attemptId)
				var current = inconclusive
				while (true) {
					val replacement = results.singleOrNull {
						it.replacesAttemptId == current.attemptId && it.ordinal == inconclusive.ordinal
					} ?: return@all false
					if (!visited.add(replacement.attemptId)) return@all false
					if (replacement.outcome != EvidenceOutcome.INCONCLUSIVE) break
					current = replacement
				}
				true
			}
	}

	private fun terminatesInValidAttempt(
		attempts: List<CertificationAttemptReport>,
		inconclusive: CertificationAttemptReport,
	): Boolean {
		val visited = mutableSetOf(inconclusive.attemptId)
		var current = inconclusive
		while (true) {
			val replacement = attempts.singleOrNull {
				it.replacesAttemptId == current.attemptId && it.ordinal == inconclusive.ordinal
			} ?: return false
			if (!visited.add(replacement.attemptId)) return false
			if (replacement.outcome != EvidenceOutcome.INCONCLUSIVE) return true
			current = replacement
		}
	}

	private fun aggregate(outcomes: List<EvidenceOutcome>): EvidenceOutcome = when {
		outcomes.isEmpty() -> EvidenceOutcome.INCONCLUSIVE
		outcomes.any { it == EvidenceOutcome.HARD_GATE_FAIL } -> EvidenceOutcome.HARD_GATE_FAIL
		outcomes.any { it == EvidenceOutcome.INCONCLUSIVE } -> EvidenceOutcome.INCONCLUSIVE
		else -> EvidenceOutcome.PASS
	}

	companion object {
		private val PREFERRED_MODELS = listOf("openai/gpt-5.4-nano", "openai/gpt-4o-mini-2024-07-18")
	}
}

class CertificationReportException(code: String) : IllegalArgumentException(code)
