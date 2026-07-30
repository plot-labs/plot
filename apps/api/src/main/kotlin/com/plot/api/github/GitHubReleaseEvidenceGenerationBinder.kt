package com.plot.api.github

import com.plot.api.common.WorkspacePrincipal
import com.plot.api.generation.GenerationWorkflowState
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

interface GitHubReleaseEvidenceGenerationBinder {
	fun bindAndCreate(
		request: GitHubReleaseDraftRequest,
		transitionVersion: Long,
		principal: WorkspacePrincipal,
		evidence: GitHubReleaseEvidence,
		instruction: String,
		idempotencyKey: String,
	): GenerationWorkflowState
}

@Service
class DefaultGitHubReleaseEvidenceGenerationBinder(
	private val persistence: GitHubReleasePersistence,
	private val generationGateway: GitHubReleaseGenerationGateway,
	private val transactionTemplate: TransactionTemplate,
) : GitHubReleaseEvidenceGenerationBinder {
	override fun bindAndCreate(
		request: GitHubReleaseDraftRequest,
		transitionVersion: Long,
		principal: WorkspacePrincipal,
		evidence: GitHubReleaseEvidence,
		instruction: String,
		idempotencyKey: String,
	): GenerationWorkflowState = checkNotNull(transactionTemplate.execute {
		persistence.bindEvidence(request.id, transitionVersion, evidence)
		generationGateway.create(
			principal = principal,
			sourceScopeId = request.sourceScopeId,
			writingBlockIds = evidence.writingBlockIds,
			instruction = instruction,
			idempotencyKey = idempotencyKey,
		)
	})
}
