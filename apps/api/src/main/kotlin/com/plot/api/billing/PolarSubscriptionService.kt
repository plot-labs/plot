package com.plot.api.billing

import com.plot.api.auth.PlotAuthProperties
import com.plot.api.common.ApiException
import com.plot.api.common.JdbcTime.timestamp
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Service
class PolarSubscriptionService(
	private val objectMapper: ObjectMapper,
	private val jdbcTemplate: JdbcTemplate,
	private val userRepository: UserRepository,
	private val workspaceRepository: WorkspaceRepository,
	private val memberRepository: WorkspaceMemberRepository,
	private val authProperties: PlotAuthProperties,
	private val clock: Clock = Clock.systemUTC(),
) {
	@Transactional
	fun handle(webhookId: String, rawBody: String) {
		val payload = parse(rawBody)
		val eventType = string(payload.path("type"))?.takeIf { it.isNotBlank() }
			?: invalidPayload()
		val data = payload.path("data")
		val subscriptionId = string(data.path("id"))?.takeIf { it.isNotBlank() }
		val inserted = jdbcTemplate.update(
			"""
			insert into polar_webhook_events (
			  webhook_id, event_type, subscription_id, received_at, outcome
			) values (?, ?, ?, ?, 'IGNORED')
			on conflict (webhook_id) do nothing
			""".trimIndent(),
			webhookId,
			eventType,
			subscriptionId,
			timestamp(clock.instant()),
		)
		if (inserted == 0 || eventType !in HANDLED_EVENTS) return
		if (subscriptionId == null) {
			recordOutcome(webhookId, "UNMATCHED", null)
			logger.warn("Polar subscription event could not be matched: subscription_id=missing")
			return
		}

		val target = if (eventType == "subscription.revoked") {
			resolveRevokedTarget(subscriptionId, data)
		} else {
			resolveTarget(data)
		}
		if (target == null) {
			recordOutcome(webhookId, "UNMATCHED", null)
			logger.warn("Polar subscription event could not be matched: subscription_id={}", subscriptionId)
			return
		}

		when (eventType) {
			"subscription.active", "subscription.uncanceled" ->
				promote(webhookId, subscriptionId, data, target)
			"subscription.revoked" -> demote(webhookId, subscriptionId, target)
		}
	}

	private fun promote(
		webhookId: String,
		subscriptionId: String,
		data: JsonNode,
		target: BillingTarget,
	) {
		val now = clock.instant()
		target.workspace.plan = "founding"
		target.workspace.entitlementStatus = "active"
		target.workspace.accessMode = "full"
		target.workspace.polarSubscriptionId = subscriptionId
		target.workspace.polarCustomerId = customerId(data)
		target.workspace.planUpdatedAt = now
		target.workspace.updatedAt = now
		workspaceRepository.save(target.workspace)
		recordOutcome(webhookId, "PROMOTED", target)
	}

	private fun demote(webhookId: String, subscriptionId: String, target: BillingTarget) {
		if (target.workspace.polarSubscriptionId != subscriptionId) {
			recordOutcome(webhookId, "STALE_SUBSCRIPTION", target)
			return
		}
		val now = clock.instant()
		target.workspace.entitlementStatus = "revoked"
		target.workspace.accessMode = "read_only"
		target.workspace.planUpdatedAt = now
		target.workspace.updatedAt = now
		workspaceRepository.save(target.workspace)
		recordOutcome(webhookId, "DEMOTED", target)
	}

	private fun resolveRevokedTarget(subscriptionId: String, data: JsonNode): BillingTarget? {
		val exactWorkspace = workspaceRepository.findByPolarSubscriptionId(subscriptionId)
		if (exactWorkspace != null) return BillingTarget(owner(exactWorkspace), exactWorkspace)
		return resolveTarget(data)
	}

	private fun resolveTarget(data: JsonNode): BillingTarget? {
		val referenceId = text(data, "metadata", "reference_id")
			?: text(data, "metadata", "referenceId")
		resolveReference(referenceId)?.let { return it }

		val externalId = text(data, "customer", "external_id")
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
		return userRepository.findByAuthIssuerAndAuthSubject(authProperties.issuer, externalId)
	}

	private fun targetForUser(user: User): BillingTarget? {
		val workspace = memberRepository
			.findAllByUserIdAndStatusOrderByCreatedAtAsc(user.id, "ACTIVE")
			.asSequence()
			.mapNotNull { membership -> workspaceRepository.findByIdAndStatus(membership.workspaceId, "ACTIVE") }
			.firstOrNull()
			?: return null
		return BillingTarget(user, workspace)
	}

	private fun owner(workspace: Workspace): User? = workspace.createdByUserId
		?.let { userId -> userRepository.findById(userId).orElse(null) }

	private fun customerId(data: JsonNode): String? =
		text(data, "customer", "id") ?: string(data.path("customer_id"))

	private fun text(node: JsonNode, vararg path: String): String? {
		var current = node
		path.forEach { segment -> current = current.path(segment) }
		return string(current)?.takeIf { it.isNotBlank() }
	}

	private fun string(node: JsonNode): String? =
		node.takeUnless { it.isMissingNode || it.isNull }?.stringValue()

	private fun recordOutcome(webhookId: String, outcome: String, target: BillingTarget?) {
		jdbcTemplate.update(
			"""
			update polar_webhook_events
			set outcome = ?, matched_user_id = ?, matched_workspace_id = ?
			where webhook_id = ?
			""".trimIndent(),
			outcome,
			target?.user?.id,
			target?.workspace?.id,
			webhookId,
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
			"subscription.revoked",
		)
	}
}
