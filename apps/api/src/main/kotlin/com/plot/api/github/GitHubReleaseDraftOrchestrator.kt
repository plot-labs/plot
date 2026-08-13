package com.plot.api.github

import com.plot.api.common.WorkspacePrincipal
import com.plot.api.artifact.run.ArtifactRunPersistence
import com.plot.api.artifact.run.ArtifactRunStatus
import com.plot.api.artifact.run.ArtifactRunWorkflowState
import com.plot.api.routine.AgentRunPersistence
import com.plot.api.routine.AgentRunStatus
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

interface GitHubReleaseExecutionProbe {
	fun findArtifact(workspaceId: UUID, agentRunId: UUID): ArtifactRunWorkflowState?
	fun findAgentStatus(workspaceId: UUID, agentRunId: UUID): AgentRunStatus?
}

@Component
class DefaultGitHubReleaseExecutionProbe(
	private val artifactRunPersistence: ArtifactRunPersistence,
	private val agentRunPersistence: AgentRunPersistence,
) : GitHubReleaseExecutionProbe {
	override fun findArtifact(workspaceId: UUID, agentRunId: UUID): ArtifactRunWorkflowState? =
		artifactRunPersistence.findWorkflowStateByAgentRun(workspaceId, agentRunId)

	override fun findAgentStatus(workspaceId: UUID, agentRunId: UUID): AgentRunStatus? =
		agentRunPersistence.findAgentRun(workspaceId, agentRunId)?.status
}

