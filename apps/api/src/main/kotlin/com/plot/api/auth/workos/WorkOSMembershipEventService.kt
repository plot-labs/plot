package com.plot.api.auth.workos

import com.fasterxml.jackson.databind.JsonNode
import com.plot.api.auth.WorkOSAuthProperties
import com.plot.api.common.ApiException
import com.workos.webhooks.Webhook
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SignatureException
import java.time.Duration
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

enum class WorkOSMembershipEventDisposition { PROCESSED, DUPLICATE, IGNORED, RETRY, DEAD_LETTER }

data class ParsedWorkOSMembershipEvent(
	val eventId: String,
	val eventType: String,
	val payloadHash: String,
	val membershipId: String?,
	val organizationId: String?,
	val userId: String?,
	val status: String?,
	val roleSlug: String?,
)

@Service
class WorkOSMembershipEventService(
	private val properties: WorkOSAuthProperties,
	private val inbox: WorkOSMembershipEventInbox,
	private val membershipGateway: WorkOSMembershipGateway,
	private val projection: WorkOSMembershipProjectionService,
) {
	private val webhook = Webhook()

	fun handle(rawBody: String, signature: String?): WorkOSMembershipEventDisposition {
		val secret = properties.webhookSecret.trim().takeIf { it.isNotBlank() }
			?: throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKOS_WEBHOOK_NOT_CONFIGURED", "WorkOS webhook processing is not configured")
		if (signature.isNullOrBlank()) invalidSignature()
		val payload = try {
			webhook.constructEvent(rawBody, signature, secret)
		} catch (_: SignatureException) {
			invalidSignature()
		}
		val event = parse(payload, rawBody)
		val receipt = try {
			inbox.recordIfNew(
				eventId = event.eventId,
				eventType = event.eventType,
				payloadHash = event.payloadHash,
				workOSMembershipId = event.membershipId,
				workOSOrganizationId = event.organizationId,
				workOSUserId = event.userId,
				now = Instant.now(),
			)
		} catch (_: WorkOSMembershipEventPayloadMismatchException) {
			throw ApiException(
				HttpStatus.BAD_REQUEST,
				"WORKOS_WEBHOOK_INVALID",
				"WorkOS webhook payload is invalid",
			)
		}
		if (receipt.duplicate && receipt.record.state in setOf(
			WorkOSMembershipEventState.PROCESSED,
			WorkOSMembershipEventState.IGNORED,
		)) return WorkOSMembershipEventDisposition.DUPLICATE

		if (event.eventType !in SUPPORTED_EVENTS) {
			inbox.markIgnored(event.eventId, Instant.now(), "UNSUPPORTED_EVENT")
			return WorkOSMembershipEventDisposition.IGNORED
		}
		if (event.membershipId.isNullOrBlank() || event.organizationId.isNullOrBlank() || event.userId.isNullOrBlank()) {
			inbox.markDeadLetter(event.eventId, Instant.now(), "INVALID_MEMBERSHIP_EVENT")
			return WorkOSMembershipEventDisposition.DEAD_LETTER
		}

		return try {
			// Re-fetching the current resource makes a delayed grant unable to
			// overwrite a later revoke or downgrade.
			val current = membershipGateway.find(event.organizationId, event.userId)
			if (current != null) {
				projection.reconcile(current)
			} else {
				projection.reconcile(WorkOSMembershipSnapshot(
					id = event.membershipId,
					organizationId = event.organizationId,
					userId = event.userId,
					status = "inactive",
					roleSlug = event.roleSlug,
				))
			}
			inbox.markProcessed(event.eventId, Instant.now())
			WorkOSMembershipEventDisposition.PROCESSED
		} catch (failure: WorkOSProviderException) {
			retryOrDeadLetter(event.eventId, failure)
		} catch (failure: RuntimeException) {
			retryOrDeadLetter(event.eventId, failure)
		}
	}

	private fun retryOrDeadLetter(eventId: String, failure: Throwable): WorkOSMembershipEventDisposition {
		val current = inbox.findByEventId(eventId)
			?: return WorkOSMembershipEventDisposition.DEAD_LETTER
		val now = Instant.now()
		if (current.attemptCount + 1 >= MAX_ATTEMPTS) {
			inbox.markDeadLetter(eventId, now, failure.safeCode())
			return WorkOSMembershipEventDisposition.DEAD_LETTER
		}
		val delay = RETRY_DELAY.multipliedBy(1L shl current.attemptCount.coerceIn(0, 5))
		inbox.markRetry(eventId, now, now.plus(delay), failure.safeCode())
		return WorkOSMembershipEventDisposition.RETRY
	}

	private fun parse(payload: JsonNode, rawBody: String): ParsedWorkOSMembershipEvent {
		val data = payload.path("data")
		return ParsedWorkOSMembershipEvent(
			eventId = requiredText(payload, "id"),
			eventType = requiredText(payload, "event"),
			payloadHash = sha256(rawBody),
			membershipId = optionalText(data, "id"),
			organizationId = optionalText(data, "organization_id"),
			userId = optionalText(data, "user_id"),
			status = optionalText(data, "status"),
			roleSlug = optionalText(data.path("role"), "slug")
				?: data.path("roles").firstOrNull()?.path("slug")?.asText()?.takeIf { it.isNotBlank() },
		)
	}

	private fun requiredText(node: JsonNode, field: String): String {
		return optionalText(node, field) ?: throw ApiException(HttpStatus.BAD_REQUEST, "WORKOS_WEBHOOK_INVALID", "WorkOS webhook payload is invalid")
	}

	private fun optionalText(node: JsonNode, field: String): String? = node.path(field).asText(null)?.trim()?.takeIf { it.isNotBlank() }

	private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(StandardCharsets.UTF_8))
		.joinToString("") { byte -> "%02x".format(byte) }

	private fun invalidSignature(): Nothing = throw ApiException(
		HttpStatus.BAD_REQUEST,
		"WORKOS_WEBHOOK_INVALID",
		"WorkOS webhook signature is invalid",
	)

	private fun Throwable.safeCode(): String = when (this) {
		is WorkOSProviderException -> "WORKOS_PROVIDER_UNAVAILABLE"
		is WorkOSMembershipEventPayloadMismatchException -> "WORKOS_EVENT_PAYLOAD_MISMATCH"
		else -> "WORKOS_MEMBERSHIP_PROJECTION_FAILED"
	}

	private companion object {
		val SUPPORTED_EVENTS = setOf(
			"organization_membership.created",
			"organization_membership.updated",
			"organization_membership.deleted",
		)
		const val MAX_ATTEMPTS = 5
		val RETRY_DELAY: Duration = Duration.ofSeconds(30)
	}
}
