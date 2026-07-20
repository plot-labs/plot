package com.plot.api.certification

enum class ReconciliationCode {
	RECONCILED,
	IDENTITY_MISMATCH,
	SOURCE_SNAPSHOT_SET_MISMATCH,
	BROWSER_CONTRACT_FAILED,
	BROWSER_INFRASTRUCTURE_INCONCLUSIVE,
	BROWSER_OBSERVATION_NOT_PENDING_AUDIT,
	RUN_NOT_DURABLY_COMPLETE,
	INVOCATION_SEQUENCE_INVALID,
	HUMAN_DECISION_MISMATCH,
	SENTENCE_STATE_MISMATCH,
	CITATION_STATE_MISMATCH,
	CITATION_COUNT_MISMATCH,
	EXPORT_SEQUENCE_MISMATCH,
	ARTIFACT_SEQUENCE_INVALID,
	ROUTE_ATTRIBUTION_MISMATCH,
	ROUTE_ATTRIBUTION_INCONCLUSIVE,
}

data class CertificationReconciliationResult(
	val schemaVersion: String = "certification-reconciliation-v1",
	val campaignId: String,
	val campaignManifestHash: String,
	val modelExecutionId: String,
	val modelExecutionManifestHash: String,
	val sourceSnapshotSetHash: String,
	val attemptId: String,
	val scenarioId: String,
	val ordinal: Int,
	val replacesAttemptId: String?,
	val outcome: EvidenceOutcome,
	val codes: List<ReconciliationCode>,
	val durableModelCallCount: Int,
	val durableCitationCount: Int,
	val durableInterventionCount: Int,
	val durableExportEventCount: Int,
	val replacementModelResultHash: String? = null,
)

/** Joins browser and database observations without accepting console text or source-derived fields. */
class CertificationEvidenceReconciler {
	fun reconcileBrowserTerminal(
		campaign: SealedArtifact<CampaignManifest>,
		execution: SealedArtifact<ModelExecutionManifest>,
		browser: EvidenceEnvelope,
		attemptReplacesAttemptId: String? = null,
		replacementModelResultHash: String? = null,
	): CertificationReconciliationResult {
		val identityValid = sameBrowserIdentity(campaign, execution, browser) &&
			(attemptReplacesAttemptId == null ||
				(ATTEMPT_ID.matches(attemptReplacesAttemptId) && attemptReplacesAttemptId != browser.attemptId))
		val infrastructure = browser.outcome == EvidenceOutcome.INCONCLUSIVE &&
			browser.codes == listOf("BROWSER_INFRASTRUCTURE_INCONCLUSIVE")
		val contractFailure = browser.outcome == EvidenceOutcome.HARD_GATE_FAIL &&
			browser.codes == listOf("BROWSER_CONTRACT_FAILED")
		val outcome = when {
			!identityValid || (!infrastructure && !contractFailure) -> EvidenceOutcome.HARD_GATE_FAIL
			infrastructure -> EvidenceOutcome.INCONCLUSIVE
			else -> EvidenceOutcome.HARD_GATE_FAIL
		}
		val code = when {
			!identityValid || (!infrastructure && !contractFailure) -> ReconciliationCode.IDENTITY_MISMATCH
			infrastructure -> ReconciliationCode.BROWSER_INFRASTRUCTURE_INCONCLUSIVE
			else -> ReconciliationCode.BROWSER_CONTRACT_FAILED
		}
		return CertificationReconciliationResult(
			campaignId = campaign.artifact.campaignId,
			campaignManifestHash = campaign.hash,
			modelExecutionId = execution.artifact.modelExecutionId,
			modelExecutionManifestHash = execution.hash,
			sourceSnapshotSetHash = campaign.artifact.sourceSnapshotSetHash,
			attemptId = browser.attemptId.orEmpty(),
			scenarioId = browser.scenarioId.orEmpty(),
			ordinal = browser.ordinal ?: 0,
			replacesAttemptId = attemptReplacesAttemptId,
			outcome = outcome,
			codes = listOf(code),
			durableModelCallCount = 0,
			durableCitationCount = 0,
			durableInterventionCount = 0,
			durableExportEventCount = 0,
			replacementModelResultHash = replacementModelResultHash,
		)
	}

