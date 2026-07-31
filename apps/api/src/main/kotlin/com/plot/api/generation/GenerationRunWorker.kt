package com.plot.api.generation

import com.plot.api.ai.provider.GenerationModelException
import com.plot.api.ai.provider.GenerationModelGateway
import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelFailureCode
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
	private val clock: Clock = Clock.systemUTC(),
	private val claimTimeout: Duration = Duration.ofMinutes(2),
	private val retryInitialDelay: Duration = Duration.ofMillis(250),
	private val workerId: String = "generation-${UUID.randomUUID()}",
	private val leaseFactory: GenerationRunLeaseFactory = GenerationRunLeaseFactory { claim ->
		GenerationRunLeaseHandle(
			GenerationRunLease(claim, renewClaim = { _, _ -> true }, clock),
		) {}
	},
) {
	internal var lastFailure: RuntimeException? = null
		private set

	/** Processes one logical model call so every successful call becomes a durable checkpoint. */
	fun processOne(): Boolean {
		val claim = persistence.claimNext(workerId, clock.instant().minus(claimTimeout)) ?: return false
		return leaseFactory.open(claim).use { handle ->
			try {
				processClaim(claim, handle.lease)
			} catch (_: GenerationRunLeaseLostException) {
				true
			}
		}
	}

	private fun processClaim(claim: ClaimedGenerationRun, runLease: GenerationRunLease): Boolean {
		val state = persistence.loadState(claim.workspaceId, claim.runId)
		val budgetFailure = persistence.budgetFailureCode(claim)
		if (budgetFailure != null) {
			runLease.commit { persistence.failClaim(claim, state, budgetFailure) }
			return true
		}
		val role = state.nextRole ?: return false
		val invocation = runLease.commit { persistence.beginInvocation(claim, role) }
		val recording = RecordingGateway(modelGateway)
		try {
			val advanced = workflowService.advance(state, recording)
			runLease.commit { persistence.completeCheckpoint(claim, invocation, advanced, recording.metadata) }
		} catch (failure: GenerationModelException) {
			runLease.commit {
				if (
					failure.code == ModelFailureCode.PROVIDER_UNAVAILABLE &&
					invocation.attemptNo < MAX_PHYSICAL_ATTEMPTS_PER_LOGICAL_CALL
				) {
					persistence.scheduleInvocationRetry(
						claim = claim,
						lease = invocation,
						code = failure.code.name,
						nextAttemptAt = clock.instant().plus(retryDelay(invocation.attemptNo)),
						metadata = recording.metadata,
					)
				} else {
					persistence.failCheckpoint(claim, invocation, state, failure.code.name, recording.metadata)
				}
			}
		} catch (_: InvalidModelOutputException) {
			runLease.commit {
				persistence.failCheckpoint(claim, invocation, state, "MALFORMED_OUTPUT", recording.metadata)
			}
		} catch (failure: GenerationRunLeaseLostException) {
			throw failure
		} catch (failure: RuntimeException) {
			lastFailure = failure
			runLease.commit {
				persistence.failCheckpoint(claim, invocation, state, "WORKFLOW_FAILED", recording.metadata)
			}
		}
		return true
	}

	fun drain(maxCheckpoints: Int = 16): Int {
		require(maxCheckpoints > 0)
		var processed = 0
		while (processed < maxCheckpoints && processOne()) processed++
		return processed
	}

	private fun retryDelay(attemptNo: Int): Duration =
		minOf(
			MAX_RETRY_DELAY,
			retryInitialDelay.multipliedBy(1L shl (attemptNo - 1).coerceIn(0, MAX_RETRY_SHIFT)),
		)

	private companion object {
		const val MAX_PHYSICAL_ATTEMPTS_PER_LOGICAL_CALL = 3
		const val MAX_RETRY_SHIFT = 8
		val MAX_RETRY_DELAY: Duration = Duration.ofMinutes(15)
	}
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
