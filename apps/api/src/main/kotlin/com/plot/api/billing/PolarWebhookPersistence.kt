package com.plot.api.billing

import com.plot.api.persistence.SqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class PolarWebhookPersistence(
	private val sql: SqlExecutor,
) {
	@Transactional
	fun recordIfNew(
		webhookId: String,
		eventType: String,
		subscriptionId: String?,
		receivedAt: Instant,
	): Boolean = sql.update(
		"""
		insert into polar_webhook_events (webhook_id, event_type, subscription_id, received_at, outcome)
		values (?, ?, ?, ?, 'IGNORED')
		on conflict (webhook_id) do nothing
		""".trimIndent(),
		webhookId, eventType, subscriptionId, Timestamp.from(receivedAt),
	) == 1

	@Transactional
	fun recordOutcome(
		webhookId: String,
		outcome: String,
		matchedUserId: UUID?,
		matchedWorkspaceId: UUID?,
	) {
		sql.update(
			"update polar_webhook_events set outcome = ?, matched_user_id = ?, matched_workspace_id = ? where webhook_id = ?",
			outcome, matchedUserId, matchedWorkspaceId, webhookId,
		)
	}
}