@Service
class GitHubReleaseDraftOrchestrator(
	private val persistence: GitHubReleasePersistence,
	private val scopeResolver: GitHubReleaseScopeResolver,
	private val rangeResolver: GitHubReleaseRangeResolver,
	private val evidenceService: GitHubReleaseEvidenceService,
	private val agentAdmission: GitHubReleaseAgentAdmission,
	private val executionProbe: GitHubReleaseExecutionProbe,
) {
	fun process(request: GitHubReleaseDraftRequest, lease: GitHubReleaseLease): GitHubReleaseDraftStatus {
		require(lease.workerId.isNotBlank()) { "Release worker ID is required" }
		require(lease.transitionVersion == request.transitionVersion) {
			"Release lease version does not match the claimed request"
		}
		try {
			lease.checkpoint()
			val context = resolveContext(request)
			val principal = WorkspacePrincipal(context.workspaceId, context.createdByUserId)
			lease.checkpoint()
			val previouslyBound = request.observationId?.let {
				persistence.findBoundEvidence(request.id)
					?: throw GitHubReleasePermanentException("GITHUB_RELEASE_EVIDENCE_UNAVAILABLE")
			}
			if (previouslyBound != null) {
				require(previouslyBound.observationId == request.observationId) {
					"Bound release evidence observation does not match its request"
				}
				startAndLinkAgent(request, context, principal, previouslyBound, lease)
				return GitHubReleaseDraftStatus.GENERATING
			}
			when (val rangeResult = rangeResolver.resolve(context, request)) {
				is GitHubReleaseRangeResult.NeedsRange -> return GitHubReleaseDraftStatus.NEEDS_RANGE
				is GitHubReleaseRangeResult.NoActivity -> {
					lease.checkpoint()
					lease.transition { transitionVersion ->
						persistence.saveResolvedRange(
							request.id,
							transitionVersion,
							rangeResult.baseSha,
							rangeResult.headSha,
							rangeResult.boundaryReason,
						)
					}
					persistence.finish(
						request.id,
						lease.transitionVersion,
						GitHubReleaseDraftStatus.NO_ACTIVITY,
					)
					return GitHubReleaseDraftStatus.NO_ACTIVITY
				}
				is GitHubReleaseRangeResult.Resolved -> {
					lease.checkpoint()
					val range = rangeResult.range
					lease.transition { transitionVersion ->
						persistence.saveResolvedRange(
							request.id,
							transitionVersion,
							range.baseSha,
							range.headSha,
							range.boundaryReason,
						)
					}
					val evidence = evidenceService.collect(principal, context, request, range)
					lease.checkpoint()
					if (evidence.writingBlockIds.isEmpty()) {
						persistence.finish(
							request.id,
							lease.transitionVersion,
							GitHubReleaseDraftStatus.NO_ACTIVITY,
						)
						return GitHubReleaseDraftStatus.NO_ACTIVITY
					}
					lease.checkpoint()
					lease.transition { transitionVersion ->
						agentAdmission.bindAndAdmit(
							request = request,
							transitionVersion = transitionVersion,
							principal = principal,
							evidence = evidence,
							instruction = instruction(request),
							idempotencyKey = idempotencyKey(context, request),
						)
						if (request.observationId == null) lease.advanceTransition()
					}
					lease.checkpoint()
					return GitHubReleaseDraftStatus.GENERATING
				}
			}
		} catch (exception: RuntimeException) {
			throw GitHubReleaseDraftProcessingException(
				requestId = request.id,
				transitionVersion = lease.transitionVersion,
				attemptCount = request.attemptCount,
				cause = exception,
			)
		}
	}

	fun reconcileGenerating(limit: Int) {
		persistence.findGenerating(limit).forEach { request ->
			try {
				val agentRunId = request.agentRunId
				if (agentRunId == null) {
					persistence.finish(
						request.id,
						request.transitionVersion,
						GitHubReleaseDraftStatus.FAILED,
						"AGENT_RUN_UNAVAILABLE",
					)
					return@forEach
				}
				val artifact = executionProbe.findArtifact(request.workspaceId, agentRunId)
				when {
					artifact?.status == ArtifactRunStatus.FAILED -> persistence.finish(
						request.id, request.transitionVersion, GitHubReleaseDraftStatus.FAILED, "ARTIFACT_WORKFLOW_FAILED",
					)
					artifact?.materialized == true && artifact.workflowRunId != null && artifact.status in setOf(ArtifactRunStatus.READY, ArtifactRunStatus.NEEDS_REVIEW) -> {
						persistence.linkAgentArtifact(request.id, request.transitionVersion, agentRunId, artifact.workflowRunId)
						persistence.finish(request.id, request.transitionVersion + 1, GitHubReleaseDraftStatus.READY)
					}
					executionProbe.findAgentStatus(request.workspaceId, agentRunId) == AgentRunStatus.FAILED -> persistence.finish(
						request.id, request.transitionVersion, GitHubReleaseDraftStatus.FAILED, "AGENT_RUN_FAILED",
					)
				}
			} catch (_: RuntimeException) {
				runCatching {
					persistence.recordReconcileDiagnostic(
						request.id,
						request.transitionVersion,
						"ARTIFACT_WORKFLOW_RECONCILE_FAILED",
					)
				}
			}
		}
	}

	private fun startAndLinkAgent(
		request: GitHubReleaseDraftRequest,
		context: GitHubReleaseSourceContext,
		principal: WorkspacePrincipal,
		evidence: GitHubReleaseEvidence,
		lease: GitHubReleaseLease,
	) {
		lease.checkpoint()
		agentAdmission.bindAndAdmit(
			request = request,
			transitionVersion = lease.transitionVersion,
			principal = principal,
			evidence = evidence,
			instruction = instruction(request),
			idempotencyKey = idempotencyKey(context, request),
		)
		lease.advanceTransition()
	}

	private fun instruction(request: GitHubReleaseDraftRequest): String =
		"Create a changelog for GitHub release ${request.tagName}."

	private fun resolveContext(request: GitHubReleaseDraftRequest): GitHubReleaseSourceContext {
		val delivery = persistence.findDelivery(request.initialDeliveryId)
			?: throw GitHubReleasePermanentException("GITHUB_RELEASE_DELIVERY_UNAVAILABLE")
		val installationId = delivery.installationId?.takeIf { it > 0 }
			?: throw GitHubReleasePermanentException("GITHUB_RELEASE_IDENTITY_UNAVAILABLE")
		val repositoryId = delivery.repositoryId?.takeIf { it > 0 }
			?: throw GitHubReleasePermanentException("GITHUB_RELEASE_IDENTITY_UNAVAILABLE")
		val context = scopeResolver.resolve(installationId, repositoryId)
			?: throw GitHubReleasePermanentException("GITHUB_RELEASE_SCOPE_UNAVAILABLE")
		if (context.workspaceId != request.workspaceId || context.sourceScopeId != request.sourceScopeId) {
			throw GitHubReleasePermanentException("GITHUB_RELEASE_SCOPE_MISMATCH")
		}
		return context
	}

	private fun idempotencyKey(
		context: GitHubReleaseSourceContext,
		request: GitHubReleaseDraftRequest,
	): String = listOf(
		"github-release",
		context.workspaceId,
		context.sourceScopeId,
		context.repositoryId,
		request.tagName,
		"attempt",
		request.generationAttempt,
	).joinToString(":")
}

class GitHubReleaseDraftProcessingException(
	val requestId: UUID,
	val transitionVersion: Long,
	val attemptCount: Int,
	override val cause: RuntimeException,
) : RuntimeException(cause)

class GitHubReleasePermanentException(
	val safeErrorCode: String,
) : IllegalStateException(safeErrorCode)
