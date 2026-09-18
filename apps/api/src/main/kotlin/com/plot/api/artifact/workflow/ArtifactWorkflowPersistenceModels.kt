package com.plot.api.artifact.workflow

import com.plot.api.ai.provider.ModelRole
import com.plot.api.ai.provider.ProviderUsage
import com.plot.api.billing.AiBillingBasis
import java.math.BigDecimal
import java.util.UUID

data class ArtifactWorkflowRunReservation(
	val workspaceId: UUID,
	val createdByUserId: UUID,
	val sourceScopeId: UUID?,
	val idempotencyKey: String,
	val requestFingerprint: String,
	val state: ArtifactWorkflowState,
	val provider: String,
	val modelName: String,
	val budgetJson: String,
	val workSessionId: UUID? = null,
	val agentRunId: UUID? = null,
	val artifactRunId: UUID? = null,
)

data class ClaimedArtifactWorkflowRun(
	val workspaceId: UUID,
	val runId: UUID,
	val transitionVersion: Long,
	val workerId: String,
)

data class ModelInvocationLease(
	val id: UUID,
	val stepId: UUID,
	val role: ModelRole,
	val logicalCallIndex: Int,
	val attemptNo: Int,
)

data class ArtifactModelInvocationSettlement(
	val id: UUID,
	val workspaceId: UUID,
	val generationRunId: UUID,
	val workflowStepId: UUID,
	val role: ModelRole,
	val logicalCallIndex: Int,
	val attemptNo: Int,
	val usage: ProviderUsage,
	val providerCostUsd: BigDecimal,
	val credits: Long,
	val billingBasis: AiBillingBasis,
	val pricePolicyVersion: String,
	val failureCode: String?,
	val latencyMillis: Int?,
)

class ArtifactModelInvocationBlockedException :
	IllegalStateException("Workspace already has unresolved AI usage")

class ArtifactWorkflowIdempotencyConflictException : IllegalStateException("Idempotency key was reused with different inputs")
class ArtifactWorkflowRunNotFoundException(val runId: UUID) : IllegalStateException("ArtifactWorkflow run not found")
