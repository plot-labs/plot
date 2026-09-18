package com.plot.api.agent

import com.plot.api.ai.provider.AgentDecision
import com.plot.api.ai.provider.AgentDecisionAction
import com.plot.api.ai.provider.AgentDecisionException
import com.plot.api.ai.provider.AgentRuntime
import com.plot.api.ai.provider.AgentRuntimeHost
import com.plot.api.ai.provider.AgentDecisionRequest
import com.plot.api.ai.provider.AgentConversationMessage
import com.plot.api.ai.provider.AgentResponseMode
import com.plot.api.ai.provider.AgentInputView
import com.plot.api.ai.provider.AgentStepView
import com.plot.api.ai.provider.ProviderUsage
import com.plot.api.billing.AiCreditControlException
import com.plot.api.billing.AiCreditCharge
import com.plot.api.billing.PolarCreditService
import com.plot.api.artifact.run.ArtifactRunPersistence
import com.plot.api.common.ApiException
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.artifact.workflow.ArtifactWorkflowIdempotencyConflictException
import com.plot.api.artifact.workflow.ArtifactWorkflowRunService
import com.plot.api.observability.stopSafely
import com.plot.api.skill.FrozenSkills
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
	private val executionPolicy: AgentRunExecutionPolicy,
	private val snapshots: AgentExecutionSnapshotPersistence,
	private val executionPersistence: AgentRunExecutionPersistence,
	private val runtime: AgentRuntime,
	private val tools: ReadOnlyAgentTools,
	private val artifactWorkflowRunService: ArtifactWorkflowRunService,
	private val artifactRunPersistence: ArtifactRunPersistence,
	private val workspaceAccessService: WorkspaceAccessService,
	private val creditService: PolarCreditService,
	private val properties: AgentProperties,
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
		} catch (failure: AiCreditControlException) {
			outcome = "FAILED"
			val now = clock.instant()
			if (failure.recoverable) {
				val nextAttemptAt = now.plus(retryDelay(queryPersistence.findAgentRun(claim.workspaceId, claim.agentRunId)?.attemptCount ?: 0))
				executionPersistence.scheduleAgentRetry(claim, failure.safeCode.safeCode("AI_CREDIT_UNAVAILABLE"), nextAttemptAt, now)
				agentRunDispatcher.scheduleDelayed(nextAttemptAt)
			} else {
				executionPersistence.failAgentRun(claim, failure.safeCode.safeCode("AI_CREDIT_FAILED"), now)
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
		executionPolicy.releaseRoutineGateFailure(run.workspaceId, run.id)?.let {
			throw AgentToolAccessException(it)
		}
		val budget = frozenBudget(run)
		if (run.startedAt != null && Duration.between(run.startedAt, clock.instant()) > Duration.ofMillis(budget.maxRunDurationMillis)) {
			throw AgentRunBudgetExceededException("AGENT_DURATION_LIMIT")
		}
		val running = queryPersistence.findRunningStep(run.workspaceId, run.id, run.currentStep)
		if (running != null) {
			executeStep(claim, run, running, budget)
			return
		}
		val frozenReplay = snapshots.isFrozenReplay(run.workspaceId, run.id)
		if (!frozenReplay && !queryPersistence.allAgentSourcesActive(run.workspaceId, run.id)) {
			throw AgentToolAccessException("SOURCE_NOT_READY")
		}

		var finished = false
		val settings = executionSettings(run)
		val responseMode = if (run.origin == AgentRunOrigin.CHAT && settings.path("responseMode").stringValue() == AgentResponseMode.FLEXIBLE.name) {
			AgentResponseMode.FLEXIBLE
		} else {
			AgentResponseMode.ARTIFACT_REQUIRED
		}
		val conversation = settings.path("conversation").takeIf { it.isArray }?.mapNotNull { message ->
			val role = message.path("role").stringValue().orEmpty()
			val content = message.path("content").stringValue().orEmpty().trim()
			if (role in setOf("user", "assistant") && content.isNotBlank()) AgentConversationMessage(role, content) else null
		}.orEmpty()
		val selectedModel = settings.path("model").takeIf { it.isTextual }?.stringValue()
		val routingProvider = settings.path("routingProvider").takeIf { it.isTextual }?.stringValue()
		val reasoningEffort = settings.path("reasoningEffort").takeIf { it.isTextual }?.stringValue()
		val host = object : AgentRuntimeHost {
			var activeInvocationId: UUID? = null
			override val finished: Boolean get() = finished
			override val modelTimeoutMillis: Long get() = minOf(
				properties.claimTimeout.toMillis() / 2,
				budget.maxRunDurationMillis - Duration.between(run.startedAt ?: clock.instant(), clock.instant()).toMillis(),
			).coerceAtLeast(1)
			override fun beforeModel() {
				checkAccess()
				if (!creditService.enabled) {
					executionPersistence.beginModelDecision(claim, budget.maxModelCalls)
					return
				}
				recoverSettlementBeforeModel()
				creditService.preflight(run.workspaceId)
				try {
					activeInvocationId = executionPersistence.beginBilledModelInvocation(claim, budget.maxModelCalls)
				} catch (failure: AgentModelInvocationBlockedException) {
					throw AiCreditControlException("AI_CREDIT_SETTLEMENT_PENDING", true, "Workspace AI usage is pending", failure)
				}
			}
			override fun afterModel(usage: ProviderUsage) {
				if (!creditService.enabled) return
				val invocationId = activeInvocationId
					?: throw AiCreditControlException("AI_USAGE_UNKNOWN", false, "AI invocation identity is unavailable")
				val charge: AiCreditCharge = try {
					creditService.calculate(usage)
				} catch (failure: AiCreditControlException) {
					executionPersistence.markModelInvocationUsageUnknown(invocationId)
					activeInvocationId = null
					throw failure
				}
				executionPersistence.recordModelInvocationUsage(claim, invocationId, usage, charge)
				creditService.publish(run.workspaceId, invocationId, usage, charge)
				executionPersistence.markModelInvocationSettled(invocationId)
				activeInvocationId = null
			}
			override fun modelFailed(failure: AgentDecisionException) {
				activeInvocationId?.let(executionPersistence::markModelInvocationAborted)
				activeInvocationId = null
			}
			private fun recoverSettlementBeforeModel() {
				val unresolved = executionPersistence.findUnresolvedModelInvocation(run.workspaceId) ?: return
				when (unresolved.status) {
					AgentModelInvocationStatus.PENDING -> {
						val usage = requireNotNull(unresolved.usage)
						val charge = AiCreditCharge(
							providerCostUsd = requireNotNull(unresolved.providerCostUsd),
							credits = requireNotNull(unresolved.credits),
							basis = requireNotNull(unresolved.billingBasis),
							policyVersion = requireNotNull(unresolved.pricePolicyVersion),
						)
						creditService.publish(run.workspaceId, unresolved.id, usage, charge)
						executionPersistence.markModelInvocationSettled(unresolved.id)
						if (unresolved.agentRunId == run.id) {
							throw AiCreditControlException(
								"AI_SETTLEMENT_RECOVERED",
								false,
								"Previous model usage was settled without repeating provider work",
							)
						}
					}
					AgentModelInvocationStatus.STARTED -> {
						if (unresolved.agentRunId == run.id) {
							executionPersistence.markModelInvocationUsageUnknown(unresolved.id)
							throw AiCreditControlException("AI_USAGE_UNKNOWN", false, "Previous provider usage is unavailable")
						}
						throw AiCreditControlException("AI_CREDIT_SETTLEMENT_PENDING", true, "Workspace AI usage is in progress")
					}
					else -> Unit
				}
			}
			private fun checkAccess() {
				queryPersistence.requireAgentClaim(claim)
				workspaceAccessService.requireWritable(run.workspaceId)
				executionPolicy.releaseRoutineGateFailure(run.workspaceId, run.id)?.let { throw AgentToolAccessException(it) }
				if (run.startedAt != null && Duration.between(run.startedAt, clock.instant()).toMillis() > budget.maxRunDurationMillis) {
					throw AgentRunBudgetExceededException("AGENT_DURATION_LIMIT")
				}
			}
			override fun context(): AgentDecisionRequest {
				val current = queryPersistence.requireAgentClaim(claim)
				return AgentDecisionRequest(
					agentRunId = run.id,
					instruction = run.instructionSnapshot,
					sources = tools.listAllowedSources(run.workspaceId, run.id, frozenReplay).sources,
					inputs = queryPersistence.listAgentRunInputs(run.workspaceId, run.id).map {
						AgentInputView(it.id, it.sourceScopeId, it.snapshotTitle, (it.snapshotExcerpt ?: it.snapshotBody).take(MAX_MODEL_EXCERPT))
					},
					completedSteps = queryPersistence.listSteps(run.workspaceId, run.id)
						.filter { it.status == AgentStepStatus.SUCCEEDED || it.status == AgentStepStatus.FAILED }
						.map { AgentStepView(it.sequence, it.toolName, if (it.toolName == "GET_SKILL") it.resultJson else it.resultJson?.take(MAX_MODEL_STEP_RESULT)) },
					remainingModelCalls = (budget.maxModelCalls - current.modelCallCount).coerceAtLeast(0),
					remainingToolCalls = (budget.maxToolCalls - current.toolCallCount).coerceAtLeast(0),
					selectedSkillIds = FrozenSkills.read(run.skillsSnapshotJson).map { it.id },
					conversation = conversation,
					responseMode = responseMode,
					model = selectedModel,
					routingProvider = routingProvider,
					reasoningEffort = reasoningEffort,
				)
			}
			override fun execute(decision: AgentDecision): String {
				check(!finished) { "Agent already handed off its artifact" }
				checkAccess()
				val current = queryPersistence.requireAgentClaim(claim)
				val context = context()
				val arguments = try {
					validateDecision(decision, context.sources.map { it.id }.toSet(), context.inputs.map { it.id }.toSet())
				} catch (failure: InvalidAgentDecisionException) {
					rejectInvalidDecision(claim, current, decision, failure, budget, retainClaim = true)
					return objectMapper.writeValueAsString(mapOf("error" to failure.message))
				}
				val creationAction = decision.action == AgentDecisionAction.CREATE_ARTIFACT
				val step = executionPersistence.reserveStep(claim, AgentStepRequest(
					agentRunId = run.id, sequence = current.currentStep,
					kind = if (creationAction) AgentStepKind.ARTIFACT_HANDOFF else AgentStepKind.READ_TOOL,
					status = AgentStepStatus.RUNNING,
					idempotencyKey = "agent:${run.id}:step:${current.currentStep}",
					toolName = decision.action.takeUnless { it == AgentDecisionAction.CREATE_ARTIFACT }?.name,
					argumentsJson = objectMapper.writeValueAsString(arguments), startedAt = clock.instant(),
				), budget.maxToolCalls, clock.instant())
				executeStep(claim, current, step, budget, frozenReplay, retainClaim = true)
				finished = creationAction
				if (finished) return "Artifact workflow started"
				return objectMapper.writeValueAsString(mapOf(
					"result" to queryPersistence.findStep(run.workspaceId, run.id, step.id)?.resultJson,
					"inputs" to context().inputs,
				))
			}
		}
		val result = runtime.run(host)
		if (!finished) {
			if (!result.completed) {
				executionPersistence.releaseRuntime(claim)
				return
			}
			if (responseMode == AgentResponseMode.ARTIFACT_REQUIRED) {
				throw AgentDecisionException("AGENT_NO_ARTIFACT", false, "Agent ended without creating an artifact")
			}
			executionPersistence.succeedChatResponse(
				claim,
				result.responseText ?: throw AgentDecisionException("AGENT_EMPTY_RESPONSE", false, "Agent ended without a response"),
				clock.instant(),
			)
		}
	}

	private fun executionSettings(run: AgentRunRecord) = snapshots.findEnvelopeForAgentRun(run.workspaceId, run.id)
		?.generationSettingsJson
		?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
		?: objectMapper.createObjectNode()

	private fun executeStep(
		claim: ClaimedAgentRun,
		run: AgentRunRecord,
		step: AgentStepRecord,
		budget: AgentBudgetSnapshot,
		frozenReplay: Boolean = snapshots.isFrozenReplay(run.workspaceId, run.id),
		retainClaim: Boolean = false,
	) {
		val arguments = objectMapper.readValue(step.argumentsJson, AgentStepArguments::class.java)
		workspaceAccessService.requireWritable(run.workspaceId)
		when (arguments.action) {
			AgentDecisionAction.LIST_AVAILABLE_SKILLS, AgentDecisionAction.GET_SKILL -> {
				val catalog = FrozenSkills.read(run.skillCatalogJson)
				val result = if (arguments.action == AgentDecisionAction.LIST_AVAILABLE_SKILLS) {
					objectMapper.writeValueAsString(mapOf("summary" to "Listed ${catalog.size} available skills",
						"skills" to catalog.map { mapOf("id" to it.id, "name" to it.name, "description" to it.description, "revision" to it.revision) }))
				} else {
					val skill = catalog.firstOrNull { it.id == arguments.skillId }
						?: throw AgentToolAccessException("SKILL_NOT_ALLOWED")
					objectMapper.writeValueAsString(skill)
				}
				executionPersistence.completeToolStep(retainClaim = retainClaim, claim = claim, stepId = step.id,
					resultJson = result, maxEvidenceCharacters = budget.maxEvidenceCharacters, now = clock.instant())
			}
			AgentDecisionAction.LIST_ALLOWED_SOURCES -> {
				val result = tools.listAllowedSources(run.workspaceId, run.id, frozenReplay)
				executionPersistence.completeToolStep(
					retainClaim = retainClaim,
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
					frozenReplay,
				)
				executionPersistence.completeToolStep(
					retainClaim = retainClaim,
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
				val result = tools.readWritingBlock(run.workspaceId, run.id, sourceScopeId, writingBlockId, frozenReplay)
				val adopted = requireNotNull(result.adoptedInput)
				executionPersistence.completeToolStep(
					retainClaim = retainClaim,
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
				val selected = if (executionPolicy.isReleaseRun(run.workspaceId, run.id)) {
					// Model-selected tool results cannot replace or expand a canonical release range.
					allInputs.values.filter { it.inputKind == AgentRunInputKind.SEED }.sortedBy { it.orderIndex }
				} else arguments.selectedInputIds.map { id ->
					allInputs[id] ?: throw IllegalArgumentException("Selected Agent input is unavailable")
				}
				val evidenceCharacters = selected.sumOf { it.snapshotTitle.orEmpty().length + it.snapshotBody.length }
				if (evidenceCharacters > budget.maxEvidenceCharacters) {
					throw AgentRunBudgetExceededException("AGENT_EVIDENCE_LIMIT")
				}
				val workflow = artifactWorkflowRunService.createForAgent(
					principal = WorkspacePrincipal(run.workspaceId, run.createdByUserId),
					agentRun = run.copy(skillsSnapshotJson = loadedSkills(run)),
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

	private fun loadedSkills(run: AgentRunRecord): String {
		val loadedIds = queryPersistence.listSteps(run.workspaceId, run.id)
			.filter { it.toolName == "GET_SKILL" && it.status == AgentStepStatus.SUCCEEDED }
			.mapNotNull { objectMapper.readTree(it.resultJson).get("id")?.stringValue() }.toSet()
		val selected = FrozenSkills.read(run.skillsSnapshotJson)
		val supporting = FrozenSkills.read(run.skillCatalogJson).filter { it.id.toString() in loadedIds }
		return objectMapper.writeValueAsString((selected + supporting).distinctBy { it.id })
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
		retainClaim: Boolean = false,
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
			retainClaim = retainClaim,
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
		AgentDecisionAction.LIST_ALLOWED_SOURCES, AgentDecisionAction.LIST_AVAILABLE_SKILLS -> AgentStepArguments(decision.action)
		AgentDecisionAction.GET_SKILL -> AgentStepArguments(decision.action, skillId = decision.skillId ?: throw InvalidAgentDecisionException("Skill id is required"))
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
		val skillId: UUID? = null,
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
