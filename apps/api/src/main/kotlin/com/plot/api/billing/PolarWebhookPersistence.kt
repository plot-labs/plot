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
		receipt: PolarWebhookReceipt,
		receivedAt: Instant,
	): Boolean = sql.update(
		"""
		insert into polar_webhook_events (
			webhook_id, event_type, resource_id, subscription_id, order_id, refund_id,
			polar_customer_id, polar_product_id, checkout_id, event_at, received_at,
			resource_status, amount, refunded_amount, currency, billing_reason, revoke_benefits, outcome
		)
		values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IGNORED')
		on conflict (webhook_id) do nothing
		""".trimIndent(),
		webhookId,
		eventType,
		receipt.resourceId,
		receipt.subscriptionId,
		receipt.orderId,
		receipt.refundId,
		receipt.polarCustomerId,
		receipt.productId,
		receipt.checkoutId,
		receipt.eventAt?.let(Timestamp::from),
		Timestamp.from(receivedAt),
		receipt.status,
		receipt.amount,
		receipt.refundedAmount,
		receipt.currency,
		receipt.billingReason,
		receipt.revokeBenefits,
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

data class PolarWebhookReceipt(
	val resourceId: String?,
	val subscriptionId: String?,
	val orderId: String?,
	val refundId: String?,
	val polarCustomerId: String?,
	val productId: String?,
	val checkoutId: String?,
	val eventAt: Instant?,
	val status: String?,
	val amount: Long?,
	val refundedAmount: Long?,
	val currency: String?,
	val billingReason: String?,
	val revokeBenefits: Boolean?,
)
