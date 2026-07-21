package com.plot.api.generation

import com.plot.api.ai.provider.GenerationModelException
import com.plot.api.ai.provider.GenerationModelGateway
import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelRole
import com.plot.api.ai.provider.ModelCallResult
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.ai.provider.WriterModelRequest
import com.plot.api.generation.model.ReviewerOutput
import com.plot.api.generation.model.TargetedRewriteOutput
import com.plot.api.generation.model.WriterOutput
import java.time.Clock
import java.time.Duration
import java.util.UUID

class GenerationRunWorker(
	private val persistence: GenerationPersistence,
	private val workflowService: GenerationWorkflowService,
	private val modelGateway: GenerationModelGateway,
	private val checkpointObserver: GenerationCheckpointObserver = GenerationCheckpointObserver.NO_OP,
	private val clock: Clock = Clock.systemUTC(),
	private val claimTimeout: Duration = Duration.ofMinutes(2),
	private val workerId: String = "generation-${UUID.randomUUID()}",
) {
	internal var lastFailure: RuntimeException? = null
		private set

	/** Processes one logical model call so every successful call becomes a durable checkpoint. */
	fun processOne(): Boolean {
		val claim = persistence.claimNext(workerId, clock.instant().minus(claimTimeout)) ?: return false
		val state = persistence.loadState(claim.workspaceId, claim.runId)
		val budgetFailure = persistence.budgetFailureCode(claim)
		if (budgetFailure != null) {
			persistence.failClaim(claim, state, budgetFailure)
			checkpointObserver.afterRunStatus(claim.workspaceId, claim.runId, failureStatus(state))
			return true
		}
		val role = state.nextRole ?: return false
		val lease = persistence.beginInvocation(claim, role)
		val recording = RecordingGateway(modelGateway)
		val advanced = try {
			val advanced = workflowService.advance(state, recording)
			persistence.completeCheckpoint(claim, lease, advanced, recording.metadata)
			advanced
		} catch (failure: GenerationModelException) {
			persistence.failCheckpoint(claim, lease, state, failure.code.name, recording.metadata)
			checkpointObserver.afterRunStatus(claim.workspaceId, claim.runId, failureStatus(state))
			return true
		} catch (_: InvalidModelOutputException) {
			persistence.failCheckpoint(claim, lease, state, "MALFORMED_OUTPUT", recording.metadata)
			checkpointObserver.afterRunStatus(claim.workspaceId, claim.runId, failureStatus(state))
			return true
		} catch (failure: RuntimeException) {
			lastFailure = failure
			persistence.failCheckpoint(claim, lease, state, "WORKFLOW_FAILED", recording.metadata)
			checkpointObserver.afterRunStatus(claim.workspaceId, claim.runId, failureStatus(state))
			return true
		}
		checkpointObserver.afterDurableCheckpoint(DurableGenerationCheckpoint(
			workspaceId = claim.workspaceId,
			runId = claim.runId,
			invocationId = lease.id,
			role = lease.role,
			artifactType = lease.role.checkpointArtifactType,
			runStatus = advanced.status,
		))
		return true
	}

	fun drain(maxCheckpoints: Int = 16): Int {
		require(maxCheckpoints > 0)
		var processed = 0
		while (processed < maxCheckpoints && processOne()) processed++
		return processed
	}
}

private fun failureStatus(state: GenerationWorkflowState): GenerationRunStatus =
	if (state.reviews.isEmpty()) GenerationRunStatus.FAILED else GenerationRunStatus.NEEDS_REVIEW

private val ModelRole.checkpointArtifactType: String
	get() = when (this) {
		ModelRole.WRITER -> "WRITER_OUTPUT"
		ModelRole.REVIEWER -> "REVIEWER_OUTPUT"
		ModelRole.REWRITER -> "REWRITER_OUTPUT"
	}

private val GenerationWorkflowState.nextRole: ModelRole?
	get() = when (status) {
		GenerationRunStatus.QUEUED, GenerationRunStatus.WRITING -> ModelRole.WRITER
		GenerationRunStatus.REVIEWING -> ModelRole.REVIEWER
		GenerationRunStatus.REWRITING -> ModelRole.REWRITER
		else -> null
	}

private class RecordingGateway(private val delegate: GenerationModelGateway) : GenerationModelGateway {
	var metadata: ModelCallMetadata? = null
		private set

	override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> = delegate.write(request).capture()
	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> = delegate.review(request).capture()
	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> = delegate.rewrite(request).capture()

	private fun <T : Any> ModelCallResult<T>.capture(): ModelCallResult<T> = also {
		this@RecordingGateway.metadata = it.metadata
	}
}
