package com.plot.api.certification

data class CertificationRestartDurableState(
	val schemaVersion: String = "certification-restart-state-v2",
	val campaignId: String,
	val campaignManifestHash: String,
	val modelExecutionId: String,
	val modelExecutionManifestHash: String,
	val attemptId: String,
	val sourceSnapshotSetHash: String,
	val workspaceIdHash: String,
	val runIdHash: String,
	val createdByUserIdHash: String,
	val idempotencyKeyHash: String,
	val requestFingerprintHash: String,
	val provider: String,
	val modelName: String,
	val checkpointArtifact: RestartCheckpointArtifact,
	val checkpointArtifactCount: Int,
	val runStatus: String,
	val writerSucceededCount: Int,
	val reviewerSucceededCount: Int,
	val rewriterSucceededCount: Int,
	val citationCount: Int,
	val interventionCount: Int,
	val contentPackCount: Int,
	val exportEventCount: Int,
	val uniqueness: CertificationRestartDistinctKeys,
)

data class CertificationRestartDistinctKeys(
	val sourceInputRows: Int,
	val sourceInputDistinctKeys: Int,
	val workflowStepRows: Int,
	val workflowStepDistinctKeys: Int,
	val modelInvocationRows: Int,
	val modelInvocationDistinctKeys: Int,
	val artifactRows: Int,
	val artifactDistinctKeys: Int,
	val citationRows: Int,
	val citationDistinctKeys: Int,
	val interventionRows: Int,
	val interventionDistinctKeys: Int,
	val contentPackRows: Int,
	val contentPackDistinctKeys: Int,
	val exportEventRows: Int,
	val exportEventDistinctKeys: Int,
) {
	fun verified(): Boolean = listOf(
		sourceInputRows to sourceInputDistinctKeys,
		workflowStepRows to workflowStepDistinctKeys,
		modelInvocationRows to modelInvocationDistinctKeys,
		artifactRows to artifactDistinctKeys,
		citationRows to citationDistinctKeys,
		interventionRows to interventionDistinctKeys,
		contentPackRows to contentPackDistinctKeys,
		exportEventRows to exportEventDistinctKeys,
	).all { (rows, distinctKeys) -> rows >= 0 && rows == distinctKeys }
}

class CertificationProcessRestartReconciler {
	fun reconcile(before: CertificationRestartDurableState, after: CertificationRestartDurableState): CertificationProcessRestartResult {
		val identityMatches = before.campaignId == after.campaignId &&
			before.campaignManifestHash == after.campaignManifestHash &&
			before.modelExecutionId == after.modelExecutionId &&
			before.modelExecutionManifestHash == after.modelExecutionManifestHash &&
			before.attemptId == after.attemptId &&
			before.sourceSnapshotSetHash == after.sourceSnapshotSetHash &&
			before.workspaceIdHash == after.workspaceIdHash &&
			before.runIdHash == after.runIdHash &&
			before.createdByUserIdHash == after.createdByUserIdHash &&
			before.idempotencyKeyHash == after.idempotencyKeyHash &&
			before.requestFingerprintHash == after.requestFingerprintHash &&
			before.provider == after.provider && before.modelName == after.modelName &&
			before.checkpointArtifact == after.checkpointArtifact
		val beforeValid = before.checkpointArtifact == RestartCheckpointArtifact.WRITER_OUTPUT &&
			before.checkpointArtifactCount == 1 && before.uniqueness.verified() && before.uniqueness.sourceInputRows > 0 &&
			before.runStatus == "REVIEWING" && before.writerSucceededCount == 1 && before.reviewerSucceededCount == 0 &&
			before.rewriterSucceededCount == 0 && before.citationCount == 0 && before.interventionCount == 0 &&
			before.contentPackCount == 0 && before.exportEventCount == 0 &&
			before.uniqueness.modelInvocationRows == 1 && before.uniqueness.citationRows == before.citationCount &&
			before.uniqueness.interventionRows == before.interventionCount &&
			before.uniqueness.contentPackRows == before.contentPackCount &&
			before.uniqueness.exportEventRows == before.exportEventCount
		val afterValid = after.runStatus in setOf("READY", "NEEDS_REVIEW", "NEEDS_YOUR_CALL") &&
			after.checkpointArtifactCount == 1 && after.uniqueness.verified() &&
			after.writerSucceededCount == 1 && after.reviewerSucceededCount == 1 && after.rewriterSucceededCount in 0..3 &&
			after.uniqueness.modelInvocationRows == after.writerSucceededCount + after.reviewerSucceededCount + after.rewriterSucceededCount &&
			after.contentPackCount == 1 && after.exportEventCount == 0 &&
			after.uniqueness.citationRows == after.citationCount && after.uniqueness.interventionRows == after.interventionCount &&
			after.uniqueness.contentPackRows == after.contentPackCount && after.uniqueness.exportEventRows == after.exportEventCount &&
			after.uniqueness.sourceInputRows == before.uniqueness.sourceInputRows
		val passed = identityMatches && beforeValid && afterValid
		return CertificationProcessRestartResult(
			campaignId = before.campaignId,
			campaignManifestHash = before.campaignManifestHash,
			modelExecutionId = before.modelExecutionId,
			modelExecutionManifestHash = before.modelExecutionManifestHash,
			attemptId = before.attemptId,
			outcome = if (passed) EvidenceOutcome.PASS else EvidenceOutcome.HARD_GATE_FAIL,
			code = if (passed) ProcessRestartCode.PROCESS_RESTART_RECONCILED else ProcessRestartCode.PROCESS_RESTART_FAILED,
			checkpointArtifact = before.checkpointArtifact,
			sourceSnapshotSetHash = before.sourceSnapshotSetHash,
			modelCallCount = after.writerSucceededCount + after.reviewerSucceededCount + after.rewriterSucceededCount,
			citationCount = after.citationCount,
			interventionCount = after.interventionCount,
			contentPackCount = after.contentPackCount,
			exportEventCount = after.exportEventCount,
		)
	}
}
