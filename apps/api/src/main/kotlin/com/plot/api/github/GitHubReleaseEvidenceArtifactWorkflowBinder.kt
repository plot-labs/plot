package com.plot.api.github

import com.plot.api.common.WorkspacePrincipal
import com.plot.api.artifact.workflow.ArtifactWorkflowState
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

interface GitHubReleaseEvidenceArtifactWorkflowBinder {
	fun bindAndCreate(
		request: GitHubReleaseDraftRequest,
		transitionVersion: Long,
		principal: WorkspacePrincipal,
		evidence: GitHubReleaseEvidence,
		instruction: String,
		idempotencyKey: String,
	): ArtifactWorkflowState
}

@Service
class DefaultGitHubReleaseEvidenceArtifactWorkflowBinder(
	private val persistence: GitHubReleasePersistence,
	private val artifactWorkflowGateway: GitHubReleaseArtifactWorkflowGateway,
	private val transactionTemplate: TransactionTemplate,
) : GitHubReleaseEvidenceArtifactWorkflowBinder {
	override fun bindAndCreate(
		request: GitHubReleaseDraftRequest,
		transitionVersion: Long,
		principal: WorkspacePrincipal,
		evidence: GitHubReleaseEvidence,
		instruction: String,
		idempotencyKey: String,
	): ArtifactWorkflowState = checkNotNull(transactionTemplate.execute {
		persistence.bindEvidence(request.id, transitionVersion, evidence)
		artifactWorkflowGateway.create(
			principal = principal,
			sourceScopeId = request.sourceScopeId,
			writingBlockIds = evidence.writingBlockIds,
			instruction = instruction,
			idempotencyKey = idempotencyKey,
		)
	})
}
