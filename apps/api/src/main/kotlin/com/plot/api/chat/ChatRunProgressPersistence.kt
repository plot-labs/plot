package com.plot.api.chat

import com.plot.api.agent.AgentRunClaimLostException
import com.plot.api.agent.AgentRunOrigin
import com.plot.api.agent.AgentRunQueryPersistence
import com.plot.api.agent.AgentRunStatus
import com.plot.api.agent.ClaimedAgentRun
import com.plot.api.artifact.workflow.ArtifactWorkflowExecutionPersistence
import com.plot.api.artifact.workflow.ArtifactWorkflowRunLeaseLostException
import com.plot.api.artifact.workflow.ClaimedArtifactWorkflowRun
import com.plot.api.artifact.workflow.ModelInvocationLease
import com.plot.api.ai.provider.ModelRole
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.TransactionExecutor
import java.util.UUID
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class ChatRunSnapshot(
	val runId: UUID,
	val epoch: Long,
	val revision: Long,
	val phase: String,
	val responseText: String,
	val draftParagraphs: List<String>,
	val status: AgentRunStatus,
	val artifactId: UUID?,
	val failureCode: String?,
)

@Component
class ChatRunProgressPersistence(
	private val sql: SqlExecutor,
	private val transactions: TransactionExecutor,
	private val runs: AgentRunQueryPersistence,
	private val mapper: ObjectMapper,
	private val artifacts: ArtifactWorkflowExecutionPersistence,
) {
	fun beginAgent(claim: ClaimedAgentRun): Long? = transactions.execute {
		if (runs.requireAgentClaim(claim).origin != AgentRunOrigin.CHAT) return@execute null
		sql.queryForObject("""
			insert into chat_run_progress (workspace_id, agent_run_id, epoch, revision, phase)
			values (?, ?, 1, 1, 'RESPONDING')
			on conflict (workspace_id, agent_run_id) do update
			set epoch=chat_run_progress.epoch+1, revision=chat_run_progress.revision+1,
			    phase='RESPONDING', response_text='', draft_paragraphs='[]', updated_at=now()
			returning epoch
		""".trimIndent(), Long::class.javaObjectType, claim.workspaceId, claim.agentRunId)
	}

	fun updateAgent(claim: ClaimedAgentRun, epoch: Long, phase: String, text: String) = transactions.execute {
		runs.requireAgentClaim(claim)
		require(phase in setOf("RESPONDING", "RESEARCHING", "WRITING") && text.length <= 40_000)
		if (sql.update("""
			update chat_run_progress set phase=?, response_text=?, revision=revision+1, updated_at=now()
			where workspace_id=? and agent_run_id=? and epoch=? and phase not in ('COMPLETE','FAILED')
		""".trimIndent(), phase, text, claim.workspaceId, claim.agentRunId, epoch) != 1) throw AgentRunClaimLostException()
	}

	fun beginArtifact(claim: ClaimedArtifactWorkflowRun, invocation: ModelInvocationLease): Long? = transactions.execute {
		val owner = artifactOwner(claim, invocation) ?: return@execute null
		val phase = when (invocation.role) { ModelRole.WRITER -> "WRITING"; ModelRole.REVIEWER -> "REVIEWING"; else -> "REWRITING" }
		sql.queryForObject("""
			insert into chat_run_progress (workspace_id,agent_run_id,epoch,revision,phase)
			values (?,?,1,1,?) on conflict (workspace_id,agent_run_id) do update
			set epoch=chat_run_progress.epoch+1,revision=chat_run_progress.revision+1,phase=excluded.phase,
			    draft_paragraphs=case when excluded.phase='WRITING' then '[]'::jsonb else chat_run_progress.draft_paragraphs end,updated_at=now()
			returning epoch
		""".trimIndent(), Long::class.javaObjectType, claim.workspaceId, owner, phase)
	}

	fun updateArtifact(claim: ClaimedArtifactWorkflowRun, invocation: ModelInvocationLease, epoch: Long, phase: String, paragraphs: List<String>) = transactions.execute {
		val owner = artifactOwner(claim, invocation) ?: return@execute
		require(phase in setOf("WRITING","REVIEWING","REWRITING"))
		val json = mapper.writeValueAsString(paragraphs)
		require(json.length <= 800_000)
		if (sql.update("""
			update chat_run_progress set phase=?,draft_paragraphs=?::jsonb,revision=revision+1,updated_at=now()
			where workspace_id=? and agent_run_id=? and epoch=? and phase not in ('COMPLETE','FAILED')
		""".trimIndent(),phase,json,claim.workspaceId,owner,epoch) != 1) throw ArtifactWorkflowRunLeaseLostException()
	}

	private fun artifactOwner(claim: ClaimedArtifactWorkflowRun, invocation: ModelInvocationLease): UUID? {
		artifacts.requireClaim(claim)
		if (sql.queryForObject("select exists(select 1 from model_invocations where workspace_id=? and generation_run_id=? and id=? and workflow_step_id=? and role=? and status='RUNNING')",
			Boolean::class.java,claim.workspaceId,claim.runId,invocation.id,invocation.stepId,invocation.role.name) != true) throw ArtifactWorkflowRunLeaseLostException()
		val owner = sql.query("""
			select a.id,a.status from generation_runs g join agent_runs a on a.workspace_id=g.workspace_id and a.id=g.agent_run_id
			where g.workspace_id=? and g.id=? and a.origin='CHAT' for update of a
		""".trimIndent(), { row,_ -> row.getObject("id",UUID::class.java)!! to row.getString("status") },claim.workspaceId,claim.runId).singleOrNull() ?: return null
		if (owner.second != "RUNNING") throw ArtifactWorkflowRunLeaseLostException()
		return owner.first
	}

	fun load(workspaceId: UUID, runId: UUID): ChatRunSnapshot? = loadMany(listOf(workspaceId to runId))[workspaceId to runId]

	/** Only registered subscriptions are queried; no connection is held while sending. */
	fun loadMany(keys: List<Pair<UUID, UUID>>): Map<Pair<UUID, UUID>, ChatRunSnapshot> {
		if (keys.isEmpty()) return emptyMap()
		return sql.query("""
			select a.workspace_id, a.id, a.status, a.failure_code, coalesce(p.epoch,0) epoch, coalesce(p.revision,0) revision,
			       case a.status when 'SUCCEEDED' then 'COMPLETE' when 'FAILED' then 'FAILED' else coalesce(p.phase,'QUEUED') end phase,
			       coalesce(v.response_text, p.response_text, '') response_text, coalesce(p.draft_paragraphs,'[]')::text draft_paragraphs,
			       artifact.id artifact_id
			from agent_runs a
			left join chat_run_progress p on p.workspace_id=a.workspace_id and p.agent_run_id=a.id
			left join chat_response_versions v on v.workspace_id=a.workspace_id and v.agent_run_id=a.id
			left join lateral (
			  select pack.id from artifact_runs owner
			  join generation_runs generation on generation.workspace_id=owner.workspace_id and generation.artifact_run_id=owner.id
			  join content_packs pack on pack.workspace_id=generation.workspace_id and pack.generation_run_id=generation.id
			  where owner.workspace_id=a.workspace_id and owner.agent_run_id=a.id
			  order by pack.updated_at desc, pack.id desc limit 1
			) artifact on true
			where a.origin='CHAT' and (a.workspace_id,a.id) in (${keys.joinToString { "(?,?)" }})
		""".trimIndent(), { row, _ ->
			val workspace = requireNotNull(row.getObject("workspace_id", UUID::class.java))
			val id = requireNotNull(row.getObject("id", UUID::class.java))
			(workspace to id) to ChatRunSnapshot(id, row.getLong("epoch"), row.getLong("revision"), row.getString("phase")!!,
				row.getString("response_text").orEmpty(), mapper.readTree(row.getString("draft_paragraphs")).toList().map { it.stringValue() },
				AgentRunStatus.valueOf(row.getString("status")!!), row.getObject("artifact_id", UUID::class.java), row.getString("failure_code"))
		}, *keys.flatMap { listOf(it.first, it.second) }.toTypedArray()).toMap()
	}
}