	fun reconcile(
		campaign: SealedArtifact<CampaignManifest>,
		execution: SealedArtifact<ModelExecutionManifest>,
		browser: EvidenceEnvelope,
		audit: CertificationAuditEnvelope,
		attemptReplacesAttemptId: String? = null,
		replacementModelResultHash: String? = null,
	): CertificationReconciliationResult {
		val codes = linkedSetOf<ReconciliationCode>()
		if (attemptReplacesAttemptId != null &&
			(!ATTEMPT_ID.matches(attemptReplacesAttemptId) || attemptReplacesAttemptId == browser.attemptId)
		) codes += ReconciliationCode.IDENTITY_MISMATCH
		if (!sameIdentity(campaign, execution, browser, audit)) codes += ReconciliationCode.IDENTITY_MISMATCH
		if (audit.sourceSnapshotSetHash != campaign.artifact.sourceSnapshotSetHash) {
			codes += ReconciliationCode.SOURCE_SNAPSHOT_SET_MISMATCH
		}
		if (codes.isNotEmpty()) {
			return result(campaign, execution, browser, audit, attemptReplacesAttemptId, replacementModelResultHash, EvidenceOutcome.HARD_GATE_FAIL, codes)
		}
		if (browser.outcome == EvidenceOutcome.HARD_GATE_FAIL) {
			codes += ReconciliationCode.BROWSER_CONTRACT_FAILED
			return result(campaign, execution, browser, audit, attemptReplacesAttemptId, replacementModelResultHash, EvidenceOutcome.HARD_GATE_FAIL, codes)
		}
		if (browser.outcome == EvidenceOutcome.INCONCLUSIVE &&
			browser.codes == listOf("BROWSER_INFRASTRUCTURE_INCONCLUSIVE")
		) {
			codes += ReconciliationCode.BROWSER_INFRASTRUCTURE_INCONCLUSIVE
			return result(campaign, execution, browser, audit, attemptReplacesAttemptId, replacementModelResultHash, EvidenceOutcome.INCONCLUSIVE, codes)
		}
		if (browser.outcome != EvidenceOutcome.INCONCLUSIVE || browser.codes.toSet() != REQUIRED_BROWSER_CODES) {
			codes += ReconciliationCode.BROWSER_OBSERVATION_NOT_PENDING_AUDIT
		}

		if (audit.runStatus !in setOf("READY", "NEEDS_REVIEW")) codes += ReconciliationCode.RUN_NOT_DURABLY_COMPLETE
		if (audit.invocations.map { it.logicalCallIndex } != audit.invocations.indices.toList() ||
			audit.invocations.any { it.status != "SUCCEEDED" } || audit.invocations.firstOrNull()?.role != "WRITER" ||
			audit.invocations.none { it.role == "REVIEWER" }
		) codes += ReconciliationCode.INVOCATION_SEQUENCE_INVALID
		if (!validArtifactSequence(audit)) codes += ReconciliationCode.ARTIFACT_SEQUENCE_INVALID
		val attributions = audit.invocationAttributions
		if (attributions.size != audit.invocations.size || attributions.map { it.logicalCallIndex } != audit.invocations.map { it.logicalCallIndex } ||
			attributions.zip(audit.invocations).any { (route, invocation) -> route.role != invocation.role } ||
			attributions.any { it.outcome == EvidenceOutcome.HARD_GATE_FAIL }
		) {
			codes += ReconciliationCode.ROUTE_ATTRIBUTION_MISMATCH
		} else if (attributions.any { it.outcome == EvidenceOutcome.INCONCLUSIVE }) {
			codes += ReconciliationCode.ROUTE_ATTRIBUTION_INCONCLUSIVE
		} else if (attributions.any {
			it.outcome != EvidenceOutcome.PASS || it.code != null || it.gateway != "openrouter" ||
				it.requestedModel != execution.artifact.requestedModel || it.servedModel != execution.artifact.servedModel ||
				it.observedUpstream != execution.artifact.pinnedUpstream || it.responseIdHash == null || it.upstreamIdHash == null
		}) {
			codes += ReconciliationCode.ROUTE_ATTRIBUTION_MISMATCH
		}
		if ("HUMAN_DECISION_OBSERVED" in browser.codes &&
			(audit.interventionCount == 0 || audit.interventionCount != audit.resolvedInterventionCount)
		) codes += ReconciliationCode.HUMAN_DECISION_MISMATCH
		if ((audit.currentRevisionOriginCounts["USER_MODIFIED"] ?: 0) < 1 ||
			(audit.sentenceVerdictCounts["NOT_REQUIRED"] ?: 0) < 1
		) codes += ReconciliationCode.SENTENCE_STATE_MISMATCH
		if ((audit.citationStatusCounts["STALE"] ?: 0) < 1 ||
			(audit.citationStatusCounts["ACTIVE"] ?: 0) < 1
		) codes += ReconciliationCode.CITATION_STATE_MISMATCH

		val browserCitationCount = browser.metric("citationCount")
		if (browserCitationCount == null || browserCitationCount != audit.citationCount) {
			codes += ReconciliationCode.CITATION_COUNT_MISMATCH
		}
		val browserExportRequestCount = browser.metric("exportEventCount")
		if (browserExportRequestCount == null || browserExportRequestCount != 2 || audit.exports.size != 2 ||
			audit.exports[0].status != "REJECTED" || audit.exports[1].status != "SUCCEEDED"
		) codes += ReconciliationCode.EXPORT_SEQUENCE_MISMATCH

		val outcome = when {
			codes.isEmpty() -> EvidenceOutcome.PASS
			codes == setOf(ReconciliationCode.ROUTE_ATTRIBUTION_INCONCLUSIVE) -> EvidenceOutcome.INCONCLUSIVE
			else -> EvidenceOutcome.HARD_GATE_FAIL
		}
		if (codes.isEmpty()) codes += ReconciliationCode.RECONCILED
		return result(campaign, execution, browser, audit, attemptReplacesAttemptId, replacementModelResultHash, outcome, codes)
	}

