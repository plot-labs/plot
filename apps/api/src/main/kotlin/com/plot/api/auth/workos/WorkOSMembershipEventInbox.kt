package com.plot.api.auth.workos

import com.plot.api.persistence.JooqSqlExecutor
import java.sql.Timestamp
import java.time.Instant
import org.springframework.stereotype.Repository

enum class WorkOSMembershipEventState { RECEIVED, PROCESSED, IGNORED, RETRY, DEAD_LETTER }

data class WorkOSMembershipEventRecord(
	val eventId: String,
	val eventType: String,
	val payloadHash: String,
	val workOSMembershipId: String?,
	val workOSOrganizationId: String?,
	val workOSUserId: String?,
	val state: WorkOSMembershipEventState,
	val attemptCount: Int,
)

data class WorkOSMembershipEventReceipt(
	val record: WorkOSMembershipEventRecord,
	val duplicate: Boolean,
)

@Repository
class WorkOSMembershipEventInbox(
	private val sql: JooqSqlExecutor,
) {
	fun recordIfNew(
		eventId: String,
		eventType: String,
		payloadHash: String,
		workOSMembershipId: String?,
		workOSOrganizationId: String?,
		workOSUserId: String?,
		now: Instant,
	): WorkOSMembershipEventReceipt {
		val existingById = findByEventId(eventId)
		if (existingById != null) {
			if (existingById.payloadHash != payloadHash) {
				throw WorkOSMembershipEventPayloadMismatchException()
			}
			return WorkOSMembershipEventReceipt(existingById, duplicate = true)
		}
		findByPayloadHash(payloadHash)?.let { return WorkOSMembershipEventReceipt(it, duplicate = true) }

		sql.update(
			"""
			insert into workos_membership_event_inbox (
			  event_id, event_type, payload_hash, workos_membership_id,
			  workos_organization_id, workos_user_id, state, attempt_count, received_at
			) values (?, ?, ?, ?, ?, ?, 'RECEIVED', 0, ?)
			""".trimIndent(),
			eventId,
			eventType,
			payloadHash,
			workOSMembershipId,
			workOSOrganizationId,
			workOSUserId,
			Timestamp.from(now),
		)
		return WorkOSMembershipEventReceipt(requireNotNull(findByEventId(eventId)), duplicate = false)
	}

	fun markProcessed(eventId: String, now: Instant) {
		sql.update(
			"""
			update workos_membership_event_inbox
			set state = 'PROCESSED', processed_at = ?, next_attempt_at = null, last_error = null
			where event_id = ?
			""".trimIndent(),
			Timestamp.from(now), eventId,
		)
	}

	fun markIgnored(eventId: String, now: Instant, error: String? = null) {
		sql.update(
			"""
			update workos_membership_event_inbox
			set state = 'IGNORED', processed_at = ?, next_attempt_at = null, last_error = ?
			where event_id = ?
			""".trimIndent(),
			Timestamp.from(now), error?.take(200), eventId,
		)
	}

	fun markRetry(eventId: String, now: Instant, nextAttemptAt: Instant, error: String) {
		sql.update(
			"""
			update workos_membership_event_inbox
			set state = 'RETRY', attempt_count = attempt_count + 1,
			    next_attempt_at = ?, last_error = ?
			where event_id = ?
			""".trimIndent(),
			Timestamp.from(nextAttemptAt), error.take(200), eventId,
		)
	}

	fun markDeadLetter(eventId: String, now: Instant, error: String) {
		sql.update(
			"""
			update workos_membership_event_inbox
			set state = 'DEAD_LETTER', processed_at = ?, next_attempt_at = null, last_error = ?,
			    attempt_count = attempt_count + 1
			where event_id = ?
			""".trimIndent(),
			Timestamp.from(now), error.take(200), eventId,
		)
	}

	fun findByEventId(eventId: String): WorkOSMembershipEventRecord? = sql.queryForObject(
		BY_EVENT_ID,
		::toModel,
		eventId,
	)

	private fun findByPayloadHash(payloadHash: String): WorkOSMembershipEventRecord? = sql.queryForObject(
		BY_PAYLOAD_HASH,
		::toModel,
		payloadHash,
	)

	private fun toModel(row: com.plot.api.persistence.SqlRow, index: Int) = WorkOSMembershipEventRecord(
		eventId = requireNotNull(row.getString("event_id")),
		eventType = requireNotNull(row.getString("event_type")),
		payloadHash = requireNotNull(row.getString("payload_hash")),
		workOSMembershipId = row.getString("workos_membership_id"),
		workOSOrganizationId = row.getString("workos_organization_id"),
		workOSUserId = row.getString("workos_user_id"),
		state = WorkOSMembershipEventState.valueOf(requireNotNull(row.getString("state"))),
		attemptCount = row.getInt("attempt_count"),
	)

	private companion object {
		const val BY_EVENT_ID = """
			select event_id, event_type, payload_hash, workos_membership_id,
			       workos_organization_id, workos_user_id, state, attempt_count
			from workos_membership_event_inbox where event_id = ?
			"""
		const val BY_PAYLOAD_HASH = """
			select event_id, event_type, payload_hash, workos_membership_id,
			       workos_organization_id, workos_user_id, state, attempt_count
			from workos_membership_event_inbox where payload_hash = ?
			"""
	}
}

class WorkOSMembershipEventPayloadMismatchException : RuntimeException("WorkOS event payload does not match its event id")
