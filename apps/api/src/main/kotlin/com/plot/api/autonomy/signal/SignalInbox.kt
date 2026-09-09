package com.plot.api.autonomy.signal

import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.SqlRow
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class SignalInbox(private val sql: JooqSqlExecutor) {
	@Transactional
	fun accept(envelope: SignalEnvelope, now: Instant): SignalReceipt {
		val id = UUID.randomUUID()
		val inserted = sql.update(
			"""insert into autonomy_signals
			(id, workspace_id, source_namespace_id, source_scope_id, provider, delivery_key, object_key,
			 event_type, schema_version, source_version, tombstone, payload, received_at, available_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
			on conflict (workspace_id, source_namespace_id, source_scope_id, delivery_key) do nothing""",
			id, envelope.workspaceId, envelope.sourceNamespaceId, envelope.sourceScopeId, envelope.provider,
			envelope.deliveryKey, envelope.objectKey, envelope.eventType, envelope.schemaVersion,
			envelope.sourceVersion?.let(Timestamp::from), envelope.tombstone, envelope.payload, Timestamp.from(now), Timestamp.from(now),
		) == 1
		if (!inserted) {
			val existing = sql.queryForObject(
				"""select id from autonomy_signals where workspace_id = ? and source_namespace_id = ?
				and source_scope_id = ? and delivery_key = ? and provider = ? and object_key = ? and event_type = ?
				and source_version is not distinct from ?::timestamptz and tombstone = ? and payload = ?::jsonb""",
				UUID::class.java, envelope.workspaceId, envelope.sourceNamespaceId, envelope.sourceScopeId, envelope.deliveryKey,
				envelope.provider, envelope.objectKey, envelope.eventType, envelope.sourceVersion?.let(Timestamp::from), envelope.tombstone, envelope.payload,
			)
			require(existing != null) { "Delivery key reused with different signal content" }
			return SignalReceipt(existing, false)
		}
		// Unordered observations remain separate evidence; they cannot supersede authoritative object state.
		if (envelope.sourceVersion == null) return SignalReceipt(id, true)
		val advanced = sql.update(
			"""insert into autonomy_signal_heads
			(workspace_id, source_namespace_id, source_scope_id, object_key, signal_id, source_version, tombstone)
			values (?, ?, ?, ?, ?, ?, ?)
			on conflict (workspace_id, source_namespace_id, source_scope_id, object_key) do update
			set signal_id = excluded.signal_id, source_version = excluded.source_version, tombstone = excluded.tombstone
			where excluded.source_version > autonomy_signal_heads.source_version
			or (excluded.source_version = autonomy_signal_heads.source_version and excluded.tombstone and not autonomy_signal_heads.tombstone)""",
			envelope.workspaceId, envelope.sourceNamespaceId, envelope.sourceScopeId, envelope.objectKey,
			id, envelope.sourceVersion?.let(Timestamp::from), envelope.tombstone,
		) == 1
		if (!advanced) sql.update("update autonomy_signals set state = 'SUPERSEDED' where id = ?", id)
		return SignalReceipt(id, true)
	}

	/** Poll per provider to allow the scheduler to enforce independent provider budgets. */
	fun claim(provider: String, now: Instant, lease: Duration, maxAttempts: Int = 5): SignalClaim? {
		require(!lease.isNegative && !lease.isZero && maxAttempts > 0)
		val token = UUID.randomUUID()
		return sql.queryForObject(
			"""with candidate as (
			 select id from autonomy_signals where provider = ? and attempts < ? and
			 ((state in ('PENDING','RETRY_WAIT') and available_at <= ?) or (state = 'PROCESSING' and lease_until <= ?))
			 order by available_at, received_at, id for update skip locked limit 1
			) update autonomy_signals s set state = 'PROCESSING', claim_token = ?, lease_until = ?, attempts = attempts + 1
			from candidate c where s.id = c.id returning s.*""",
			{ row, _ -> SignalClaim(row.uuid("id"), token, row.getInt("attempts"), row.envelope()) },
			provider, maxAttempts, Timestamp.from(now), Timestamp.from(now), token, Timestamp.from(now.plus(lease)),
		)
	}

	/** Must run inside the consumer's transaction before any domain writes; locks the object head. */
	@Transactional
	fun isCurrent(claim: SignalClaim, now: Instant): Boolean {
		if (claim.envelope.sourceVersion == null) return sql.query(
			"""select id from autonomy_signals where workspace_id = ? and id = ? and claim_token = ?
			and state = 'PROCESSING' and lease_until > ? and source_version is null for update""",
			claim.envelope.workspaceId, claim.id, claim.token, Timestamp.from(now),
		).isNotEmpty()
		return sql.query(
			"""select h.signal_id from autonomy_signal_heads h join autonomy_signals s
			on s.workspace_id = h.workspace_id and s.id = h.signal_id
			where s.workspace_id = ? and s.id = ? and s.claim_token = ? and s.state = 'PROCESSING'
			and s.lease_until > ? for update of h, s""",
			claim.envelope.workspaceId, claim.id, claim.token, Timestamp.from(now),
		).isNotEmpty()
	}

	fun finish(claim: SignalClaim, now: Instant, superseded: Boolean = false): Boolean = sql.update(
		"""update autonomy_signals set state = ?, claim_token = null, lease_until = null
		where workspace_id = ? and id = ? and state = 'PROCESSING' and claim_token = ? and lease_until > ?""",
		if (superseded) "SUPERSEDED" else "SUCCEEDED", claim.envelope.workspaceId, claim.id, claim.token, Timestamp.from(now),
	) == 1

	fun retry(claim: SignalClaim, now: Instant, retryAt: Instant, errorCode: String, maxAttempts: Int = 5): Boolean {
		require(errorCode.matches(Regex("[A-Z0-9_]{1,100}")) && retryAt >= now && maxAttempts > 0)
		return sql.update(
			"""update autonomy_signals set state = ?, available_at = ?, last_error_code = ?, claim_token = null, lease_until = null
			where workspace_id = ? and id = ? and state = 'PROCESSING' and claim_token = ? and lease_until > ?""",
			if (claim.attempt >= maxAttempts) "FAILED" else "RETRY_WAIT", Timestamp.from(retryAt), errorCode,
			claim.envelope.workspaceId, claim.id, claim.token, Timestamp.from(now),
		) == 1
	}

	/** A crash on the last allowed attempt must not leave an unclaimable PROCESSING row. */
	fun failExhausted(provider: String, now: Instant, maxAttempts: Int = 5): Int {
		require(maxAttempts > 0)
		return sql.update(
			"""update autonomy_signals set state = 'FAILED', claim_token = null, lease_until = null,
			last_error_code = 'ATTEMPTS_EXHAUSTED' where provider = ? and attempts >= ?
			and ((state = 'PROCESSING' and lease_until <= ?) or state in ('PENDING','RETRY_WAIT'))""",
			provider, maxAttempts, Timestamp.from(now),
		)
	}
}

private fun SqlRow.uuid(name: String): UUID = requireNotNull(getObject(name, UUID::class.java))
private fun SqlRow.envelope() = SignalEnvelope(
	workspaceId = uuid("workspace_id"), sourceNamespaceId = uuid("source_namespace_id"), sourceScopeId = uuid("source_scope_id"),
	provider = requireNotNull(getString("provider")), deliveryKey = requireNotNull(getString("delivery_key")),
	objectKey = requireNotNull(getString("object_key")), eventType = requireNotNull(getString("event_type")),
	sourceVersion = getTimestamp("source_version")?.toInstant(), payload = requireNotNull(getString("payload")),
	tombstone = getBoolean("tombstone"), schemaVersion = getInt("schema_version"),
)
