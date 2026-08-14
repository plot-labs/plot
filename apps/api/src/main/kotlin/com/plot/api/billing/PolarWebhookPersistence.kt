package com.plot.api.billing

import com.plot.api.persistence.generated.tables.PolarWebhookEvents.Companion.POLAR_WEBHOOK_EVENTS
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

@Repository
class PolarWebhookPersistence(
	private val dsl: DSLContext,
) {
	fun recordIfNew(
		webhookId: String,
		eventType: String,
		subscriptionId: String?,
		receivedAt: Instant,
	): Boolean = dsl
		.insertInto(POLAR_WEBHOOK_EVENTS)
		.set(POLAR_WEBHOOK_EVENTS.WEBHOOK_ID, webhookId)
		.set(POLAR_WEBHOOK_EVENTS.EVENT_TYPE, eventType)
		.set(POLAR_WEBHOOK_EVENTS.SUBSCRIPTION_ID, subscriptionId)
		.set(POLAR_WEBHOOK_EVENTS.RECEIVED_AT, receivedAt.atOffset(ZoneOffset.UTC))
		.set(POLAR_WEBHOOK_EVENTS.OUTCOME, "IGNORED")
		.onConflict(POLAR_WEBHOOK_EVENTS.WEBHOOK_ID)
		.doNothing()
		.execute() == 1

	fun recordOutcome(
		webhookId: String,
		outcome: String,
		matchedUserId: UUID?,
		matchedWorkspaceId: UUID?,
	) {
		dsl.update(POLAR_WEBHOOK_EVENTS)
			.set(POLAR_WEBHOOK_EVENTS.OUTCOME, outcome)
			.set(POLAR_WEBHOOK_EVENTS.MATCHED_USER_ID, matchedUserId)
			.set(POLAR_WEBHOOK_EVENTS.MATCHED_WORKSPACE_ID, matchedWorkspaceId)
			.where(POLAR_WEBHOOK_EVENTS.WEBHOOK_ID.eq(webhookId))
			.execute()
	}
}