	private fun validArtifactSequence(audit: CertificationAuditEnvelope): Boolean {
		val artifacts = audit.artifactTypes
		val allowedArtifacts = setOf(
			"EVIDENCE_SET",
			"WRITER_OUTPUT",
			"REVIEWER_OUTPUT",
			"REWRITER_OUTPUT",
			"CONFLICT_DECISION",
			"FINAL_OUTPUT",
		)
		if (artifacts.firstOrNull() != "EVIDENCE_SET" || artifacts.lastOrNull() != "FINAL_OUTPUT" ||
			artifacts.any { it !in allowedArtifacts } ||
			artifacts.count { it == "EVIDENCE_SET" } != 1 ||
			artifacts.count { it == "WRITER_OUTPUT" } != 1 ||
			artifacts.count { it == "FINAL_OUTPUT" } != 1 ||
			artifacts.count { it == "CONFLICT_DECISION" } != audit.resolvedInterventionCount ||
			artifacts.count { it == "REWRITER_OUTPUT" } != audit.rewriteCount
		) return false

		val expectedModelArtifacts = audit.invocations.map { invocation ->
			when (invocation.role) {
				"WRITER" -> "WRITER_OUTPUT"
				"REVIEWER" -> "REVIEWER_OUTPUT"
				"REWRITER" -> "REWRITER_OUTPUT"
				else -> return false
			}
		}
		val actualModelArtifacts = artifacts.filter {
			it == "WRITER_OUTPUT" || it == "REVIEWER_OUTPUT" || it == "REWRITER_OUTPUT"
		}
		if (actualModelArtifacts != expectedModelArtifacts ||
			actualModelArtifacts.firstOrNull() != "WRITER_OUTPUT" ||
			actualModelArtifacts.lastOrNull() != "REVIEWER_OUTPUT"
		) return false

		return artifacts.indices.all { index ->
			when (artifacts[index]) {
				"REWRITER_OUTPUT" ->
					artifacts.getOrNull(index - 1) in setOf("REVIEWER_OUTPUT", "CONFLICT_DECISION") &&
						artifacts.getOrNull(index + 1) == "REVIEWER_OUTPUT"
				"CONFLICT_DECISION" ->
					artifacts.getOrNull(index - 1) == "REVIEWER_OUTPUT" &&
						artifacts.getOrNull(index + 1) in setOf("REWRITER_OUTPUT", "FINAL_OUTPUT")
				else -> true
			}
		}
	}

