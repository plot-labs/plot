package com.plot.api.autonomy.signal

import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.SqlRow
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository

data class SignalEvaluationRecord(
	val id: UUID,
	val workspaceId: UUID,
	val signalId: UUID,
	val sourceNamespaceId: UUID,
	val sourceScopeId: UUID,
	val inputFingerprint: String,
	val outcome: String,
	val reason: String,
	val semanticTime: Instant,
	val admittedResponseVersionId: UUID?,
	val claimToken: UUID?,
	val leaseUntil: Instant?,
	val attempts: Int,
	val lastErrorCode: String?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

@Repository
class SignalEvaluationPersistence(
	private val sql: SqlExecutor,
	private val uuidGenerator: UuidGenerator,
	@Value("\${plot.autonomy.signal-evaluator.enabled:false}")
	val signalEvaluatorEnabled: Boolean = false,
	@Value("\${plot.autonomy.new-execution-writer.enabled:false}")
	val newExecutionWriterEnabled: Boolean = false,
) {
	init {
		// Enforce invariant: legacy and new execution writers cannot both be active together
		// When newExecutionWriterEnabled is true, legacy admission writer must be disabled.
	}

	fun assertWriterInvariants(legacyWriterActive: Boolean) {
		check(!(legacyWriterActive && newExecutionWriterEnabled)) {
			"Old and new execution-admission writers cannot both be active together"
		}
	}

	fun recordEvaluation(
		workspaceId: UUID,
		signalId: UUID,
		sourceNamespaceId: UUID,
		sourceScopeId: UUID,
		inputFingerprint: String,
		outcome: String,
		reason: String,
		semanticTime: Instant,
		admittedResponseVersionId: UUID? = null,
		now: Instant = Instant.now(),
	): UUID {
		val id = uuidGenerator.next()
		sql.update(
			"""
			insert into signal_evaluations (
				id, workspace_id, signal_id, source_namespace_id, source_scope_id, input_fingerprint,
				outcome, reason, semantic_time, admitted_response_version_id, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			on conflict (workspace_id, signal_id) do update
			set input_fingerprint = excluded.input_fingerprint,
			    outcome = excluded.outcome,
			    reason = excluded.reason,
			    semantic_time = excluded.semantic_time,
			    admitted_response_version_id = coalesce(excluded.admitted_response_version_id, signal_evaluations.admitted_response_version_id),
			    updated_at = excluded.updated_at
			""".trimIndent(),
			id,
			workspaceId,
			signalId,
			sourceNamespaceId,
			sourceScopeId,
			inputFingerprint,
			outcome,
			reason,
			Timestamp.from(semanticTime),
			admittedResponseVersionId,
			Timestamp.from(now),
			Timestamp.from(now),
		)
		return sql.queryForObject(
			"select id from signal_evaluations where workspace_id = ? and signal_id = ?",
			UUID::class.java,
			workspaceId,
			signalId,
		) ?: id
	}

	/**
	 * Converges every durable GitHub release observation for [tagName] once its
	 * release draft has admitted an AgentRun. A signal projection may run before
	 * admission, so this callback is the authoritative admission-side link.
	 */
	fun recordReleaseAdmission(
		workspaceId: UUID,
		sourceScopeId: UUID,
		tagName: String,
		agentRunId: UUID,
		now: Instant = Instant.now(),
	): Int {
		val responseVersionId = requireNotNull(sql.queryForObject(
			"select id from chat_response_versions where workspace_id = ? and agent_run_id = ?",
			UUID::class.java,
			workspaceId,
			agentRunId,
		)) { "Admitted release AgentRun must have a Chat response version" }
		val signals = sql.query(
			"""
			select id, source_namespace_id, source_version, received_at
			from autonomy_signals
			where workspace_id = ? and source_scope_id = ? and provider = 'GITHUB'
			  and object_key = ?
			""".trimIndent(),
			{ row, _ ->
				ReleaseSignal(
					id = requireNotNull(row.getObject("id", UUID::class.java)),
					sourceNamespaceId = requireNotNull(row.getObject("source_namespace_id", UUID::class.java)),
					sourceVersion = row.getTimestamp("source_version")?.toInstant(),
					semanticTime = row.getTimestamp("source_version")?.toInstant()
						?: requireNotNull(row.getTimestamp("received_at")).toInstant(),
				)
			},
			workspaceId,
			sourceScopeId,
			"release:$tagName",
		)
		signals.forEach { signal ->
			recordEvaluation(
				workspaceId = workspaceId,
				signalId = signal.id,
				sourceNamespaceId = signal.sourceNamespaceId,
				sourceScopeId = sourceScopeId,
				inputFingerprint = fingerprint(sourceScopeId, tagName, signal.sourceVersion),
				outcome = "ADMITTED",
				reason = "Release draft admitted: $tagName",
				semanticTime = signal.semanticTime,
				admittedResponseVersionId = responseVersionId,
				now = now,
			)
		}
		return signals.size
	}

	fun findBySignalId(workspaceId: UUID, signalId: UUID): SignalEvaluationRecord? = sql.query(
		"select * from signal_evaluations where workspace_id = ? and signal_id = ?",
		::mapRecord,
		workspaceId,
		signalId,
	).firstOrNull()

	fun find(workspaceId: UUID, id: UUID): SignalEvaluationRecord? = sql.query(
		"select * from signal_evaluations where workspace_id = ? and id = ?",
		::mapRecord,
		workspaceId,
		id,
	).firstOrNull()

	fun list(workspaceId: UUID): List<SignalEvaluationRecord> = sql.query(
		"select * from signal_evaluations where workspace_id = ? order by semantic_time desc, id desc",
		::mapRecord,
		workspaceId,
	)

	private fun mapRecord(row: SqlRow, index: Int): SignalEvaluationRecord = SignalEvaluationRecord(
		id = requireNotNull(row.getObject("id", UUID::class.java)),
		workspaceId = requireNotNull(row.getObject("workspace_id", UUID::class.java)),
		signalId = requireNotNull(row.getObject("signal_id", UUID::class.java)),
		sourceNamespaceId = requireNotNull(row.getObject("source_namespace_id", UUID::class.java)),
		sourceScopeId = requireNotNull(row.getObject("source_scope_id", UUID::class.java)),
		inputFingerprint = requireNotNull(row.getString("input_fingerprint")),
		outcome = requireNotNull(row.getString("outcome")),
		reason = requireNotNull(row.getString("reason")),
		semanticTime = requireNotNull(row.getTimestamp("semantic_time")).toInstant(),
		admittedResponseVersionId = row.getObject("admitted_response_version_id", UUID::class.java),
		claimToken = row.getObject("claim_token", UUID::class.java),
		leaseUntil = row.getTimestamp("lease_until")?.toInstant(),
		attempts = row.getInt("attempts"),
		lastErrorCode = row.getString("last_error_code"),
		createdAt = requireNotNull(row.getTimestamp("created_at")).toInstant(),
		updatedAt = requireNotNull(row.getTimestamp("updated_at")).toInstant(),
	)

	private fun fingerprint(sourceScopeId: UUID, tagName: String, sourceVersion: Instant?): String =
		MessageDigest.getInstance("SHA-256")
			.digest("$sourceScopeId:release:$tagName:$sourceVersion".toByteArray())
			.joinToString("") { "%02x".format(it) }

	private data class ReleaseSignal(
		val id: UUID,
		val sourceNamespaceId: UUID,
		val sourceVersion: Instant?,
		val semanticTime: Instant,
	)
}
