package com.plot.api.billing

import com.plot.api.auth.workos.WorkOSIdentityMappingRepository
import com.plot.api.common.ApiException
import com.plot.api.workspace.User
import com.plot.api.workspace.UserRepository
import com.plot.api.workspace.Workspace
import com.plot.api.workspace.WorkspaceMemberRepository
import com.plot.api.workspace.WorkspaceRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Service
class PolarSubscriptionService(
	private val objectMapper: ObjectMapper,
	private val webhookPersistence: PolarWebhookPersistence,
	private val userRepository: UserRepository,
	private val workspaceRepository: WorkspaceRepository,
	private val memberRepository: WorkspaceMemberRepository,
	private val workOSIdentityMappingRepository: WorkOSIdentityMappingRepository,
	private val clock: Clock = Clock.systemUTC(),
) {
	@Transactional
	fun handle(webhookId: String, rawBody: String) {
		val payload = parse(rawBody)
		val eventType = string(payload.path("type"))?.takeIf { it.isNotBlank() }
			?: invalidPayload()
		val data = payload.path("data")
		val subscriptionId = string(data.path("id"))?.takeIf { it.isNotBlank() }
		if (!webhookPersistence.recordIfNew(webhookId, eventType, subscriptionId, clock.instant()) || eventType !in HANDLED_EVENTS) return
		if (subscriptionId == null) {
			recordOutcome(webhookId, "UNMATCHED", null)
			logger.warn("Polar subscription event could not be matched: subscription_id=missing")
			return
		}

		val target = resolveTarget(subscriptionId, data)
		if (target == null) {
			recordOutcome(webhookId, "UNMATCHED", null)
			logger.warn("Polar subscription event could not be matched: subscription_id={}", subscriptionId)
			return
		}
		val eventAt = eventTimestamp(payload)
		if (eventAt == null) {
			recordOutcome(webhookId, "INVALID_TIMESTAMP", target)
			logger.warn("Polar subscription event has no valid lifecycle timestamp: subscription_id={}", subscriptionId)
			return
		}

		when (eventType) {
			"subscription.active", "subscription.uncanceled" ->
				promote(webhookId, subscriptionId, data, eventAt, target)
			"subscription.canceled" -> refreshCanceled(webhookId, subscriptionId, data, eventAt, target)
			"subscription.past_due" -> refreshPastDue(webhookId, subscriptionId, data, eventAt, target)
			"subscription.updated" -> refreshUpdated(webhookId, subscriptionId, data, eventAt, target)
			"subscription.revoked" -> demote(webhookId, subscriptionId, data, eventAt, target)
		}
	}

	private fun promote(
		webhookId: String,
		subscriptionId: String,
		data: JsonNode,
		eventAt: Instant,
		target: BillingTarget,
	) {
		if (!canPromote(target.workspace, subscriptionId)) {
			recordOutcome(webhookId, "STALE_SUBSCRIPTION", target)
			return
		}
		if (isStaleEvent(target.workspace, subscriptionId, eventAt)) {
			recordOutcome(webhookId, "STALE_EVENT", target)
			return
		}
		val now = clock.instant()
		target.workspace.plan = "founding"
		target.workspace.entitlementStatus = "active"
		target.workspace.accessMode = "full"
		target.workspace.polarSubscriptionId = subscriptionId
		target.workspace.polarCustomerId = customerId(data) ?: target.workspace.polarCustomerId
		target.workspace.polarSubscriptionStatus = subscriptionStatus(data) ?: "active"
		target.workspace.polarSubscriptionCancelAtPeriodEnd = false
		target.workspace.polarSubscriptionCurrentPeriodEnd = currentPeriodEnd(data)
		target.workspace.polarSubscriptionEventAt = eventAt
		target.workspace.planUpdatedAt = now
		target.workspace.updatedAt = now
		workspaceRepository.save(target.workspace)
		recordOutcome(webhookId, "PROMOTED", target)
	}

	private fun refreshCanceled(
		webhookId: String,
		subscriptionId: String,
		data: JsonNode,
		eventAt: Instant,
		target: BillingTarget,
	) {
		if (!canRefresh(target.workspace, subscriptionId, eventAt, webhookId, target)) return
		val scheduled = boolean(data, "cancel_at_period_end") == true
		applySnapshot(
			target.workspace,
			status = "canceled",
			cancelAtPeriodEnd = scheduled,
			currentPeriodEnd = if (scheduled) currentPeriodEnd(data) else null,
			eventAt = eventAt,
		)
		workspaceRepository.save(target.workspace)
		recordOutcome(webhookId, "SNAPSHOT_UPDATED", target)
	}

	private fun refreshPastDue(
		webhookId: String,
		subscriptionId: String,
		data: JsonNode,
		eventAt: Instant,
		target: BillingTarget,
	) {
		if (!canRefresh(target.workspace, subscriptionId, eventAt, webhookId, target)) return
		applySnapshot(
			target.workspace,
			status = "past_due",
			cancelAtPeriodEnd = boolean(data, "cancel_at_period_end") ?: target.workspace.polarSubscriptionCancelAtPeriodEnd,
			currentPeriodEnd = currentPeriodEnd(data) ?: target.workspace.polarSubscriptionCurrentPeriodEnd,
			eventAt = eventAt,
		)
		workspaceRepository.save(target.workspace)
		recordOutcome(webhookId, "SNAPSHOT_UPDATED", target)
	}

	private fun refreshUpdated(
		webhookId: String,
		subscriptionId: String,
		data: JsonNode,
		eventAt: Instant,
		target: BillingTarget,
	) {
		if (!canRefresh(target.workspace, subscriptionId, eventAt, webhookId, target)) return
		applySnapshot(
			target.workspace,
			status = subscriptionStatus(data) ?: target.workspace.polarSubscriptionStatus,
			cancelAtPeriodEnd = boolean(data, "cancel_at_period_end") ?: target.workspace.polarSubscriptionCancelAtPeriodEnd,
			currentPeriodEnd = currentPeriodEnd(data) ?: target.workspace.polarSubscriptionCurrentPeriodEnd,
			eventAt = eventAt,
		)
		workspaceRepository.save(target.workspace)
		recordOutcome(webhookId, "SNAPSHOT_UPDATED", target)
	}

	private fun demote(
		webhookId: String,
		subscriptionId: String,
		data: JsonNode,
		eventAt: Instant,
		target: BillingTarget,
	) {
		if (target.workspace.polarSubscriptionId != subscriptionId) {
			recordOutcome(webhookId, "STALE_SUBSCRIPTION", target)
			return
		}
		if (isStaleEvent(target.workspace, subscriptionId, eventAt)) {
			recordOutcome(webhookId, "STALE_EVENT", target)
			return
		}
		val now = clock.instant()
		target.workspace.entitlementStatus = "revoked"
		target.workspace.accessMode = "read_only"
		target.workspace.polarSubscriptionStatus = subscriptionStatus(data) ?: "revoked"
		target.workspace.polarSubscriptionCancelAtPeriodEnd = false
		target.workspace.polarSubscriptionCurrentPeriodEnd = null
		target.workspace.polarSubscriptionEventAt = eventAt
		target.workspace.planUpdatedAt = now
		target.workspace.updatedAt = now
		workspaceRepository.save(target.workspace)
		recordOutcome(webhookId, "DEMOTED", target)
	}

	private fun canPromote(workspace: Workspace, subscriptionId: String): Boolean =
		workspace.polarSubscriptionId == null ||
			workspace.polarSubscriptionId == subscriptionId ||
			workspace.entitlementStatus == "revoked"

	private fun canRefresh(
		workspace: Workspace,
		subscriptionId: String,
		eventAt: Instant,
		webhookId: String,
		target: BillingTarget,
	): Boolean {
		if (workspace.polarSubscriptionId != subscriptionId) {
			recordOutcome(webhookId, "STALE_SUBSCRIPTION", target)
			return false
		}
		if (isStaleEvent(workspace, subscriptionId, eventAt)) {
			recordOutcome(webhookId, "STALE_EVENT", target)
			return false
		}
		return true
	}

	private fun isStaleEvent(workspace: Workspace, subscriptionId: String, eventAt: Instant): Boolean =
		workspace.polarSubscriptionId == subscriptionId &&
			workspace.polarSubscriptionEventAt?.let { !eventAt.isAfter(it) } == true

	private fun applySnapshot(
		workspace: Workspace,
		status: String?,
		cancelAtPeriodEnd: Boolean?,
		currentPeriodEnd: Instant?,
		eventAt: Instant,
	) {
		workspace.polarSubscriptionStatus = status
		workspace.polarSubscriptionCancelAtPeriodEnd = cancelAtPeriodEnd
		workspace.polarSubscriptionCurrentPeriodEnd = currentPeriodEnd
		workspace.polarSubscriptionEventAt = eventAt
		workspace.updatedAt = clock.instant()
	}

	private fun resolveTarget(subscriptionId: String, data: JsonNode): BillingTarget? {
		val exactWorkspace = workspaceRepository.findByPolarSubscriptionId(subscriptionId)
		if (exactWorkspace != null) return BillingTarget(owner(exactWorkspace), exactWorkspace)
		return resolveTarget(data)
	}

	private fun resolveTarget(data: JsonNode): BillingTarget? {
		val referenceId = text(data, "metadata", "reference_id")
			?: text(data, "metadata", "referenceId")
		resolveReference(referenceId)?.let { return it }

		val externalId = text(data, "customer", "external_id")
		resolveExternalWorkspace(externalId)?.let { workspace ->
			return BillingTarget(owner(workspace), workspace)
		}
		resolveExternalCustomer(externalId)?.let { return targetForUser(it) }

		val email = text(data, "customer", "email")?.trim()?.lowercase()
		val emailUser = email?.let(userRepository::findByEmailIgnoreCase)
		return emailUser?.let(::targetForUser)
	}

	private fun resolveReference(referenceId: String?): BillingTarget? {
		val id = referenceId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
		workspaceRepository.findByIdAndStatus(id, "ACTIVE")?.let { workspace ->
			return BillingTarget(owner(workspace), workspace)
		}
		return userRepository.findById(id).orElse(null)?.let(::targetForUser)
	}

	private fun resolveExternalCustomer(externalId: String?): User? {
		if (externalId.isNullOrBlank()) return null
		val plotUserId = runCatching { UUID.fromString(externalId) }.getOrNull()
		if (plotUserId != null) userRepository.findById(plotUserId).orElse(null)?.let { return it }
		return workOSIdentityMappingRepository.findByWorkOSUserId(externalId)
			?.let { mapping -> userRepository.findById(mapping.plotUserId).orElse(null) }
	}

	private fun resolveExternalWorkspace(externalId: String?): Workspace? {
		val workspaceId = externalId
			?.removePrefix("plot-workspace:")
			?.takeIf { it != externalId }
			?.let { runCatching { UUID.fromString(it) }.getOrNull() }
			?: return null
		return workspaceRepository.findByIdAndStatus(workspaceId, "ACTIVE")
	}

	private fun targetForUser(user: User): BillingTarget? {
		val workspaces = memberRepository
			.findAllByUserIdAndStatusOrderByCreatedAtAsc(user.id, "ACTIVE")
			.asSequence()
			.mapNotNull { membership -> workspaceRepository.findByIdAndStatus(membership.workspaceId, "ACTIVE") }
			.toList()
		if (workspaces.size != 1) return null
		val workspace = workspaces.single()
		return BillingTarget(user, workspace)
	}

	private fun owner(workspace: Workspace): User? = workspace.createdByUserId
		?.let { userId -> userRepository.findById(userId).orElse(null) }

	private fun customerId(data: JsonNode): String? =
		text(data, "customer", "id") ?: string(data.path("customer_id"))

	private fun subscriptionStatus(data: JsonNode): String? = string(data.path("status"))

	private fun currentPeriodEnd(data: JsonNode): Instant? =
		string(data.path("current_period_end"))
			?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }

	private fun eventTimestamp(payload: JsonNode): Instant? =
		string(payload.path("timestamp"))
			?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }

	private fun boolean(node: JsonNode, vararg path: String): Boolean? {
		var current = node
		path.forEach { segment -> current = current.path(segment) }
		return current.takeUnless { it.isMissingNode || it.isNull || !it.isBoolean }?.booleanValue()
	}

	private fun text(node: JsonNode, vararg path: String): String? {
		var current = node
		path.forEach { segment -> current = current.path(segment) }
		return string(current)?.takeIf { it.isNotBlank() }
	}

	private fun string(node: JsonNode): String? =
		node.takeUnless { it.isMissingNode || it.isNull }?.stringValue()

	private fun recordOutcome(webhookId: String, outcome: String, target: BillingTarget?) {
		webhookPersistence.recordOutcome(
			webhookId = webhookId,
			outcome = outcome,
			matchedUserId = target?.user?.id,
			matchedWorkspaceId = target?.workspace?.id,
		)
	}

	private fun parse(rawBody: String): JsonNode = try {
		objectMapper.readTree(rawBody)
	} catch (_: RuntimeException) {
		invalidPayload()
	}

	private fun invalidPayload(): Nothing = throw ApiException(
		HttpStatus.BAD_REQUEST,
		"INVALID_POLAR_WEBHOOK_PAYLOAD",
		"Polar webhook payload is invalid",
	)

	private data class BillingTarget(
		val user: User?,
		val workspace: Workspace,
	)

	private companion object {
		val logger = LoggerFactory.getLogger(PolarSubscriptionService::class.java)
		val HANDLED_EVENTS = setOf(
			"subscription.active",
			"subscription.uncanceled",
			"subscription.updated",
			"subscription.canceled",
			"subscription.past_due",
			"subscription.revoked",
		)
	}
}
