package com.plot.api.github

import com.plot.api.common.WorkspacePrincipal
import com.plot.api.routine.AgentRunRecord
import com.plot.api.routine.ChatAgentAdmissionService
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

interface GitHubReleaseAgentAdmission {
	fun bindAndAdmit(
		request: GitHubReleaseDraftRequest,
		transitionVersion: Long,
		principal: WorkspacePrincipal,
		evidence: GitHubReleaseEvidence,
		instruction: String,
		idempotencyKey: String,
	): AgentRunRecord
}

@Component
class DefaultGitHubReleaseAgentAdmission(
	private val persistence: GitHubReleasePersistence,
	private val chatAgentAdmissionService: ChatAgentAdmissionService,
	private val transactionTemplate: TransactionTemplate,
) : GitHubReleaseAgentAdmission {
	override fun bindAndAdmit(
		request: GitHubReleaseDraftRequest,
		transitionVersion: Long,
		principal: WorkspacePrincipal,
		evidence: GitHubReleaseEvidence,
		instruction: String,
		idempotencyKey: String,
	): AgentRunRecord = checkNotNull(transactionTemplate.execute {
		if (request.observationId == null) {
			persistence.bindEvidence(request.id, transitionVersion, evidence)
		}
		chatAgentAdmissionService.admitAutomated(
			principal = principal,
			instruction = instruction,
			writingBlockIds = evidence.writingBlockIds,
			idempotencyKey = idempotencyKey,
			chatTitle = "GitHub release ${request.tagName}",
		)
	})
}
