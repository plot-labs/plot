package com.plot.api.github

import com.plot.api.common.WorkspacePrincipal
import com.plot.api.artifact.run.ArtifactRunPersistence
import com.plot.api.artifact.run.ArtifactRunStatus
import com.plot.api.artifact.workflow.ArtifactWorkflowPersistence
import com.plot.api.artifact.workflow.ArtifactWorkflowRunService
import com.plot.api.artifact.workflow.ArtifactWorkflowRunNotFoundException
import com.plot.api.artifact.workflow.ArtifactWorkflowRunStatus
import com.plot.api.artifact.workflow.ArtifactWorkflowState
import com.plot.api.routine.AgentRunPersistence
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

interface GitHubReleaseArtifactWorkflowGateway {
	fun create(
		principal: WorkspacePrincipal,
		sourceScopeId: UUID,
		writingBlockIds: List<UUID>,
		instruction: String,
		idempotencyKey: String,
	): ArtifactWorkflowState

	fun load(workspaceId: UUID, runId: UUID): ArtifactWorkflowState
}

@Component
class DefaultGitHubReleaseArtifactWorkflowGateway(
	private val artifactWorkflowRunService: ArtifactWorkflowRunService,
	private val artifactWorkflowPersistence: ArtifactWorkflowPersistence,
) : GitHubReleaseArtifactWorkflowGateway {
	override fun create(
		principal: WorkspacePrincipal,
		sourceScopeId: UUID,
		writingBlockIds: List<UUID>,
		instruction: String,
		idempotencyKey: String,
	): ArtifactWorkflowState = artifactWorkflowRunService.createForPrincipal(
		principal = principal,
		sourceScopeId = sourceScopeId,
		writingBlockIds = writingBlockIds,
		instruction = instruction,
		idempotencyKey = idempotencyKey,
	)

	override fun load(workspaceId: UUID, runId: UUID): ArtifactWorkflowState =
		artifactWorkflowPersistence.loadState(workspaceId, runId)
}

