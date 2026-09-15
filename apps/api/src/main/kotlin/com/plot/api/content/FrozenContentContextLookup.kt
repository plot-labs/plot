package com.plot.api.content

import com.plot.api.contentprofile.ContentProfilePersistence
import com.plot.api.persistence.SqlExecutor
import java.util.UUID
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

fun interface FrozenContentContextLookup {
	fun forRun(artifactWorkflowRunId: UUID): FrozenContentContext
}

@Component
class GenerationRunFrozenContentContextLookup(
	private val sqlExecutor: SqlExecutor,
	private val contentProfilePersistence: ContentProfilePersistence,
	private val objectMapper: ObjectMapper,
) : FrozenContentContextLookup {
	override fun forRun(artifactWorkflowRunId: UUID): FrozenContentContext {
		val row = sqlExecutor.query(
			"""
			select gr.workspace_id, ar.content_profile_revision_id, ar.content_brief_snapshot::text
			from generation_runs gr
			left join agent_runs ar
			  on ar.workspace_id = gr.workspace_id and ar.id = gr.agent_run_id
			where gr.id = ?
			""".trimIndent(),
			{ rs, _ -> FrozenContentRow(
				requireNotNull(rs.getObject(1, UUID::class.java)),
				rs.getObject(2, UUID::class.java),
				rs.getString(3),
			) },
			artifactWorkflowRunId,
		).firstOrNull() ?: return FrozenContentContext(null, null)

		val profile = row.revisionId?.let { contentProfilePersistence.findRevision(row.workspaceId, it) }
		val brief = row.briefJson?.takeIf { it.isNotBlank() }?.let { json ->
			try {
				objectMapper.readValue(json, ContentBrief::class.java)
			} catch (failure: RuntimeException) {
				throw IllegalStateException("Frozen content brief snapshot is invalid", failure)
			}
		}
		return FrozenContentContext(profile, brief)
	}
}

private data class FrozenContentRow(
	val workspaceId: UUID,
	val revisionId: UUID?,
	val briefJson: String?,
)
