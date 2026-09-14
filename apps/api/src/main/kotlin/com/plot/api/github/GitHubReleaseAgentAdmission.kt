package com.plot.api.github

import com.plot.api.common.WorkspacePrincipal
import com.plot.api.autonomy.signal.SignalEvaluationPersistence
import com.plot.api.routine.AgentRunRecord
import com.plot.api.routine.ChatAgentAdmissionService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

interface GitHubReleaseAgentAdmission {
	fun prepare(request: GitHubReleaseDraftRequest) {}

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
	private val requestPersistence: GitHubReleaseRequestStore,
	private val chatAgentAdmissionService: ChatAgentAdmissionService,
	private val routineService: GitHubReleaseRoutineService,
	private val signalEvaluations: SignalEvaluationPersistence,
	private val preparationGate: GitHubReleasePreparationGate? = null,
) : GitHubReleaseAgentAdmission {
	override fun prepare(request: GitHubReleaseDraftRequest) {
		if (request.routineId != null) routineService.prepare(request)
	}

	@Transactional
	override fun bindAndAdmit(
		request: GitHubReleaseDraftRequest,
		transitionVersion: Long,
		principal: WorkspacePrincipal,
		evidence: GitHubReleaseEvidence,
		instruction: String,
		idempotencyKey: String,
	): AgentRunRecord {
		val evidenceTransitionVersion = if (request.observationId == null) {
			requestPersistence.bindEvidence(request.id, transitionVersion, evidence)
			transitionVersion + 1
		} else transitionVersion
		val agentRun = if (request.routineId != null) routineService.admit(request, evidence) else chatAgentAdmissionService.admitAutomated(
			principal = principal,
			instruction = instruction,
			writingBlockIds = evidence.writingBlockIds,
			idempotencyKey = idempotencyKey,
			chatTitle = "GitHub release ${request.tagName}",
		)
		requestPersistence.linkAgentRun(request.id, evidenceTransitionVersion, evidence.observationId, agentRun.id)
		signalEvaluations.recordReleaseAdmission(
			workspaceId = request.workspaceId,
			sourceScopeId = request.sourceScopeId,
			tagName = request.tagName,
			agentRunId = agentRun.id,
		)
		preparationGate?.admitted(request, agentRun.id)
		return agentRun
	}
}