@Service
class GitHubReleaseDraftOrchestrator(
	private val persistence: GitHubReleasePersistence,
	private val scopeResolver: GitHubReleaseScopeResolver,
	private val rangeResolver: GitHubReleaseRangeResolver,
	private val evidenceService: GitHubReleaseEvidenceService,
	private val artifactWorkflowGateway: GitHubReleaseArtifactWorkflowGateway,
	private val evidenceArtifactWorkflowBinder: GitHubReleaseEvidenceArtifactWorkflowBinder,
	private val agentAdmission: GitHubReleaseAgentAdmission? = null,
	private val artifactRunPersistence: ArtifactRunPersistence? = null,
	private val agentRunPersistence: AgentRunPersistence? = null,
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
				startAndLinkArtifactWorkflow(request, context, principal, previouslyBound, lease)
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
					var generation: ArtifactWorkflowState? = null
					var agentRunId: UUID? = null
					lease.transition { transitionVersion ->
						if (agentAdmission != null) {
							agentRunId = agentAdmission.bindAndAdmit(
								request = request,
								transitionVersion = transitionVersion,
								principal = principal,
								evidence = evidence,
								instruction = instruction(request),
								idempotencyKey = idempotencyKey(context, request),
							).id
						} else {
							generation = evidenceArtifactWorkflowBinder.bindAndCreate(
								request = request,
								transitionVersion = transitionVersion,
								principal = principal,
								evidence = evidence,
								instruction = instruction(request),
								idempotencyKey = idempotencyKey(context, request),
							)
						}
					}
					lease.checkpoint()
					if (agentRunId != null) {
						persistence.linkAgentRun(request.id, lease.transitionVersion, evidence.observationId, agentRunId)
					} else {
						linkArtifactWorkflow(request, evidence, checkNotNull(generation), lease)
					}
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
				if (request.agentRunId != null && artifactRunPersistence != null && agentRunPersistence != null) {
					val artifact = artifactRunPersistence.findWorkflowStateByAgentRun(request.workspaceId, request.agentRunId)
					when {
						artifact?.status == ArtifactRunStatus.FAILED -> persistence.finish(
							request.id, request.transitionVersion, GitHubReleaseDraftStatus.FAILED, "ARTIFACT_WORKFLOW_FAILED",
						)
						artifact?.materialized == true && artifact.workflowRunId != null && artifact.status in setOf(ArtifactRunStatus.READY, ArtifactRunStatus.NEEDS_REVIEW) -> {
							persistence.linkAgentArtifact(request.id, request.transitionVersion, request.agentRunId, artifact.workflowRunId)
							persistence.finish(request.id, request.transitionVersion + 1, GitHubReleaseDraftStatus.READY)
						}
						agentRunPersistence.findAgentRun(request.workspaceId, request.agentRunId)?.status == com.plot.api.routine.AgentRunStatus.FAILED -> persistence.finish(
							request.id, request.transitionVersion, GitHubReleaseDraftStatus.FAILED, "AGENT_RUN_FAILED",
						)
					}
					return@forEach
				}
				val runId = request.artifactWorkflowRunId ?: return@forEach
				val generation = artifactWorkflowGateway.load(request.workspaceId, runId)
				when (generation.status) {
					ArtifactWorkflowRunStatus.READY, ArtifactWorkflowRunStatus.NEEDS_REVIEW -> persistence.finish(
						request.id,
						request.transitionVersion,
						GitHubReleaseDraftStatus.READY,
					)
					ArtifactWorkflowRunStatus.FAILED -> persistence.finish(
						request.id,
						request.transitionVersion,
						GitHubReleaseDraftStatus.FAILED,
						"GENERATION_FAILED",
					)
					else -> Unit
				}
			} catch (_: ArtifactWorkflowRunNotFoundException) {
				runCatching {
					persistence.finish(
						request.id,
						request.transitionVersion,
						GitHubReleaseDraftStatus.FAILED,
						"GENERATION_STATE_UNAVAILABLE",
					)
				}
			} catch (_: RuntimeException) {
				runCatching {
					persistence.recordReconcileDiagnostic(
						request.id,
						request.transitionVersion,
						"GENERATION_RECONCILE_FAILED",
					)
				}
			}
		}
	}

	private fun startAndLinkArtifactWorkflow(
		request: GitHubReleaseDraftRequest,
		context: GitHubReleaseSourceContext,
		principal: WorkspacePrincipal,
		evidence: GitHubReleaseEvidence,
		lease: GitHubReleaseLease,
	) {
		lease.checkpoint()
		if (agentAdmission != null) {
			val agentRun = agentAdmission.bindAndAdmit(
				request = request,
				transitionVersion = lease.transitionVersion,
				principal = principal,
				evidence = evidence,
				instruction = instruction(request),
				idempotencyKey = idempotencyKey(context, request),
			)
			lease.advanceTransition()
			lease.checkpoint()
			persistence.linkAgentRun(request.id, lease.transitionVersion, evidence.observationId, agentRun.id)
			return
		}
		val generation = artifactWorkflowGateway.create(
			principal = principal,
			sourceScopeId = context.sourceScopeId,
			writingBlockIds = evidence.writingBlockIds,
			instruction = instruction(request),
			idempotencyKey = idempotencyKey(context, request),
		)
		lease.checkpoint()
		linkArtifactWorkflow(request, evidence, generation, lease)
	}

	private fun linkArtifactWorkflow(
		request: GitHubReleaseDraftRequest,
		evidence: GitHubReleaseEvidence,
		generation: ArtifactWorkflowState,
		lease: GitHubReleaseLease,
	) {
		persistence.linkArtifactWorkflow(
			request.id,
			lease.transitionVersion,
			evidence.observationId,
			generation.runId,
		)
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
