package com.plot.api.content

import com.plot.api.artifact.dto.RelatedArtifactSummaryResponse
import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.SqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class ContentSourceSnapshotService(
	private val sqlExecutor: SqlExecutor,
	private val uuidGenerator: UuidGenerator,
	private val objectMapper: ObjectMapper,
) {
	fun findOrCreateSnapshotForAgentRun(workspaceId: UUID, agentRunId: UUID): ContentSourceSnapshot {
		val existingSnapshotId = sqlExecutor.queryForObject(
			"select source_snapshot_id from agent_runs where workspace_id = ? and id = ?",
			UUID::class.java,
			workspaceId,
			agentRunId,
		)
		if (existingSnapshotId != null) {
			val snapshot = findSnapshot(workspaceId, existingSnapshotId)
			if (snapshot != null) return snapshot
		}

		val inputs = sqlExecutor.query(
			"""
			select writing_block_id, source_scope_id, source_provider, source_kind, source_label,
			       order_index, snapshot_title, snapshot_body, snapshot_excerpt, original_url,
			       source_created_at, source_updated_at, content_hash, captured_at
			from agent_run_inputs
			where workspace_id = ? and agent_run_id = ? and input_kind = 'SEED'
			order by order_index, id
			""".trimIndent(),
			{ rs, _ ->
				ContentSourceSnapshotInput(
					writingBlockId = rs.getObject("writing_block_id", UUID::class.java),
					sourceScopeId = rs.getObject("source_scope_id", UUID::class.java),
					sourceProvider = requireNotNull(rs.getString("source_provider")),
					sourceKind = requireNotNull(rs.getString("source_kind")),
					sourceLabel = requireNotNull(rs.getString("source_label")),
					orderIndex = rs.getInt("order_index"),
					snapshotTitle = rs.getString("snapshot_title"),
					snapshotBody = requireNotNull(rs.getString("snapshot_body")),
					snapshotExcerpt = rs.getString("snapshot_excerpt"),
					originalUrl = rs.getString("original_url"),
					sourceCreatedAt = rs.getTimestamp("source_created_at")?.toInstant(),
					sourceUpdatedAt = rs.getTimestamp("source_updated_at")?.toInstant(),
					contentHash = requireNotNull(rs.getString("content_hash")),
					capturedAt = requireNotNull(rs.getTimestamp("captured_at")).toInstant(),
				)
			},
			workspaceId,
			agentRunId,
		)

		val releaseInfo = sqlExecutor.query(
			"""
			select r.base_sha, r.head_sha, r.source_scope_id
			from github_release_draft_requests r
			where r.workspace_id = ? and r.agent_run_id = ?
			union all
			select r.base_sha, r.head_sha, r.source_scope_id
			from routine_executions e
			join github_release_draft_requests r on r.workspace_id = e.workspace_id and r.id = e.release_request_id
			where e.workspace_id = ? and e.id = (select routine_execution_id from agent_runs where workspace_id = ? and id = ?)
			limit 1
			""".trimIndent(),
			{ rs, _ -> Triple(rs.getString("base_sha"), rs.getString("head_sha"), rs.getObject("source_scope_id", UUID::class.java)) },
			workspaceId, agentRunId, workspaceId, workspaceId, agentRunId,
		).firstOrNull()

		val baseRef = releaseInfo?.first
		val headRef = releaseInfo?.second
		val sourceScopeId = releaseInfo?.third ?: inputs.firstOrNull { it.sourceScopeId != null }?.sourceScopeId
		val briefSnapshotJson = sqlExecutor.queryForObject(
			"select content_brief_snapshot::text from agent_runs where workspace_id = ? and id = ?",
			String::class.java,
			workspaceId,
			agentRunId,
		)

		val hash = computeSourceBundleHash(inputs, baseRef, headRef, briefSnapshotJson)
		val existing = sqlExecutor.query(
			"select id from content_source_snapshots where workspace_id = ? and source_bundle_hash = ?",
			{ rs, _ -> requireNotNull(rs.getObject("id", UUID::class.java)) },
			workspaceId,
			hash,
		).firstOrNull()

		val snapshotId = existing ?: run {
			val newId = uuidGenerator.next()
			val now = Instant.now()
			val inputsJson = objectMapper.writeValueAsString(inputs)
			sqlExecutor.update(
				"""
				insert into content_source_snapshots (
				  id, workspace_id, source_bundle_hash, source_scope_id, base_ref, head_ref,
				  captured_at, brief_snapshot, inputs_snapshot, created_at
				) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
				on conflict (workspace_id, id) do nothing
				""".trimIndent(),
				newId,
				workspaceId,
				hash,
				sourceScopeId,
				baseRef,
				headRef,
				Timestamp.from(inputs.firstOrNull()?.capturedAt ?: now),
				briefSnapshotJson,
				inputsJson,
				Timestamp.from(now),
			)
			newId
		}

		sqlExecutor.update(
			"update agent_runs set source_snapshot_id = ? where workspace_id = ? and id = ? and source_snapshot_id is null",
			snapshotId,
			workspaceId,
			agentRunId,
		)

		return requireNotNull(findSnapshot(workspaceId, snapshotId))
	}

	fun findSnapshot(workspaceId: UUID, snapshotId: UUID): ContentSourceSnapshot? = sqlExecutor.query(
		"""
		select id, workspace_id, source_bundle_hash, source_scope_id, base_ref, head_ref,
		       captured_at, brief_snapshot::text, inputs_snapshot::text, created_at
		from content_source_snapshots
		where workspace_id = ? and id = ?
		""".trimIndent(),
		{ rs, _ ->
			val inputsJson = requireNotNull(rs.getString("inputs_snapshot"))
			val inputs: List<ContentSourceSnapshotInput> = objectMapper.readValue(
				inputsJson,
				objectMapper.typeFactory.constructCollectionType(List::class.java, ContentSourceSnapshotInput::class.java),
			)
			ContentSourceSnapshot(
				id = requireNotNull(rs.getObject("id", UUID::class.java)),
				workspaceId = requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
				sourceBundleHash = requireNotNull(rs.getString("source_bundle_hash")),
				sourceScopeId = rs.getObject("source_scope_id", UUID::class.java),
				baseRef = rs.getString("base_ref"),
				headRef = rs.getString("head_ref"),
				capturedAt = requireNotNull(rs.getTimestamp("captured_at")).toInstant(),
				briefSnapshotJson = rs.getString("brief_snapshot"),
				inputs = inputs,
				createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
			)
		},
		workspaceId,
		snapshotId,
	).firstOrNull()

	fun findRelatedArtifacts(workspaceId: UUID, artifactId: UUID): List<RelatedArtifactSummaryResponse> {
		val sourceSnapshotId = sqlExecutor.queryForObject(
			"""
			select ar.source_snapshot_id
			from content_packs cp
			join generation_runs gr on gr.workspace_id = cp.workspace_id and gr.id = cp.generation_run_id
			join agent_runs ar on ar.workspace_id = gr.workspace_id and ar.id = gr.agent_run_id
			where cp.workspace_id = ? and cp.id = ?
			""".trimIndent(),
			UUID::class.java,
			workspaceId,
			artifactId,
		) ?: return emptyList()

		return sqlExecutor.query(
			"""
			select distinct cp.id, cp.title, coalesce(ar.content_type, 'CHANGELOG') as content_type, cp.status, cp.updated_at
			from content_packs cp
			join generation_runs gr on gr.workspace_id = cp.workspace_id and gr.id = cp.generation_run_id
			join agent_runs ar on ar.workspace_id = gr.workspace_id and ar.id = gr.agent_run_id
			where cp.workspace_id = ? and ar.source_snapshot_id = ? and cp.id <> ?
			order by cp.updated_at desc, cp.id desc
			""".trimIndent(),
			{ rs, _ ->
				RelatedArtifactSummaryResponse(
					id = requireNotNull(rs.getObject("id", UUID::class.java)),
					title = rs.getString("title"),
					contentType = requireNotNull(rs.getString("content_type")),
					status = requireNotNull(rs.getString("status")),
					updatedAt = requireNotNull(rs.getTimestamp("updated_at")).toInstant(),
				)
			},
			workspaceId,
			sourceSnapshotId,
			artifactId,
		)
	}
}
