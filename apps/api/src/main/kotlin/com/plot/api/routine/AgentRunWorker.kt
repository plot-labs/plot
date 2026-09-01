package com.plot.api.routine

import com.plot.api.ai.provider.AgentDecision
import com.plot.api.ai.provider.AgentDecisionAction
import com.plot.api.ai.provider.AgentDecisionException
import com.plot.api.ai.provider.AgentDecisionGateway
import com.plot.api.ai.provider.AgentDecisionRequest
import com.plot.api.ai.provider.AgentInputView
import com.plot.api.ai.provider.AgentStepView
import com.plot.api.artifact.run.ArtifactRunPersistence
import com.plot.api.common.ApiException
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.artifact.workflow.ArtifactWorkflowIdempotencyConflictException
import com.plot.api.artifact.workflow.ArtifactWorkflowRunService
import com.plot.api.observability.stopSafely
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class AgentRunWorker(
	private val queryPersistence: AgentRunQueryPersistence,
	private val executionPersistence: AgentRunExecutionPersistence,
	private val decisionGateway: AgentDecisionGateway,
	private val tools: ReadOnlyAgentTools,
	private val artifactWorkflowRunService: ArtifactWorkflowRunService,
	private val artifactRunPersistence: ArtifactRunPersistence,
	private val workspaceAccessService: WorkspaceAccessService,
	private val properties: RoutineAgentProperties,
	private val objectMapper: ObjectMapper,
	@Lazy private val agentRunDispatcher: AgentRunDispatcher,
	private val clock: Clock = Clock.systemUTC(),
	private val workerId: String = "routine-agent-${UUID.randomUUID()}",
	private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) {
	fun recover(): Int {
		if (!properties.workersEnabled) return 0
		val now = clock.instant()
		return executionPersistence.recoverStaleAgentRuns(now.minus(properties.claimTimeout), now) +
			executionPersistence.reconcileWaitingArtifactHandoffs(now)
	}

	fun earliestNextAttemptAt(): Instant? {
		if (!properties.workersEnabled) return null
		return queryPersistence.earliestNextAttemptAt(clock.instant())
	}

	fun processOne(): Boolean {
		if (!properties.workersEnabled) return false
		val claimAt = clock.instant()
		val claim = executionPersistence.claimNextAgentRun(
			workerId = workerId,
			now = claimAt,
			staleBefore = claimAt.minus(properties.claimTimeout),
		) ?: return false
		val origin = queryPersistence.findAgentRun(claim.workspaceId, claim.agentRunId)?.origin?.name ?: "UNKNOWN"
		val observation = Observation.start("plot.agent.attempt", observationRegistry)
			.lowCardinalityKeyValue("plot.operation", "agent")
			.lowCardinalityKeyValue("plot.agent_origin", origin)
			.highCardinalityKeyValue("plot.agent_run_id", claim.agentRunId.toString())
		var outcome = "SUCCEEDED"
		try {
			observation.openScope().use { processClaim(claim) }
		} catch (_: AgentRunClaimLostException) {
			outcome = "LEASE_LOST"
			// Another worker reclaimed this run. Its fenced transition wins.
		} catch (failure: AgentDecisionException) {
			outcome = "FAILED"
			val now = clock.instant()
			if (failure.recoverable) {
				val nextAttemptAt = now.plus(retryDelay(queryPersistence.findAgentRun(claim.workspaceId, claim.agentRunId)?.attemptCount ?: 0))
				executionPersistence.scheduleAgentRetry(
					claim,
					failure.code.safeCode("AGENT_PROVIDER_UNAVAILABLE"),
					nextAttemptAt,
					now,
				)
				agentRunDispatcher.scheduleDelayed(nextAttemptAt)
			} else {
				executionPersistence.failAgentRun(claim, failure.code.safeCode("AGENT_MODEL_FAILED"), now)
			}
		} catch (failure: AgentRunBudgetExceededException) {
			outcome = "FAILED"
			executionPersistence.failAgentRun(claim, failure.safeCode, clock.instant())
		} catch (failure: AgentToolAccessException) {
			outcome = "FAILED"
			executionPersistence.failAgentRun(claim, failure.safeCode, clock.instant())
		} catch (failure: ApiException) {
			outcome = "FAILED"
			executionPersistence.failAgentRun(claim, backgroundAccessCode(failure), clock.instant())
		} catch (_: ArtifactWorkflowIdempotencyConflictException) {
			outcome = "FAILED"
			executionPersistence.failAgentRun(claim, "AGENT_HANDOFF_CONFLICT", clock.instant())
		} catch (_: IllegalArgumentException) {
			outcome = "FAILED"
			executionPersistence.failAgentRun(claim, "AGENT_INVALID_DECISION", clock.instant())
		} catch (failure: RuntimeException) {
			outcome = "FAILED"
			// Unknown infrastructure outcomes retain the claim and become eligible
			// only through bounded stale recovery; they are not misreported as terminal.
			try {
				executionPersistence.recordAgentInfrastructureFailure(claim, clock.instant())
			} catch (recordFailure: RuntimeException) {
				failure.addSuppressed(recordFailure)
			}
			throw failure
		} finally {
			observation.lowCardinalityKeyValue("plot.outcome", outcome)
			observation.stopSafely()
		}
		return true
	}

	fun drain(maxTurns: Int = 16): Int {
		require(maxTurns > 0) { "Agent drain limit must be positive" }
		var processed = 0
		while (processed < maxTurns && processOne()) processed++
		return processed
	}

	private fun processClaim(claim: ClaimedAgentRun) {
		val run = queryPersistence.findAgentRun(claim.workspaceId, claim.agentRunId)
			?: throw AgentRunClaimLostException()
		val budget = frozenBudget(run)
		if (run.startedAt != null && Duration.between(run.startedAt, clock.instant()) > Duration.ofMillis(budget.maxRunDurationMillis)) {
			throw AgentRunBudgetExceededException("AGENT_DURATION_LIMIT")
		}
		val steps = queryPersistence.listSteps(run.workspaceId, run.id)
		val running = queryPersistence.findRunningStep(run.workspaceId, run.id, run.currentStep)
		if (running != null) {
			executeStep(claim, run, running, budget)
			return
		}
		if (!queryPersistence.allAgentSourcesActive(run.workspaceId, run.id)) {
			throw AgentToolAccessException("SOURCE_NOT_READY")
		}

		workspaceAccessService.requireWritable(run.workspaceId)
		val countedRun = executionPersistence.beginModelDecision(claim, budget.maxModelCalls)
		val inputs = queryPersistence.listAgentRunInputs(run.workspaceId, run.id)
		val sources = tools.listAllowedSources(run.workspaceId, run.id).sources
		if (sources.size != queryPersistence.listAgentRunSources(run.workspaceId, run.id).size) {
			throw AgentToolAccessException("SOURCE_NOT_READY")
		}
		val decision = decisionGateway.decide(
			AgentDecisionRequest(
				agentRunId = run.id,
				instruction = run.instructionSnapshot,
				sources = sources,
				inputs = inputs.map { input ->
					AgentInputView(
						id = input.id,
						sourceScopeId = input.sourceScopeId,
						title = input.snapshotTitle,
						excerpt = (input.snapshotExcerpt ?: input.snapshotBody).take(MAX_MODEL_EXCERPT),
					)
				},
				completedSteps = steps
					.filter { it.status == AgentStepStatus.SUCCEEDED || it.status == AgentStepStatus.FAILED }
					.map { step ->
						AgentStepView(step.sequence, step.toolName, step.resultJson?.take(MAX_MODEL_STEP_RESULT))
					},
				remainingModelCalls = (budget.maxModelCalls - countedRun.modelCallCount).coerceAtLeast(0),
				remainingToolCalls = (budget.maxToolCalls - countedRun.toolCallCount).coerceAtLeast(0),
			),
		)
		val arguments = try {
			validateDecision(decision, sources.map { it.id }.toSet(), inputs.map { it.id }.toSet())
		} catch (failure: InvalidAgentDecisionException) {
			rejectInvalidDecision(claim, run, decision, failure, budget)
			return
		}
		val step = executionPersistence.reserveStep(
			claim = claim,
			request = AgentStepRequest(
				agentRunId = run.id,
				sequence = run.currentStep,
				kind = if (decision.action == AgentDecisionAction.CREATE_ARTIFACT) {
					AgentStepKind.ARTIFACT_HANDOFF
				} else {
					AgentStepKind.READ_TOOL
				},
				status = AgentStepStatus.RUNNING,
				idempotencyKey = "agent:${run.id}:step:${run.currentStep}",
				toolName = decision.action.takeUnless { it == AgentDecisionAction.CREATE_ARTIFACT }?.name,
				argumentsJson = objectMapper.writeValueAsString(arguments),
				startedAt = clock.instant(),
			),
			maxToolCalls = budget.maxToolCalls,
			now = clock.instant(),
		)
		executeStep(claim, countedRun, step, budget)
	}

	private fun executeStep(
		claim: ClaimedAgentRun,
		run: AgentRunRecord,
		step: AgentStepRecord,
		budget: AgentBudgetSnapshot,
	) {
		val arguments = objectMapper.readValue(step.argumentsJson, AgentStepArguments::class.java)
		workspaceAccessService.requireWritable(run.workspaceId)
		when (arguments.action) {
			AgentDecisionAction.LIST_ALLOWED_SOURCES -> {
				val result = tools.listAllowedSources(run.workspaceId, run.id)
				executionPersistence.completeToolStep(
					claim = claim,
					stepId = step.id,
					resultJson = objectMapper.writeValueAsString(
						mapOf(
							"summary" to "Listed ${result.sources.size} allowed sources",
							"sources" to result.sources,
						),
					),
					maxEvidenceCharacters = budget.maxEvidenceCharacters,
					now = clock.instant(),
				)
			}

			AgentDecisionAction.SEARCH_WRITING_BLOCKS -> {
				val sourceScopeId = requireNotNull(arguments.sourceScopeId)
				val result = tools.searchWritingBlocks(
					run.workspaceId,
					run.id,
					sourceScopeId,
					requireNotNull(arguments.query),
				)
				executionPersistence.completeToolStep(
					claim = claim,
					stepId = step.id,
					resultJson = objectMapper.writeValueAsString(
						mapOf(
							"summary" to "Found ${result.matches.size} matching source items",
							"sourceScopeId" to sourceScopeId,
							"matches" to result.matches,
						),
					),
					sourceScopeId = sourceScopeId,
					sourceStatusChangedAt = result.sourceStatusChangedAt,
					maxEvidenceCharacters = budget.maxEvidenceCharacters,
					now = clock.instant(),
				)
			}

			AgentDecisionAction.READ_WRITING_BLOCKS -> {
				val sourceScopeId = requireNotNull(arguments.sourceScopeId)
				val writingBlockId = resolveReadableBlockId(run, sourceScopeId, requireNotNull(arguments.writingBlockId))
				val result = tools.readWritingBlock(run.workspaceId, run.id, sourceScopeId, writingBlockId)
				val adopted = requireNotNull(result.adoptedInput)
				executionPersistence.completeToolStep(
					claim = claim,
					stepId = step.id,
					resultJson = objectMapper.writeValueAsString(
						mapOf(
							"summary" to "Read one source item",
							"sourceScopeId" to sourceScopeId,
							"writingBlockId" to writingBlockId,
							"title" to adopted.snapshotTitle,
							"excerpt" to adopted.snapshotExcerpt,
						),
					),
					adoptedInput = adopted,
					sourceScopeId = sourceScopeId,
					sourceStatusChangedAt = result.sourceStatusChangedAt,
					maxEvidenceCharacters = budget.maxEvidenceCharacters,
					now = clock.instant(),
				)
			}

			AgentDecisionAction.CREATE_ARTIFACT -> {
				val allInputs = queryPersistence.listAgentRunInputs(run.workspaceId, run.id).associateBy { it.id }
				val selected = arguments.selectedInputIds.map { id ->
					allInputs[id] ?: throw IllegalArgumentException("Selected Agent input is unavailable")
				}
				val evidenceCharacters = selected.sumOf { it.snapshotTitle.orEmpty().length + it.snapshotBody.length }
				if (evidenceCharacters > budget.maxEvidenceCharacters) {
					throw AgentRunBudgetExceededException("AGENT_EVIDENCE_LIMIT")
				}
				val workflow = artifactWorkflowRunService.createForAgent(
					principal = WorkspacePrincipal(run.workspaceId, run.createdByUserId),
					agentRun = run,
					inputs = selected,
					idempotencyKey = step.idempotencyKey,
				)
				val artifactRun = artifactRunPersistence.findWorkflowStateByWorkflowRun(run.workspaceId, workflow.runId)
					?: throw IllegalArgumentException("Artifact run admission was not persisted")
				executionPersistence.linkArtifactWorkflowStep(
					claim = claim,
					stepId = step.id,
					artifactWorkflowRunId = workflow.runId,
					resultJson = objectMapper.writeValueAsString(
						mapOf(
							"summary" to "Created an Artifact draft",
							"artifactWorkflowRunId" to workflow.runId,
							"artifactRunId" to artifactRun.artifactRunId,
							"selectedInputCount" to selected.size,
						),
					),
					nextAttemptAt = ARTIFACT_HANDOFF_WAIT_UNTIL,
					now = clock.instant(),
				)
				// The workflow may already be terminal, in which case its completion callback
				// ran before the handoff was linked and could not claim this run.
				executionPersistence.completeWaitingArtifactHandoff(
					workspaceId = run.workspaceId,
					artifactWorkflowRunId = workflow.runId,
					now = clock.instant(),
				)
			}
		}
	}

	private fun resolveReadableBlockId(run: AgentRunRecord, sourceScopeId: UUID, requestedId: UUID): UUID {
		// Models occasionally send an agent run input id (which the decision view exposes as `id`)
		// instead of the underlying writing block id. Remap it so the read still resolves.
		val input = queryPersistence.listAgentRunInputs(run.workspaceId, run.id)
			.firstOrNull { it.id == requestedId && it.sourceScopeId == sourceScopeId }
		return input?.writingBlockId ?: requestedId
	}

	private fun rejectInvalidDecision(
		claim: ClaimedAgentRun,
		run: AgentRunRecord,
		decision: AgentDecision,
		failure: InvalidAgentDecisionException,
		budget: AgentBudgetSnapshot,
	) {
		val now = clock.instant()
		val step = executionPersistence.reserveStep(
			claim = claim,
			request = AgentStepRequest(
				agentRunId = run.id,
				sequence = run.currentStep,
				kind = if (decision.action == AgentDecisionAction.CREATE_ARTIFACT) {
					AgentStepKind.ARTIFACT_HANDOFF
				} else {
					AgentStepKind.READ_TOOL
				},
				status = AgentStepStatus.RUNNING,
				idempotencyKey = "agent:${run.id}:step:${run.currentStep}",
				toolName = decision.action.takeUnless { it == AgentDecisionAction.CREATE_ARTIFACT }?.name,
				argumentsJson = objectMapper.writeValueAsString(decision),
				startedAt = now,
			),
			maxToolCalls = budget.maxToolCalls,
			now = now,
		)
		executionPersistence.failToolStep(
			claim = claim,
			stepId = step.id,
			code = "AGENT_INVALID_DECISION",
			resultJson = objectMapper.writeValueAsString(
				mapOf(
					"summary" to "Decision rejected",
					"error" to (failure.message ?: "The decision is invalid"),
				),
			),
			now = now,
		)
	}

	private fun validateDecision(
		decision: AgentDecision,
		allowedSourceIds: Set<UUID>,
		availableInputIds: Set<UUID>,
	): AgentStepArguments = when (decision.action) {
		AgentDecisionAction.LIST_ALLOWED_SOURCES -> AgentStepArguments(decision.action)
		AgentDecisionAction.SEARCH_WRITING_BLOCKS -> {
			val sourceScopeId = decision.sourceScopeId?.takeIf { it in allowedSourceIds }
				?: throw IllegalArgumentException("Search source is not allowed")
			val query = decision.query?.trim()?.take(200)?.takeIf { it.isNotBlank() }
				?: throw InvalidAgentDecisionException("Search query is required")
			AgentStepArguments(decision.action, sourceScopeId = sourceScopeId, query = query)
		}
		AgentDecisionAction.READ_WRITING_BLOCKS -> {
			val sourceScopeId = decision.sourceScopeId?.takeIf { it in allowedSourceIds }
				?: throw IllegalArgumentException("Read source is not allowed")
			val writingBlockId = decision.writingBlockIds.singleOrNull()
				?: throw InvalidAgentDecisionException("Read requires exactly one source item")
			AgentStepArguments(decision.action, sourceScopeId = sourceScopeId, writingBlockId = writingBlockId)
		}
		AgentDecisionAction.CREATE_ARTIFACT -> {
			val selected = decision.selectedInputIds.distinct()
			if (selected.isEmpty()) {
				throw InvalidAgentDecisionException("Artifact selection is empty; select at least one input by its id")
			}
			val unknown = selected.filter { it !in availableInputIds }
			if (unknown.isNotEmpty()) {
				throw InvalidAgentDecisionException(
					"Artifact selection includes unknown input IDs: ${unknown.joinToString()}. " +
						"Copy each id exactly from the inputs list",
				)
			}
			AgentStepArguments(decision.action, selectedInputIds = selected)
		}
	}

	private fun frozenBudget(run: AgentRunRecord): AgentBudgetSnapshot = try {
		objectMapper.readValue(run.budgetSnapshotJson, AgentBudgetSnapshot::class.java).also {
			require(it.maxModelCalls > 0 && it.maxToolCalls > 0)
			require(it.maxRunDurationMillis > 0 && it.maxInputCharacters > 0)
			require(it.maxEvidenceCharacters >= it.maxInputCharacters)
		}
	} catch (_: RuntimeException) {
		throw AgentRunBudgetExceededException("AGENT_BUDGET_INVALID")
	}

	private fun retryDelay(attemptCount: Int): Duration = minOf(
		MAX_RETRY_DELAY,
		properties.retryInitialDelay.multipliedBy(1L shl attemptCount.coerceIn(0, MAX_RETRY_SHIFT)),
	)

	private fun String.safeCode(fallback: String): String =
		trim().takeIf { SAFE_ERROR_CODE.matches(it) } ?: fallback

	private fun backgroundAccessCode(failure: ApiException): String = when (failure.error) {
		"ACCESS_DENIED", "WORKSPACE_READ_ONLY" -> failure.error
		else -> "WORKSPACE_ACCESS_FAILED"
	}

	private data class AgentStepArguments(
		val action: AgentDecisionAction,
		val sourceScopeId: UUID? = null,
		val query: String? = null,
		val writingBlockId: UUID? = null,
		val selectedInputIds: List<UUID> = emptyList(),
	)

	private companion object {
		const val MAX_MODEL_EXCERPT = 1_200
		const val MAX_MODEL_STEP_RESULT = 2_000
		const val MAX_RETRY_SHIFT = 8
		val MAX_RETRY_DELAY: Duration = Duration.ofMinutes(15)
		val SAFE_ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,99}")
		val ARTIFACT_HANDOFF_WAIT_UNTIL: Instant = Instant.parse("9999-12-31T23:59:59Z")
	}
}

class InvalidAgentDecisionException(message: String) : IllegalArgumentException(message)
