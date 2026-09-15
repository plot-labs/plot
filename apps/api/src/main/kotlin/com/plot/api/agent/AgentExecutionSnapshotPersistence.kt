package com.plot.api.agent

import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.SqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

/** Frozen settings and tool results used to reproduce an execution. Legacy table names are retained. */
@Repository
class AgentExecutionSnapshotPersistence(
	private val sqlExecutor: SqlExecutor,
	private val uuidGenerator: UuidGenerator,
	private val objectMapper: ObjectMapper,
) {
	fun copyTranscript(workspaceId: UUID, targetEnvelopeId: UUID, newEnvelopeId: UUID, now: Instant) {
		sqlExecutor.update(
			"""
			insert into chat_execution_transcript_entries (
			  id, workspace_id, envelope_id, call_index, tool_name, normalized_arguments, bounded_result, adopted_input_hash, created_at
			)
			select gen_random_uuid(), workspace_id, ?, call_index, tool_name, normalized_arguments, bounded_result, adopted_input_hash, ?
			from chat_execution_transcript_entries
			where workspace_id = ? and envelope_id = ?
			""".trimIndent(),
			newEnvelopeId,
			Timestamp.from(now),
			workspaceId,
			targetEnvelopeId,
		)
	}

	fun insertEnvelope(
		id: UUID,
		workspaceId: UUID,
		agentRunId: UUID,
		fingerprintVersion: Int,
		fingerprint: String,
		settingsJson: String,
		sourceSnapshotId: UUID?,
		now: Instant,
	) {
		sqlExecutor.update(
			"""
			insert into chat_execution_envelopes (
				id, workspace_id, agent_run_id, fingerprint_version, envelope_fingerprint,
				generation_settings, source_snapshot_id, created_at
			) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
			on conflict (workspace_id, agent_run_id) do nothing
			""".trimIndent(),
			id, workspaceId, agentRunId, fingerprintVersion, fingerprint, settingsJson, sourceSnapshotId, Timestamp.from(now),
		)
	}

	fun findEnvelopeForAgentRun(workspaceId: UUID, agentRunId: UUID): AgentExecutionEnvelope? = sqlExecutor.query(
		"""
		select id, workspace_id, agent_run_id, fingerprint_version, envelope_fingerprint, generation_settings, source_snapshot_id, created_at
		from chat_execution_envelopes
		where workspace_id = ? and agent_run_id = ?
		""".trimIndent(),
		{ rs, _ ->
			AgentExecutionEnvelope(
				id = requireNotNull(rs.getObject("id", UUID::class.java)),
				workspaceId = requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
				agentRunId = requireNotNull(rs.getObject("agent_run_id", UUID::class.java)),
				fingerprintVersion = rs.getInt("fingerprint_version"),
				envelopeFingerprint = requireNotNull(rs.getString("envelope_fingerprint")),
				generationSettingsJson = rs.getString("generation_settings") ?: "{}",
				sourceSnapshotId = rs.getObject("source_snapshot_id", UUID::class.java),
				createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
			)
		},
		workspaceId,
		agentRunId,
	).firstOrNull()

	fun isFrozenReplay(workspaceId: UUID, agentRunId: UUID): Boolean =
		sqlExecutor.queryForObject(
			"select exists(select 1 from chat_response_versions where workspace_id = ? and agent_run_id = ? and lineage_parent_version_id is not null)",
			Boolean::class.java, workspaceId, agentRunId,
		) == true
	fun recordTranscriptEntry(
		workspaceId: UUID,
		agentRunId: UUID,
		callIndex: Int,
		toolName: String,
		argumentsJson: String,
		resultJson: String,
		adoptedInputHash: String? = null,
		now: Instant = Instant.now(),
	) {
		val envelopeId = sqlExecutor.queryForObject(
			"select id from chat_execution_envelopes where workspace_id = ? and agent_run_id = ?",
			UUID::class.java,
			workspaceId,
			agentRunId,
		) ?: return

		val transcriptId = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into chat_execution_transcript_entries (
				id, workspace_id, envelope_id, call_index, tool_name, normalized_arguments,
				bounded_result, adopted_input_hash, created_at
			) values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
			on conflict (workspace_id, envelope_id, call_index) do nothing
			""".trimIndent(),
			transcriptId,
			workspaceId,
			envelopeId,
			callIndex,
			toolName,
			argumentsJson,
			resultJson,
			adoptedInputHash,
			Timestamp.from(now),
		)
	}

	fun linkSourceSnapshot(workspaceId: UUID, agentRunId: UUID, sourceSnapshotId: UUID) {
		sqlExecutor.update(
			"update chat_execution_envelopes set source_snapshot_id = ? where workspace_id = ? and agent_run_id = ? and source_snapshot_id is null",
			sourceSnapshotId,
			workspaceId,
			agentRunId,
		)
	}

	fun hasCompleteFrozenEnvelope(
		workspaceId: UUID,
		agentRunId: UUID,
		envelope: AgentExecutionEnvelope?,
	): Boolean {
		if (
			envelope == null ||
			envelope.envelopeFingerprint.isBlank() ||
			envelope.sourceSnapshotId == null
		) return false
		val settings = runCatching { objectMapper.readTree(envelope.generationSettingsJson) }.getOrNull() ?: return false
		if (settings !is tools.jackson.databind.node.ObjectNode) return false
		val requiredSettings = setOf(
			"promptVersion",
			"toolPolicyVersion",
			"budgetSnapshot",
			"contentType",
			"contentBriefSnapshot",
		)
		if (requiredSettings.any { !settings.has(it) }) return false
		return sqlExecutor.queryForObject(
			"""
			select exists(
			  select 1
			  from content_source_snapshots snapshot
			  where snapshot.workspace_id = ? and snapshot.id = ?
			    and jsonb_typeof(snapshot.inputs_snapshot) = 'array'
			)
			and (
			  select count(*) from agent_run_inputs input
			  where input.workspace_id = ? and input.agent_run_id = ? and input.input_kind = 'SEED'
			) >= coalesce((
			  select jsonb_array_length(snapshot.inputs_snapshot)
			  from content_source_snapshots snapshot
			  where snapshot.workspace_id = ? and snapshot.id = ?
			), 0)
			and coalesce((
			  select bool_and(
			    entry.tool_name <> '' and jsonb_typeof(entry.normalized_arguments) = 'object'
			      and jsonb_typeof(entry.bounded_result) = 'object'
			  )
			  from chat_execution_transcript_entries entry
			  where entry.workspace_id = ? and entry.envelope_id = ?
				), true)
				and not exists (
				  select 1 from agent_run_sources source
				  where source.workspace_id = ? and source.agent_run_id = ?
				    and coalesce(nullif(trim(source.source_display_name), ''), '') = ''
				)
				""".trimIndent(),
			Boolean::class.java,
			workspaceId,
			envelope.sourceSnapshotId,
			workspaceId,
			agentRunId,
			workspaceId,
			envelope.sourceSnapshotId,
				workspaceId,
				envelope.id,
				workspaceId,
				agentRunId,
		) == true
	}

	fun matchingTranscriptEntry(
		workspaceId: UUID,
		agentRunId: UUID,
		matches: (tools.jackson.databind.JsonNode, String) -> Boolean,
	): tools.jackson.databind.JsonNode? = sqlExecutor.query(
		"""
		select entry.tool_name, entry.normalized_arguments::text, entry.bounded_result::text
		from chat_execution_transcript_entries entry
		join chat_execution_envelopes envelope
		  on envelope.workspace_id = entry.workspace_id and envelope.id = entry.envelope_id
		where envelope.workspace_id = ? and envelope.agent_run_id = ?
		order by entry.call_index
		""".trimIndent(),
		{ rs, _ -> Triple(
			requireNotNull(rs.getString(1)),
			objectMapper.readTree(requireNotNull(rs.getString(2))),
			objectMapper.readTree(requireNotNull(rs.getString(3))),
		) },
		workspaceId,
		agentRunId,
	).firstOrNull { (toolName, arguments, _) -> matches(arguments, toolName) }?.third

}

data class AgentExecutionEnvelope(
	val id: UUID,
	val workspaceId: UUID,
	val agentRunId: UUID,
	val fingerprintVersion: Int,
	val envelopeFingerprint: String,
	val generationSettingsJson: String,
	val sourceSnapshotId: UUID?,
	val createdAt: Instant,
)
