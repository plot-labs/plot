package com.plot.api.github

import com.plot.api.common.WorkspacePrincipal
import com.plot.api.generation.GenerationPersistence
import com.plot.api.generation.GenerationRunService
import com.plot.api.generation.GenerationRunNotFoundException
import com.plot.api.generation.GenerationRunStatus
import com.plot.api.generation.GenerationWorkflowState
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

interface GitHubReleaseGenerationGateway {
	fun create(
		principal: WorkspacePrincipal,
		sourceScopeId: UUID,
		writingBlockIds: List<UUID>,
		instruction: String,
		idempotencyKey: String,
	): GenerationWorkflowState

	fun load(workspaceId: UUID, runId: UUID): GenerationWorkflowState
}

@Component
class DefaultGitHubReleaseGenerationGateway(
	private val generationRunService: GenerationRunService,
	private val generationPersistence: GenerationPersistence,
) : GitHubReleaseGenerationGateway {
	override fun create(
		principal: WorkspacePrincipal,
		sourceScopeId: UUID,
		writingBlockIds: List<UUID>,
		instruction: String,
		idempotencyKey: String,
	): GenerationWorkflowState = generationRunService.createForPrincipal(
		principal = principal,
		sourceScopeId = sourceScopeId,
		writingBlockIds = writingBlockIds,
		instruction = instruction,
		idempotencyKey = idempotencyKey,
	)

	override fun load(workspaceId: UUID, runId: UUID): GenerationWorkflowState =
		generationPersistence.loadState(workspaceId, runId)
}

@Service
class GitHubReleaseDraftOrchestrator(
	private val persistence: GitHubReleasePersistence,
	private val scopeResolver: GitHubReleaseScopeResolver,
	private val rangeResolver: GitHubReleaseRangeResolver,
	private val evidenceService: GitHubReleaseEvidenceService,
	private val generationGateway: GitHubReleaseGenerationGateway,
	private val evidenceGenerationBinder: GitHubReleaseEvidenceGenerationBinder,
) {
	fun process(request: GitHubReleaseDraftRequest, lease: GitHubReleaseLease) {
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
				startAndLinkGeneration(request, context, principal, previouslyBound, lease)
				return
			}
			when (val rangeResult = rangeResolver.resolve(context, request)) {
				is GitHubReleaseRangeResult.NeedsRange -> return
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
					return
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
						return
					}
					lease.checkpoint()
					var generation: GenerationWorkflowState? = null
					lease.transition { transitionVersion ->
						generation = evidenceGenerationBinder.bindAndCreate(
							request = request,
							transitionVersion = transitionVersion,
							principal = principal,
							evidence = evidence,
							instruction = instruction(request),
							idempotencyKey = idempotencyKey(context, request),
						)
					}
					lease.checkpoint()
					linkGeneration(request, evidence, checkNotNull(generation), lease)
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
				val runId = request.generationRunId ?: return@forEach
				val generation = generationGateway.load(request.workspaceId, runId)
				when (generation.status) {
					GenerationRunStatus.READY, GenerationRunStatus.NEEDS_REVIEW -> persistence.finish(
						request.id,
						request.transitionVersion,
						GitHubReleaseDraftStatus.READY,
					)
					GenerationRunStatus.FAILED -> persistence.finish(
						request.id,
						request.transitionVersion,
						GitHubReleaseDraftStatus.FAILED,
						"GENERATION_FAILED",
					)
					else -> Unit
				}
			} catch (_: GenerationRunNotFoundException) {
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

	private fun startAndLinkGeneration(
		request: GitHubReleaseDraftRequest,
		context: GitHubReleaseSourceContext,
		principal: WorkspacePrincipal,
		evidence: GitHubReleaseEvidence,
		lease: GitHubReleaseLease,
	) {
		lease.checkpoint()
		val generation = generationGateway.create(
			principal = principal,
			sourceScopeId = context.sourceScopeId,
			writingBlockIds = evidence.writingBlockIds,
			instruction = instruction(request),
			idempotencyKey = idempotencyKey(context, request),
		)
		lease.checkpoint()
		linkGeneration(request, evidence, generation, lease)
	}

	private fun linkGeneration(
		request: GitHubReleaseDraftRequest,
		evidence: GitHubReleaseEvidence,
		generation: GenerationWorkflowState,
		lease: GitHubReleaseLease,
	) {
		persistence.linkGeneration(
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
