package com.plot.api.autonomy.signal

import com.plot.api.persistence.SqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

data class ActivityItem(
	val id: UUID,
	val sourceScopeId: UUID,
	val signalId: UUID?,
	val responseVersionId: UUID?,
	val agentRunId: UUID?,
	val chatId: UUID?,
	val artifactId: UUID?,
	val title: String,
	val status: String,
	val reason: String,
	val semanticTime: Instant,
	val updatedAt: Instant,
)

data class ActivityPage(
	val items: List<ActivityItem>,
	val nextCursor: String?,
	val hasMore: Boolean,
	val highWaterMark: Instant,
)

@Service
class SignalActivityProjectionService(
	private val sql: SqlExecutor,
) {
	fun projectActivity(
		workspaceId: UUID,
		visibleScopeIds: Set<UUID>,
		limit: Int = 20,
		cursor: String? = null,
		highWaterMark: Instant? = null,
	): ActivityPage {
		val hwm = highWaterMark ?: Instant.now()
		if (visibleScopeIds.isEmpty()) {
			return ActivityPage(emptyList(), null, false, hwm)
		}

		val scopePlaceholders = visibleScopeIds.joinToString(",") { "?" }
		val (cursorInstant, cursorId) = if (cursor != null) {
			val parts = cursor.split("_")
			if (parts.size == 2) {
				val cursorEpoch = parts[0].toLongOrNull()
				val id = try { UUID.fromString(parts[1]) } catch (_: Exception) { null }
				if (cursorEpoch != null && id != null) {
					Instant.ofEpochMilli(cursorEpoch) to id
				} else null to null
			} else null to null
		} else null to null

		val evalArgs = mutableListOf<Any>(workspaceId)
		evalArgs.addAll(visibleScopeIds)
		evalArgs.add(Timestamp.from(hwm))

		val evalCursorClause = if (cursorInstant != null && cursorId != null) {
			evalArgs.add(Timestamp.from(cursorInstant))
			evalArgs.add(Timestamp.from(cursorInstant))
			evalArgs.add(cursorId)
			"and (e.semantic_time < ? or (e.semantic_time = ? and e.id < ?))"
		} else ""
		evalArgs.add(limit + 1)

		val evalItems = sql.query(
			"""
			select distinct on (e.semantic_time, e.id)
				e.id,
				e.source_scope_id,
				e.signal_id,
				e.admitted_response_version_id,
				v.agent_run_id,
				t.work_session_id as chat_id,
				r.status as run_status,
				a.id as artifact_id,
				a.status as artifact_status,
				s.object_key,
				e.outcome,
				e.reason,
				e.semantic_time,
				e.updated_at
			from signal_evaluations e
			join autonomy_signals s on s.workspace_id = e.workspace_id and s.id = e.signal_id
			left join chat_response_versions v on v.workspace_id = e.workspace_id and v.id = e.admitted_response_version_id
			left join chat_turns t on t.workspace_id = v.workspace_id and t.id = v.turn_id
			left join agent_runs r on r.workspace_id = e.workspace_id and r.id = v.agent_run_id
			left join artifact_runs ar on ar.workspace_id = e.workspace_id and ar.agent_run_id = r.id
			left join generation_runs g on g.workspace_id = e.workspace_id and g.artifact_run_id = ar.id
			left join content_packs a on a.workspace_id = e.workspace_id and a.generation_run_id = g.id
			where e.workspace_id = ?
			  and e.source_scope_id in ($scopePlaceholders)
			  and e.semantic_time <= ?
			  $evalCursorClause
			order by e.semantic_time desc, e.id desc
			limit ?
			""".trimIndent(),
			{ rs, _ ->
				val outcome = rs.getString("outcome") ?: "NO_GENERATION"
				val runStatus = rs.getString("run_status")
				val artifactStatus = rs.getString("artifact_status")
				val status = mapSignalStatus(outcome, runStatus, artifactStatus)
				val objectKey = rs.getString("object_key") ?: ""
				val title = if (objectKey.startsWith("release:")) {
					"Release ${objectKey.removePrefix("release:")}"
				} else if (objectKey.isNotBlank()) {
					objectKey
				} else {
					"Source update"
				}

				ActivityItem(
					id = requireNotNull(rs.getObject("id", UUID::class.java)),
					sourceScopeId = requireNotNull(rs.getObject("source_scope_id", UUID::class.java)),
					signalId = rs.getObject("signal_id", UUID::class.java),
					responseVersionId = rs.getObject("admitted_response_version_id", UUID::class.java),
					agentRunId = rs.getObject("agent_run_id", UUID::class.java),
					chatId = rs.getObject("chat_id", UUID::class.java),
					artifactId = rs.getObject("artifact_id", UUID::class.java),
					title = title,
					status = status,
					reason = rs.getString("reason") ?: "",
					semanticTime = requireNotNull(rs.getTimestamp("semantic_time")).toInstant(),
					updatedAt = requireNotNull(rs.getTimestamp("updated_at")).toInstant(),
				)
			},
			*evalArgs.toTypedArray(),
		)

		val provArgs = mutableListOf<Any>(workspaceId)
		provArgs.addAll(visibleScopeIds)
		provArgs.add(Timestamp.from(hwm))

		val provCursorClause = if (cursorInstant != null && cursorId != null) {
			provArgs.add(Timestamp.from(cursorInstant))
			provArgs.add(Timestamp.from(cursorInstant))
			provArgs.add(cursorId)
			"and (p.semantic_time < ? or (p.semantic_time = ? and p.id < ?))"
		} else ""
		provArgs.add(limit + 1)

		val provItems = sql.query(
			"""
			select distinct on (p.semantic_time, p.id)
				p.id,
				p.source_scope_id,
				p.agent_run_id,
				p.chat_id,
				r.status as run_status,
				a.id as artifact_id,
				a.status as artifact_status,
				p.title,
				p.disposition,
				p.reason,
				p.dismissed,
				p.last_error_code,
				p.semantic_time,
				p.created_at
			from legacy_activity_provenance p
			left join agent_runs r on r.workspace_id = p.workspace_id and r.id = p.agent_run_id
			left join artifact_runs ar on ar.workspace_id = p.workspace_id and ar.agent_run_id = r.id
			left join generation_runs g on g.workspace_id = p.workspace_id and g.artifact_run_id = ar.id
			left join content_packs a on a.workspace_id = p.workspace_id and a.generation_run_id = g.id
			where p.workspace_id = ?
			  and p.source_scope_id in ($scopePlaceholders)
			  and p.semantic_time <= ?
			  $provCursorClause
			order by p.semantic_time desc, p.id desc
			limit ?
			""".trimIndent(),
			{ rs, _ ->
				val disposition = rs.getString("disposition") ?: "NO_GENERATION"
				val dismissed = rs.getBoolean("dismissed")
				val lastErrorCode = rs.getString("last_error_code")
				val runStatus = rs.getString("run_status")
				val artifactStatus = rs.getString("artifact_status")
				val status = mapProvenanceStatus(disposition, dismissed, lastErrorCode, runStatus, artifactStatus)

				ActivityItem(
					id = requireNotNull(rs.getObject("id", UUID::class.java)),
					sourceScopeId = requireNotNull(rs.getObject("source_scope_id", UUID::class.java)),
					signalId = null,
					responseVersionId = null,
					agentRunId = rs.getObject("agent_run_id", UUID::class.java),
					chatId = rs.getObject("chat_id", UUID::class.java),
					artifactId = rs.getObject("artifact_id", UUID::class.java),
					title = rs.getString("title") ?: "Legacy Activity",
					status = status,
					reason = rs.getString("reason") ?: "",
					semanticTime = requireNotNull(rs.getTimestamp("semantic_time")).toInstant(),
					updatedAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
				)
			},
			*provArgs.toTypedArray(),
		)

		val allItems = (evalItems + provItems).sortedWith(
			compareByDescending<ActivityItem> { it.semanticTime }.thenByDescending { it.id },
		)

		val paged = allItems.take(limit)
		val hasMore = allItems.size > limit
		val nextCursor = if (hasMore && paged.isNotEmpty()) {
			val last = paged.last()
			"${last.semanticTime.toEpochMilli()}_${last.id}"
		} else null

		return ActivityPage(
			items = paged,
			nextCursor = nextCursor,
			hasMore = hasMore,
			highWaterMark = hwm,
		)
	}

	private fun mapSignalStatus(outcome: String, runStatus: String?, artifactStatus: String?): String {
		return when (outcome) {
			"NO_GENERATION" -> "NO_UPDATE_NEEDED"
			"EXCLUDED" -> "EXCLUDED"
			"ADMITTED" -> when (runStatus) {
				"FAILED" -> "ACTION_REQUIRED"
				"SUCCEEDED" -> if (artifactStatus in setOf("READY", "NEEDS_REVIEW", "READY_FOR_REVIEW")) {
					"READY_FOR_REVIEW"
				} else if (artifactStatus != null) {
					"READY_FOR_REVIEW"
				} else {
					"NO_UPDATE_NEEDED"
				}
				"QUEUED", "RUNNING" -> "IN_PROGRESS"
				else -> "IN_PROGRESS"
			}
			else -> "NO_UPDATE_NEEDED"
		}
	}

	private fun mapProvenanceStatus(
		disposition: String,
		dismissed: Boolean,
		lastErrorCode: String?,
		runStatus: String?,
		artifactStatus: String?,
	): String {
		if (dismissed || disposition == "EXCLUDED") return "EXCLUDED"
		if (disposition == "NO_GENERATION") return "NO_UPDATE_NEEDED"
		if (runStatus == "FAILED" || (lastErrorCode != null && runStatus == null)) return "ACTION_REQUIRED"
		if (runStatus == "SUCCEEDED" && artifactStatus in setOf("READY", "NEEDS_REVIEW", "READY_FOR_REVIEW")) {
			return "READY_FOR_REVIEW"
		}
		if (runStatus in setOf("QUEUED", "RUNNING")) return "IN_PROGRESS"
		return "NO_UPDATE_NEEDED"
	}
}
