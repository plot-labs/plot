package com.plot.api.github

import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.SqlRow
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.stereotype.Component

/** Owns webhook delivery idempotency and disposition state. */
@Component
class GitHubWebhookDeliveryPersistence(
	private val sqlExecutor: JooqSqlExecutor,
	private val clock: Clock = Clock.systemUTC(),
) : GitHubWebhookDeliveryStore {
	override fun insertDelivery(delivery: GitHubWebhookDelivery): GitHubWebhookDelivery {
		return sqlExecutor.query(
			"""
			insert into github_webhook_deliveries (
			 id, external_delivery_id, event_type, event_action, installation_id, repository_id, ref,
			 before_sha, after_sha, tag_name, ref_created, ref_deleted, forced, payload_hash,
			 disposition, error_code, received_at, processed_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			on conflict (external_delivery_id) do nothing
			returning id, external_delivery_id, event_type, event_action, installation_id, repository_id, ref,
			 before_sha, after_sha, tag_name, ref_created, ref_deleted, forced, payload_hash, disposition,
			 error_code, received_at, processed_at
			""".trimIndent(),
			{ rs, _ -> rs.toDelivery() },
			delivery.id, delivery.externalDeliveryId, delivery.eventType, delivery.eventAction,
			delivery.installationId, delivery.repositoryId, delivery.ref, delivery.beforeSha, delivery.afterSha,
			delivery.tagName, delivery.refCreated, delivery.refDeleted, delivery.forced, delivery.payloadHash,
			delivery.disposition.name, delivery.errorCode, Timestamp.from(delivery.receivedAt),
			delivery.processedAt?.let(Timestamp::from),
		).firstOrNull() ?: findDelivery(delivery.externalDeliveryId)
			?: throw IllegalStateException("GitHub webhook delivery was not found after a conflicted insert")
	}

	override fun findDelivery(externalDeliveryId: String): GitHubWebhookDelivery? = sqlExecutor.query(
		"""
		select id, external_delivery_id, event_type, event_action, installation_id, repository_id, ref,
			before_sha, after_sha, tag_name, ref_created, ref_deleted, forced, payload_hash, disposition,
			error_code, received_at, processed_at
		from github_webhook_deliveries where external_delivery_id = ?
		""".trimIndent(),
		{ rs, _ -> rs.toDelivery() },
		externalDeliveryId,
	).firstOrNull()

	override fun findDelivery(id: UUID): GitHubWebhookDelivery? = sqlExecutor.query(
		"""
		select id, external_delivery_id, event_type, event_action, installation_id, repository_id, ref,
			before_sha, after_sha, tag_name, ref_created, ref_deleted, forced, payload_hash, disposition,
			error_code, received_at, processed_at
		from github_webhook_deliveries where id = ?
		""".trimIndent(),
		{ rs, _ -> rs.toDelivery() },
		id,
	).firstOrNull()

	override fun markDelivery(id: UUID, disposition: GitHubWebhookDisposition, errorCode: String?) {
		val updated = sqlExecutor.update(
			"""
			update github_webhook_deliveries
			set disposition = ?, error_code = ?, processed_at = ?
			where id = ?
			""".trimIndent(),
			disposition.name,
			errorCode,
			Timestamp.from(clock.instant()),
			id,
		)
		requireExactlyOne(updated, "Webhook delivery was not found")
	}

	private fun requireExactlyOne(updated: Int, message: String) {
		if (updated != 1) throw InvalidDataAccessApiUsageException(message)
	}
}

internal fun SqlRow.toDelivery(): GitHubWebhookDelivery = GitHubWebhookDelivery(
	id = requireNotNull(getObject("id", UUID::class.java)),
	externalDeliveryId = requireNotNull(getString("external_delivery_id")),
	eventType = requireNotNull(getString("event_type")),
	eventAction = getString("event_action"),
	installationId = getObject("installation_id") as Long?,
	repositoryId = getObject("repository_id") as Long?,
	ref = getString("ref"),
	beforeSha = getString("before_sha"),
	afterSha = getString("after_sha"),
	tagName = getString("tag_name"),
	refCreated = getObject("ref_created") as Boolean?,
	refDeleted = getObject("ref_deleted") as Boolean?,
	forced = getObject("forced") as Boolean?,
	payloadHash = requireNotNull(getString("payload_hash")),
	disposition = GitHubWebhookDisposition.valueOf(requireNotNull(getString("disposition"))),
	errorCode = getString("error_code"),
	receivedAt = requireNotNull(getTimestamp("received_at")).toInstant(),
	processedAt = getTimestamp("processed_at")?.toInstant(),
)
