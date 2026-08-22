package com.plot.api.artifact.workflow

import com.plot.api.ai.provider.ArtifactWorkflowModelException
import com.plot.api.ai.provider.ArtifactWorkflowModelGateway
import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelFailureCode
import com.plot.api.ai.provider.ModelRole
import com.plot.api.ai.provider.ModelCallResult
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.ai.provider.WriterModelRequest
import com.plot.api.common.ApiException
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.artifact.workflow.model.ReviewerOutput
import com.plot.api.artifact.workflow.model.TargetedRewriteOutput
import com.plot.api.artifact.workflow.model.WriterOutput
import com.plot.api.observability.stopSafely
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
import java.time.Duration
import java.util.UUID

class ArtifactWorkflowRunWorker(
	private val executionPersistence: ArtifactWorkflowExecutionPersistence,
	private val queryPersistence: ArtifactWorkflowQueryPersistence,
	private val workflowService: ArtifactWorkflowService,
	private val modelGateway: ArtifactWorkflowModelGateway,
	private val clock: Clock = Clock.systemUTC(),
	private val claimTimeout: Duration = Duration.ofMinutes(2),
	private val retryInitialDelay: Duration = Duration.ofMillis(250),
	private val workerId: String = "artifact-workflow-${UUID.randomUUID()}",
	private val leaseFactory: ArtifactWorkflowRunLeaseFactory = ArtifactWorkflowRunLeaseFactory { claim ->
		ArtifactWorkflowRunLeaseHandle(
			ArtifactWorkflowRunLease(claim, renewClaim = { _, _ -> true }, clock),
		) {}
	},
	private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
	private val workspaceAccessService: WorkspaceAccessService? = null,
	private val agentRunsEnabled: Boolean = true,
) {
	internal var lastFailure: RuntimeException? = null
		private set

	/** Processes one logical model call so every successful call becomes a durable checkpoint. */
	fun processOne(): Boolean {
		val claim = executionPersistence.claimNext(
			workerId,
			clock.instant().minus(claimTimeout),
			includeAgentRuns = agentRunsEnabled,
		) ?: return false
		val observation = Observation.start("plot.artifact_workflow.attempt", observationRegistry)
			.lowCardinalityKeyValue("plot.operation", "artifact_workflow")
			.highCardinalityKeyValue("plot.artifact_workflow_run_id", claim.runId.toString())
		var outcome = "SUCCEEDED"
		var processed = true
		try {
			observation.openScope().use {
				leaseFactory.open(claim).use { handle ->
					try {
						val result = processClaim(claim, handle.lease, observation)
						outcome = result.first
						processed = result.second
					} catch (_: ArtifactWorkflowRunLeaseLostException) {
						outcome = "LEASE_LOST"
					}
				}
			}
		} catch (failure: RuntimeException) {
			outcome = "FAILED"
			throw failure
		} finally {
			observation.lowCardinalityKeyValue("plot.outcome", outcome)
			observation.stopSafely()
		}
		return processed
	}

	private fun processClaim(
		claim: ClaimedArtifactWorkflowRun,
		runLease: ArtifactWorkflowRunLease,
		attemptObservation: Observation,
	): Pair<String, Boolean> {
		val state = queryPersistence.loadState(claim.workspaceId, claim.runId)
		try {
			workspaceAccessService?.requireWritable(claim.workspaceId)
		} catch (failure: ApiException) {
			val code = if (failure.error in setOf("ACCESS_DENIED", "WORKSPACE_READ_ONLY")) {
				failure.error
			} else {
				"WORKSPACE_ACCESS_FAILED"
			}
			runLease.commit { executionPersistence.failClaim(claim, state, code) }
			attemptObservation.lowCardinalityKeyValue("plot.error_code", code)
			return "FAILED" to true
		}
		val budgetFailure = executionPersistence.budgetFailureCode(claim)
		if (budgetFailure != null) {
			runLease.commit { executionPersistence.failClaim(claim, state, budgetFailure) }
			attemptObservation.lowCardinalityKeyValue("plot.error_code", budgetFailure)
			return "FAILED" to true
		}
		val role = state.nextRole ?: return "NO_ACTIVITY" to false
		attemptObservation.lowCardinalityKeyValue("plot.model_role", role.name)
		val invocation = runLease.commit { executionPersistence.beginInvocation(claim, role) }
		attemptObservation.lowCardinalityKeyValue("plot.physical_attempt", invocation.attemptNo.toString())
		val recording = RecordingGateway(modelGateway)
		val modelObservation = Observation.start("plot.artifact_workflow.model_call", observationRegistry)
			.lowCardinalityKeyValue("plot.operation", "model_invocation")
			.lowCardinalityKeyValue("plot.model_role", role.name)
			.lowCardinalityKeyValue("plot.physical_attempt", invocation.attemptNo.toString())
		var modelOutcome = "SUCCEEDED"
		try {
			modelObservation.openScope().use {
				val advanced = workflowService.advance(state, recording)
				recording.metadata?.let { metadata ->
					metadata.gateway?.let { modelObservation.highCardinalityKeyValue("plot.model_provider", it) }
					metadata.requestedModel?.let { modelObservation.highCardinalityKeyValue("plot.requested_model", it) }
					metadata.actualModel?.let { modelObservation.highCardinalityKeyValue("plot.served_model", it) }
					metadata.responseId?.let { modelObservation.highCardinalityKeyValue("plot.provider_response_id", it) }
					metadata.promptTokens?.let { modelObservation.highCardinalityKeyValue("plot.prompt_tokens", it.toString()) }
					metadata.completionTokens?.let { modelObservation.highCardinalityKeyValue("plot.completion_tokens", it.toString()) }
					metadata.totalTokens?.let { modelObservation.highCardinalityKeyValue("plot.total_tokens", it.toString()) }
					modelObservation.highCardinalityKeyValue("plot.model_latency_ms", metadata.latency.toMillis().toString())
				}
				runLease.commit { executionPersistence.completeCheckpoint(claim, invocation, advanced, recording.metadata) }
			}
		} catch (failure: ArtifactWorkflowModelException) {
			attemptObservation.lowCardinalityKeyValue("plot.error_code", failure.code.name)
			modelObservation.lowCardinalityKeyValue("plot.error_code", failure.code.name)
			runLease.commit {
				if (
					failure.code == ModelFailureCode.PROVIDER_UNAVAILABLE &&
					invocation.attemptNo < MAX_PHYSICAL_ATTEMPTS_PER_LOGICAL_CALL
				) {
					executionPersistence.scheduleInvocationRetry(
						claim = claim,
						lease = invocation,
						code = failure.code.name,
						nextAttemptAt = clock.instant().plus(retryDelay(invocation.attemptNo)),
						metadata = recording.metadata,
					)
					modelOutcome = "RETRY_SCHEDULED"
				} else {
					executionPersistence.failCheckpoint(claim, invocation, state, failure.code.name, recording.metadata, failure)
					modelOutcome = "FAILED"
				}
			}
		} catch (failure: InvalidModelOutputException) {
			attemptObservation.lowCardinalityKeyValue("plot.error_code", "MALFORMED_OUTPUT")
			modelObservation.lowCardinalityKeyValue("plot.error_code", "MALFORMED_OUTPUT")
			modelOutcome = "FAILED"
			runLease.commit {
				executionPersistence.failCheckpoint(claim, invocation, state, "MALFORMED_OUTPUT", recording.metadata, failure)
			}
		} catch (failure: ArtifactWorkflowRunLeaseLostException) {
			modelOutcome = "LEASE_LOST"
			throw failure
		} catch (failure: RuntimeException) {
			modelObservation.lowCardinalityKeyValue("plot.error_code", "WORKFLOW_FAILED")
			modelOutcome = "FAILED"
			lastFailure = failure
			attemptObservation.lowCardinalityKeyValue("plot.error_code", "WORKFLOW_FAILED")
			runLease.commit {
				executionPersistence.failCheckpoint(claim, invocation, state, "WORKFLOW_FAILED", recording.metadata, failure)
			}
		} finally {
			modelObservation.lowCardinalityKeyValue("plot.outcome", modelOutcome)
			modelObservation.stopSafely()
		}
		return modelOutcome to true
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

private val ArtifactWorkflowState.nextRole: ModelRole?
	get() = when (status) {
		ArtifactWorkflowRunStatus.QUEUED, ArtifactWorkflowRunStatus.WRITING -> ModelRole.WRITER
		ArtifactWorkflowRunStatus.REVIEWING -> ModelRole.REVIEWER
		ArtifactWorkflowRunStatus.REWRITING -> ModelRole.REWRITER
		else -> null
	}

private class RecordingGateway(private val delegate: ArtifactWorkflowModelGateway) : ArtifactWorkflowModelGateway {
	var metadata: ModelCallMetadata? = null
		private set

	override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> = delegate.write(request).capture()
	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> = delegate.review(request).capture()
	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> = delegate.rewrite(request).capture()

	private fun <T : Any> ModelCallResult<T>.capture(): ModelCallResult<T> = also {
		this@RecordingGateway.metadata = it.metadata
	}
}