	private fun result(
		campaign: SealedArtifact<CampaignManifest>,
		execution: SealedArtifact<ModelExecutionManifest>,
		browser: EvidenceEnvelope,
		audit: CertificationAuditEnvelope,
		attemptReplacesAttemptId: String?,
		replacementModelResultHash: String?,
		outcome: EvidenceOutcome,
		codes: Set<ReconciliationCode>,
	): CertificationReconciliationResult = CertificationReconciliationResult(
			campaignId = campaign.artifact.campaignId,
			campaignManifestHash = campaign.hash,
			modelExecutionId = execution.artifact.modelExecutionId,
			modelExecutionManifestHash = execution.hash,
			sourceSnapshotSetHash = audit.sourceSnapshotSetHash,
			attemptId = browser.attemptId.orEmpty(),
			scenarioId = browser.scenarioId.orEmpty(),
			ordinal = browser.ordinal ?: 0,
			replacesAttemptId = attemptReplacesAttemptId,
			outcome = outcome,
			codes = codes.toList(),
			durableModelCallCount = audit.invocations.size,
			durableCitationCount = audit.citationCount,
			durableInterventionCount = audit.interventionCount,
			durableExportEventCount = audit.exports.size,
			replacementModelResultHash = replacementModelResultHash,
		)

	private fun sameIdentity(
		campaign: SealedArtifact<CampaignManifest>,
		execution: SealedArtifact<ModelExecutionManifest>,
		browser: EvidenceEnvelope,
		audit: CertificationAuditEnvelope,
	): Boolean = sameBrowserIdentity(campaign, execution, browser) &&
		audit.campaignId == campaign.artifact.campaignId &&
		audit.campaignManifestHash == campaign.hash &&
		audit.modelExecutionId == execution.artifact.modelExecutionId &&
		audit.modelExecutionManifestHash == execution.hash &&
		audit.attemptId == browser.attemptId &&
		audit.scenarioId == browser.scenarioId &&
		audit.ordinal == browser.ordinal

	private fun sameBrowserIdentity(
		campaign: SealedArtifact<CampaignManifest>,
		execution: SealedArtifact<ModelExecutionManifest>,
		browser: EvidenceEnvelope,
	): Boolean = browser.evidenceType == EvidenceType.BROWSER_OBSERVATION &&
		browser.subjectType == EvidenceSubjectType.ATTEMPT &&
		browser.campaignId == campaign.artifact.campaignId &&
		browser.campaignManifestHash == campaign.hash &&
		browser.modelExecutionId == execution.artifact.modelExecutionId &&
		browser.modelExecutionManifestHash == execution.hash

	private fun EvidenceEnvelope.metric(name: String): Int? = (metrics[name] as? Number)?.toInt()

	companion object {
		private val ATTEMPT_ID = Regex("^attempt-[a-f0-9]{16,64}$")
		private val REQUIRED_BROWSER_CODES = setOf(
			"BROWSER_CONTRACT_OBSERVED",
			"CITATION_POPOVER_OBSERVED",
			"EVIDENCE_FREE_SENTENCE_OBSERVED",
			"EXPORT_CONFIRMATION_OBSERVED",
			"HUMAN_DECISION_OBSERVED",
			"MARKDOWN_SAFETY_OBSERVED",
			"PENDING_AUDIT_RECONCILIATION",
			"REAL_GITHUB_BLOCKS_OBSERVED",
			"STALE_EDIT_OBSERVED",
		)
	}
}
