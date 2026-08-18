package com.plot.api.routine

import com.plot.api.persistence.JooqSqlExecutor
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class AgentRunQueryPersistence(
	private val sqlExecutor: JooqSqlExecutor,
) {
	fun findAgentRun(workspaceId: UUID, id: UUID): AgentRunRecord? = sqlExecutor.query(
		selectAgentRunSql + " where a.workspace_id = ? and a.id = ?",
		agentRunMapper,
		workspaceId,
		id,
	).firstOrNull()
	fun chatExists(workspaceId: UUID, chatId: UUID): Boolean = sqlExecutor.queryForObject(
		"select exists(select 1 from work_sessions where workspace_id = ? and id = ?)",
		Boolean::class.java,
		workspaceId,
		chatId,
	) ?: false
	fun listChatAgentRuns(workspaceId: UUID, chatId: UUID): List<AgentRunRecord> = sqlExecutor.query(
		selectAgentRunSql + " where a.workspace_id = ? and a.work_session_id = ? and a.origin = 'CHAT' order by a.created_at, a.id",
		agentRunMapper,
		workspaceId,
		chatId,
	)
	fun findChatAgentRunByIdempotencyKey(
		workspaceId: UUID,
		idempotencyKey: String,
		forUpdate: Boolean = false,
	): AgentRunRecord? = sqlExecutor.query(
		selectAgentRunSql + " where a.workspace_id = ? and a.origin = 'CHAT' and a.idempotency_key = ?" +
			if (forUpdate) " for update" else "",
		agentRunMapper,
		workspaceId,
		idempotencyKey,
	).singleOrNull()
	fun listAgentRunSources(workspaceId: UUID, agentRunId: UUID): List<AgentRunSourceRecord> = sqlExecutor.query(
		"""
		select id, workspace_id, agent_run_id, source_scope_id, source_role, order_index,
		       captured_status, captured_status_changed_at, captured_at
		from agent_run_sources
		where workspace_id = ? and agent_run_id = ?
		order by order_index, id
		""".trimIndent(),
		agentRunSourceMapper,
		workspaceId,
		agentRunId,
	)
	fun listAgentRunInputs(workspaceId: UUID, agentRunId: UUID): List<AgentRunInputRecord> = sqlExecutor.query(
		"""
		select id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
		       source_provider, source_kind, source_label,
		       input_kind, order_index, activity_sequence, snapshot_title, snapshot_body,
		       snapshot_excerpt, original_url, source_created_at, source_updated_at,
		       content_hash, captured_at
		from agent_run_inputs
		where workspace_id = ? and agent_run_id = ?
		order by order_index, id
		""".trimIndent(),
		agentRunInputMapper,
		workspaceId,
		agentRunId,
	)
	fun listSteps(workspaceId: UUID, agentRunId: UUID): List<AgentStepRecord> = sqlExecutor.query(
		"""
		select id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
		       tool_name, arguments::text, result::text, adopted_input_id, generation_run_id,
		       failure_code, started_at, finished_at, created_at
		from agent_steps
		where workspace_id = ? and agent_run_id = ?
		order by sequence, id
		""".trimIndent(),
		agentStepMapper,
		workspaceId,
		agentRunId,
	)
	fun findArtifactId(workspaceId: UUID, artifactWorkflowRunId: UUID): UUID? = sqlExecutor.query(
		"""
		select id
		from content_packs
		where workspace_id = ? and generation_run_id = ?
		""".trimIndent(),
		{ rs, _ -> rs.getObject("id", UUID::class.java) },
		workspaceId,
		artifactWorkflowRunId,
	).singleOrNull()
	fun findArtifactForAgentRun(workspaceId: UUID, agentRunId: UUID): AgentArtifactRecord? = sqlExecutor.query(
		"""
		select pack.id, pack.status, pack.title, pack.updated_at
		from artifact_runs artifact
		join generation_runs generation
		  on generation.workspace_id = artifact.workspace_id and generation.artifact_run_id = artifact.id
		join content_packs pack
		  on pack.workspace_id = generation.workspace_id and pack.generation_run_id = generation.id
		where artifact.workspace_id = ? and artifact.agent_run_id = ?
		order by pack.updated_at desc, pack.id desc
		limit 1
		""".trimIndent(),
		{ rs, _ -> AgentArtifactRecord(
			requireNotNull(rs.getObject("id", UUID::class.java)),
			requireNotNull(rs.getString("status")),
			rs.getString("title"),
			requireNotNull(rs.getTimestamp("updated_at")).toInstant(),
		) },
		workspaceId,
		agentRunId,
	).firstOrNull()
	fun loadArtifactWorkflowState(workspaceId: UUID, artifactWorkflowRunId: UUID): AgentArtifactWorkflowState? = sqlExecutor.query(
		"""
		select generation.id, generation.status,
		       exists (
		         select 1 from content_packs pack
		         where pack.workspace_id = generation.workspace_id and pack.generation_run_id = generation.id
		       ) as materialized
		from generation_runs generation
		where generation.workspace_id = ? and generation.id = ?
		""".trimIndent(),
		{ rs, _ -> AgentArtifactWorkflowState(
			requireNotNull(rs.getObject("id", UUID::class.java)),
			requireNotNull(rs.getString("status")),
			rs.getBoolean("materialized"),
		) },
		workspaceId,
		artifactWorkflowRunId,
	).singleOrNull()
	fun allAgentSourcesActive(workspaceId: UUID, agentRunId: UUID): Boolean {
		val counts = sqlExecutor.query(
			"""
			select count(*) as total,
			       count(*) filter (
			         where scope.status = 'ACTIVE' and namespace.status = 'ACTIVE'
			           and exists (
			             select 1
			             from connection_namespace_bindings binding
			             join connections connection
			               on connection.workspace_id = binding.workspace_id
			              and connection.id = binding.connection_id
			              and connection.provider = binding.provider
			              and connection.status = 'ACTIVE'
			             where binding.workspace_id = namespace.workspace_id
			               and binding.source_namespace_id = namespace.id
			               and binding.provider = namespace.provider
			               and binding.status = 'ACTIVE'
			           )
			       ) as active
			from agent_run_sources source
			join source_scopes scope
			  on scope.workspace_id = source.workspace_id and scope.id = source.source_scope_id
			join source_namespaces namespace
			  on namespace.workspace_id = scope.workspace_id and namespace.id = scope.source_namespace_id
			 and namespace.provider = scope.provider
			where source.workspace_id = ? and source.agent_run_id = ?
			""".trimIndent(),
			{ rs, _ -> rs.getInt("total") to rs.getInt("active") },
			workspaceId,
			agentRunId,
		).single()
		return counts.first > 0 && counts.first == counts.second
	}
	fun findRunningStep(workspaceId: UUID, agentRunId: UUID, sequence: Int): AgentStepRecord? =
		findStepBySequence(workspaceId, agentRunId, sequence)?.takeIf { it.status == AgentStepStatus.RUNNING }
	internal fun requireAgentClaim(claim: ClaimedAgentRun): AgentRunRecord {
		val run = sqlExecutor.query(
			selectAgentRunSql + """

			where a.workspace_id = ? and a.id = ? and a.claimed_by = ? and a.transition_version = ?
			  and a.status = 'RUNNING'
			for update
			""".trimIndent(),
			agentRunMapper,
			claim.workspaceId,
			claim.agentRunId,
			claim.workerId,
			claim.transitionVersion,
		).singleOrNull()
		return run ?: throw AgentRunClaimLostException()
	}
	internal fun findStepBySequence(workspaceId: UUID, agentRunId: UUID, sequence: Int): AgentStepRecord? =
		sqlExecutor.query(
			"""
			select id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
			       tool_name, arguments::text, result::text, adopted_input_id, generation_run_id,
			       failure_code, started_at, finished_at, created_at
			from agent_steps
			where workspace_id = ? and agent_run_id = ? and sequence = ?
			""".trimIndent(),
			agentStepMapper,
			workspaceId,
			agentRunId,
			sequence,
		).singleOrNull()
	internal fun findStep(workspaceId: UUID, agentRunId: UUID, id: UUID): AgentStepRecord? = sqlExecutor.query(
		"""
		select id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
		       tool_name, arguments::text, result::text, adopted_input_id, generation_run_id,
		       failure_code, started_at, finished_at, created_at
		from agent_steps
		where workspace_id = ? and agent_run_id = ? and id = ?
		""".trimIndent(),
		agentStepMapper,
		workspaceId,
		agentRunId,
		id,
	).firstOrNull()
	internal fun findAdoptedInput(
		workspaceId: UUID,
		agentRunId: UUID,
		input: AgentRunInputRequest,
	): AgentRunInputRecord? = sqlExecutor.query(
		"""
		select id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
		       source_provider, source_kind, source_label,
		       input_kind, order_index, activity_sequence, snapshot_title, snapshot_body,
		       snapshot_excerpt, original_url, source_created_at, source_updated_at,
		       content_hash, captured_at
		from agent_run_inputs
		where workspace_id = ? and agent_run_id = ? and input_kind = 'TOOL_RESULT'
		  and source_scope_id = ? and writing_block_id = ? and content_hash = ?
		""".trimIndent(),
		agentRunInputMapper,
		workspaceId,
		agentRunId,
		input.sourceScopeId,
		input.writingBlockId,
		input.contentHash,
	).singleOrNull()
	internal fun findInput(workspaceId: UUID, agentRunId: UUID, id: UUID): AgentRunInputRecord? = sqlExecutor.query(
		"""
		select id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
		       source_provider, source_kind, source_label,
		       input_kind, order_index, activity_sequence, snapshot_title, snapshot_body,
		       snapshot_excerpt, original_url, source_created_at, source_updated_at,
		       content_hash, captured_at
		from agent_run_inputs
		where workspace_id = ? and agent_run_id = ? and id = ?
		""".trimIndent(),
		agentRunInputMapper,
		workspaceId,
		agentRunId,
		id,
	).firstOrNull()
	internal fun requireAllAgentSourcesActiveForUpdate(workspaceId: UUID, agentRunId: UUID) {
		val expected = sqlExecutor.queryForObject(
			"select count(*) from agent_run_sources where workspace_id = ? and agent_run_id = ?",
			Int::class.java,
			workspaceId,
			agentRunId,
		) ?: 0
		val statuses = sqlExecutor.query(
			"""
			select scope.id, scope.status, namespace.status as namespace_status
			from agent_run_sources source
			join source_scopes scope
			  on scope.workspace_id = source.workspace_id and scope.id = source.source_scope_id
			join source_namespaces namespace
			  on namespace.workspace_id = scope.workspace_id and namespace.id = scope.source_namespace_id
			 and namespace.provider = scope.provider
			where source.workspace_id = ? and source.agent_run_id = ?
			order by scope.id
			for update of scope, namespace
			""".trimIndent(),
			{ rs, _ -> Triple(
				requireNotNull(rs.getObject("id", UUID::class.java)),
				requireNotNull(rs.getString("status")),
				requireNotNull(rs.getString("namespace_status")),
			) },
			workspaceId,
			agentRunId,
		)
		val connectedScopeIds = sqlExecutor.query(
			"""
			select scope.id
			from agent_run_sources source
			join source_scopes scope
			  on scope.workspace_id = source.workspace_id and scope.id = source.source_scope_id
			join connection_namespace_bindings binding
			  on binding.workspace_id = scope.workspace_id
			 and binding.source_namespace_id = scope.source_namespace_id
			 and binding.provider = scope.provider
			join connections connection
			  on connection.workspace_id = binding.workspace_id
			 and connection.id = binding.connection_id
			 and connection.provider = binding.provider
			where source.workspace_id = ? and source.agent_run_id = ?
			  and binding.status = 'ACTIVE' and connection.status = 'ACTIVE'
			order by scope.id
			for update of binding, connection
			""".trimIndent(),
			{ rs, _ -> requireNotNull(rs.getObject("id", UUID::class.java)) },
			workspaceId,
			agentRunId,
		).toSet()
		if (
			expected == 0 ||
			statuses.size != expected ||
			statuses.any { it.second != "ACTIVE" || it.third != "ACTIVE" } ||
			connectedScopeIds != statuses.map { it.first }.toSet()
		) {
			throw AgentToolAccessException("SOURCE_NOT_READY")
		}
	}
}

data class AgentArtifactRecord(
	val id: UUID,
	val status: String,
	val title: String?,
	val updatedAt: Instant,
)
