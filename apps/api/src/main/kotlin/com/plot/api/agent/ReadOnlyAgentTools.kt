package com.plot.api.agent

import com.plot.api.ai.provider.AgentSourceView
import com.plot.api.writingblock.writingBlockContentHash
import java.net.URI
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.SqlRow
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class AgentSearchItem(
	val writingBlockId: UUID,
	val title: String?,
	val excerpt: String,
)

data class AgentToolResult(
	val sourceScopeId: UUID? = null,
	val sourceStatusChangedAt: Instant? = null,
	val sources: List<AgentSourceView> = emptyList(),
	val matches: List<AgentSearchItem> = emptyList(),
	val adoptedInput: AgentRunInputRequest? = null,
)

@Component
class ReadOnlyAgentTools(
	private val sqlExecutor: SqlExecutor,
	private val properties: AgentProperties,
	private val objectMapper: ObjectMapper,
	private val snapshots: AgentExecutionSnapshotPersistence,
) {
	fun listAllowedSources(workspaceId: UUID, agentRunId: UUID, frozenReplay: Boolean = false): AgentToolResult = AgentToolResult(
		sources = if (frozenReplay) {
			sqlExecutor.query(
				"""
					select source.source_scope_id, source.source_display_name, source.source_role
				from agent_run_sources source
				where source.workspace_id = ? and source.agent_run_id = ?
				order by source.order_index, source.source_scope_id
				""".trimIndent(),
				{ rs, _ -> AgentSourceView(
					requireNotNull(rs.getObject(1, UUID::class.java)),
						requireNotNull(rs.getString(2)),
						requireNotNull(rs.getString(3)),
				) },
				workspaceId,
				agentRunId,
			)
		} else sqlExecutor.query(
			"""
			select scope.id, scope.display_name, source.source_role
			from agent_run_sources source
			join source_scopes scope
			  on scope.workspace_id = source.workspace_id and scope.id = source.source_scope_id
			join source_namespaces namespace
			  on namespace.workspace_id = scope.workspace_id and namespace.id = scope.source_namespace_id
			 and namespace.provider = scope.provider and namespace.status = 'ACTIVE'
			join connection_namespace_bindings binding
			  on binding.workspace_id = namespace.workspace_id and binding.source_namespace_id = namespace.id
			 and binding.provider = namespace.provider and binding.status = 'ACTIVE'
			join connections connection
			  on connection.workspace_id = binding.workspace_id and connection.id = binding.connection_id
			 and connection.provider = binding.provider and connection.status = 'ACTIVE'
			where source.workspace_id = ? and source.agent_run_id = ? and scope.status = 'ACTIVE'
			order by source.order_index, scope.id
			""".trimIndent(),
			{ rs, _ -> AgentSourceView(
				requireNotNull(rs.getObject(1, UUID::class.java)),
				requireNotNull(rs.getString(2)),
				requireNotNull(rs.getString(3)),
			) },
			workspaceId,
			agentRunId,
		),
	)

	fun searchWritingBlocks(
		workspaceId: UUID,
		agentRunId: UUID,
		sourceScopeId: UUID,
		query: String,
		frozenReplay: Boolean = false,
	): AgentToolResult {
		if (frozenReplay) return replaySearch(workspaceId, agentRunId, sourceScopeId, query)
		val source = requireActiveAllowedSource(workspaceId, agentRunId, sourceScopeId)
		val normalized = query.trim().take(200)
		require(normalized.isNotBlank()) { "Search query is required" }
		val pattern = "%${escapeLike(normalized.lowercase())}%"
		val matches = sqlExecutor.query(
			"""
			select block.id, block.title, block.body
			from writing_blocks block
			where block.workspace_id = ? and block.status = 'ACTIVE'
			  and exists (
			    select 1 from writing_block_scopes membership
			    where membership.workspace_id = block.workspace_id
			      and membership.writing_block_id = block.id
			      and membership.source_scope_id = ?
			      and membership.status = 'ACTIVE'
			  )
			  and lower(coalesce(block.title, '') || ' ' || coalesce(block.body, '')) like ? escape '!'
			order by block.activity_sequence desc, block.id
			limit ?
			""".trimIndent(),
			{ rs, _ ->
				AgentSearchItem(
					writingBlockId = requireNotNull(rs.getObject("id", UUID::class.java)),
					title = rs.getString("title")?.take(MAX_RESULT_TITLE),
					excerpt = normalizedExcerpt(rs.getString("body") ?: rs.getString("title").orEmpty()),
				)
			},
			workspaceId,
			sourceScopeId,
			pattern,
			properties.searchResultLimit,
		)
		return AgentToolResult(sourceScopeId, source.statusChangedAt, matches = matches)
	}

	fun readWritingBlock(
		workspaceId: UUID,
		agentRunId: UUID,
		sourceScopeId: UUID,
		writingBlockId: UUID,
		frozenReplay: Boolean = false,
	): AgentToolResult {
		if (frozenReplay) return replayRead(workspaceId, agentRunId, sourceScopeId, writingBlockId)
		val source = requireActiveAllowedSource(workspaceId, agentRunId, sourceScopeId)
		val frozenInput = sqlExecutor.query(
			"""
			select writing_block_id, source_scope_id, snapshot_title, snapshot_body,
			       snapshot_excerpt, original_url, source_created_at, source_updated_at, content_hash,
			       source_provider, source_kind, source_label, input_kind, order_index, activity_sequence, captured_at
			from agent_run_inputs
			where workspace_id = ? and agent_run_id = ? and writing_block_id = ?
			""".trimIndent(),
			{ rs, _ ->
				AgentRunInputRequest(
					routineId = null,
					sourceScopeId = requireNotNull(rs.getObject("source_scope_id", UUID::class.java)),
					writingBlockId = requireNotNull(rs.getObject("writing_block_id", UUID::class.java)),
					sourceProvider = rs.getString("source_provider") ?: "GITHUB",
					sourceKind = rs.getString("source_kind") ?: "COMMIT",
					sourceLabel = rs.getString("source_label") ?: source.label,
					inputKind = AgentRunInputKind.TOOL_RESULT,
					orderIndex = 0,
					activitySequence = null,
					snapshotTitle = rs.getString("snapshot_title"),
					snapshotBody = requireNotNull(rs.getString("snapshot_body")),
					snapshotExcerpt = rs.getString("snapshot_excerpt"),
					originalUrl = requireNotNull(rs.getString("original_url")),
					sourceCreatedAt = rs.getTimestamp("source_created_at")?.toInstant(),
					sourceUpdatedAt = rs.getTimestamp("source_updated_at")?.toInstant(),
					contentHash = requireNotNull(rs.getString("content_hash")),
					capturedAt = rs.getTimestamp("captured_at")?.toInstant() ?: Instant.now(),
				)
			},
			workspaceId,
			agentRunId,
			writingBlockId,
		).firstOrNull()

		if (frozenInput != null) {
			return AgentToolResult(
				sourceScopeId = sourceScopeId,
				sourceStatusChangedAt = source.statusChangedAt,
				adoptedInput = frozenInput,
			)
		}

		val block = sqlExecutor.query(
			"""
			select block.id, block.title, block.body, block.url, block.canonical_url,
			       block.platform, block.source_kind, block.content_hash,
			       block.source_created_at, block.source_updated_at
			from writing_blocks block
			where block.workspace_id = ? and block.id = ? and block.status = 'ACTIVE'
			  and exists (
			    select 1 from writing_block_scopes membership
			    where membership.workspace_id = block.workspace_id
			      and membership.writing_block_id = block.id
			      and membership.source_scope_id = ?
			      and membership.status = 'ACTIVE'
			  )
			""".trimIndent(),
			{ rs, _ -> rs.toReadBlock(sourceScopeId, source.label) },
			workspaceId,
			writingBlockId,
			sourceScopeId,
		).singleOrNull() ?: throw AgentToolAccessException("SOURCE_ITEM_NOT_FOUND")

		return AgentToolResult(
			sourceScopeId = sourceScopeId,
			sourceStatusChangedAt = source.statusChangedAt,
			adoptedInput = block,
		)
	}

	private fun replaySearch(
		workspaceId: UUID,
		agentRunId: UUID,
		sourceScopeId: UUID,
		query: String,
	): AgentToolResult {
		requireFrozenSourceAllowed(workspaceId, agentRunId, sourceScopeId)
		val normalized = query.trim().take(200)
		require(normalized.isNotBlank()) { "Search query is required" }
		val result = snapshots.matchingTranscriptEntry(workspaceId, agentRunId) { arguments, toolName ->
			toolName == "SEARCH_WRITING_BLOCKS" &&
				arguments.path("action").asText() == "SEARCH_WRITING_BLOCKS" &&
				arguments.path("sourceScopeId").asText() == sourceScopeId.toString() &&
				arguments.path("query").asText() == normalized
		} ?: throw AgentToolAccessException("FROZEN_EVIDENCE_MISS")
		val matches = result.path("matches").iterator().asSequence().map { item ->
			AgentSearchItem(
				writingBlockId = UUID.fromString(item.path("writingBlockId").asText()),
				title = item.path("title").takeUnless { it.isNull }?.asText()?.take(MAX_RESULT_TITLE),
				excerpt = item.path("excerpt").asText().take(MAX_RESULT_EXCERPT),
			)
		}.toList()
		return AgentToolResult(sourceScopeId = sourceScopeId, matches = matches)
	}

	private fun replayRead(
		workspaceId: UUID,
		agentRunId: UUID,
		sourceScopeId: UUID,
		writingBlockId: UUID,
	): AgentToolResult {
		val source = requireFrozenSourceAllowed(workspaceId, agentRunId, sourceScopeId)
		val frozenInput = sqlExecutor.query(
			"""
			select writing_block_id, source_scope_id, snapshot_title, snapshot_body,
			       snapshot_excerpt, original_url, source_created_at, source_updated_at, content_hash,
			       source_provider, source_kind, source_label, input_kind, order_index, activity_sequence, captured_at
			from agent_run_inputs
			where workspace_id = ? and agent_run_id = ? and source_scope_id = ? and writing_block_id = ?
			""".trimIndent(),
			{ rs, _ -> rs.toFrozenInput(source.label) },
			workspaceId,
			agentRunId,
			sourceScopeId,
			writingBlockId,
		).firstOrNull() ?: throw AgentToolAccessException("FROZEN_EVIDENCE_MISS")
		val recorded = snapshots.matchingTranscriptEntry(workspaceId, agentRunId) { arguments, toolName ->
			val recordedInputId = arguments.path("writingBlockId").asText()
			toolName == "READ_WRITING_BLOCKS" &&
				arguments.path("action").asText() == "READ_WRITING_BLOCKS" &&
				arguments.path("sourceScopeId").asText() == sourceScopeId.toString() &&
				(recordedInputId == writingBlockId.toString() || recordedInputId.isNotBlank() && sqlExecutor.queryForObject(
					"""
					select exists(
					  select 1 from agent_run_inputs
					  where workspace_id = ? and agent_run_id = ? and id::text = ? and writing_block_id = ?
					)
					""".trimIndent(),
					Boolean::class.java,
					workspaceId,
					agentRunId,
					recordedInputId,
					writingBlockId,
				) == true)
		}
		if (recorded == null) {
			throw AgentToolAccessException("FROZEN_EVIDENCE_MISS")
		}
		return AgentToolResult(sourceScopeId = sourceScopeId, adoptedInput = frozenInput)
	}

	private fun requireFrozenSourceAllowed(workspaceId: UUID, agentRunId: UUID, sourceScopeId: UUID): AllowedSource = sqlExecutor.query(
		"""
		select scope.display_name, scope.status_changed_at
		from agent_run_sources source
		join source_scopes scope
		  on scope.workspace_id = source.workspace_id and scope.id = source.source_scope_id
		join source_namespaces namespace
		  on namespace.workspace_id = scope.workspace_id and namespace.id = scope.source_namespace_id
		 and namespace.provider = scope.provider
		where source.workspace_id = ? and source.agent_run_id = ? and source.source_scope_id = ?
		  and scope.status = 'ACTIVE' and namespace.status = 'ACTIVE'
		""".trimIndent(),
		{ rs, _ -> AllowedSource(requireNotNull(rs.getString(1)), requireNotNull(rs.getTimestamp(2)).toInstant()) },
		workspaceId,
		agentRunId,
		sourceScopeId,
	).singleOrNull() ?: throw AgentToolAccessException("SOURCE_NOT_ALLOWED")

	private fun SqlRow.toFrozenInput(sourceLabel: String): AgentRunInputRequest = AgentRunInputRequest(
		routineId = null,
		sourceScopeId = requireNotNull(getObject("source_scope_id", UUID::class.java)),
		writingBlockId = requireNotNull(getObject("writing_block_id", UUID::class.java)),
		sourceProvider = getString("source_provider") ?: "GITHUB",
		sourceKind = getString("source_kind") ?: "COMMIT",
		sourceLabel = getString("source_label") ?: sourceLabel,
		inputKind = AgentRunInputKind.TOOL_RESULT,
		orderIndex = getInt("order_index"),
		activitySequence = getObject("activity_sequence", Long::class.java),
		snapshotTitle = getString("snapshot_title"),
		snapshotBody = requireNotNull(getString("snapshot_body")),
		snapshotExcerpt = getString("snapshot_excerpt"),
		originalUrl = requireNotNull(getString("original_url")),
		sourceCreatedAt = getTimestamp("source_created_at")?.toInstant(),
		sourceUpdatedAt = getTimestamp("source_updated_at")?.toInstant(),
		contentHash = requireNotNull(getString("content_hash")),
		capturedAt = requireNotNull(getTimestamp("captured_at")).toInstant(),
	)

	private fun requireActiveAllowedSource(
		workspaceId: UUID,
		agentRunId: UUID,
		sourceScopeId: UUID,
	): AllowedSource = sqlExecutor.query(
		"""
		select scope.display_name,
		       greatest(scope.status_changed_at, namespace.updated_at, binding.updated_at, connection.updated_at) as lifecycle_version_at
		from agent_run_sources source
		join source_scopes scope
		  on scope.workspace_id = source.workspace_id and scope.id = source.source_scope_id
		join source_namespaces namespace
		  on namespace.workspace_id = scope.workspace_id and namespace.id = scope.source_namespace_id
		 and namespace.provider = scope.provider and namespace.status = 'ACTIVE'
		join connection_namespace_bindings binding
		  on binding.workspace_id = namespace.workspace_id and binding.source_namespace_id = namespace.id
		 and binding.provider = namespace.provider and binding.status = 'ACTIVE'
		join connections connection
		  on connection.workspace_id = binding.workspace_id and connection.id = binding.connection_id
		 and connection.provider = binding.provider and connection.status = 'ACTIVE'
		where source.workspace_id = ? and source.agent_run_id = ?
		  and source.source_scope_id = ? and scope.status = 'ACTIVE'
		""".trimIndent(),
		{ rs, _ -> AllowedSource(requireNotNull(rs.getString(1)), requireNotNull(rs.getTimestamp("lifecycle_version_at")).toInstant()) },
		workspaceId,
		agentRunId,
		sourceScopeId,
	).singleOrNull() ?: throw AgentToolAccessException("SOURCE_NOT_ALLOWED")

	private fun SqlRow.toReadBlock(sourceScopeId: UUID, sourceLabel: String): AgentRunInputRequest {
		val title = getString("title")?.trim()?.takeIf { it.isNotBlank() }
		val unboundedBody = getString("body")?.trim()?.takeIf { it.isNotBlank() } ?: title
			?: throw AgentToolAccessException("SOURCE_ITEM_EMPTY")
		val boundedTitle = title
			?.take(minOf(MAX_RESULT_TITLE, (properties.maxInputCharacters - 1).coerceAtLeast(0)))
			?.takeIf { it.isNotBlank() }
		val bodyBudget = properties.maxInputCharacters - boundedTitle.orEmpty().length
		val boundedBody = unboundedBody.take(bodyBudget)
		val provider = getString("platform")?.uppercase()?.takeIf { it.isNotBlank() } ?: "GITHUB"
		val sourceKind = requireNotNull(getString("source_kind")).trim()
		val url = canonicalHttpUrl(getString("canonical_url") ?: getString("url"))
		return AgentRunInputRequest(
			routineId = null,
			sourceScopeId = sourceScopeId,
			writingBlockId = requireNotNull(getObject("id", UUID::class.java)),
			sourceProvider = provider,
			sourceKind = sourceKind,
			sourceLabel = boundedTitle ?: "$sourceLabel $sourceKind",
			inputKind = AgentRunInputKind.TOOL_RESULT,
			orderIndex = 0,
			activitySequence = null,
			snapshotTitle = boundedTitle,
			snapshotBody = boundedBody,
			snapshotExcerpt = normalizedExcerpt(boundedBody),
			originalUrl = url,
			sourceCreatedAt = getTimestamp("source_created_at")?.toInstant(),
			sourceUpdatedAt = getTimestamp("source_updated_at")?.toInstant(),
			contentHash = getString("content_hash")?.takeIf { it.isNotBlank() }
				?: writingBlockContentHash(title, unboundedBody),
			capturedAt = Instant.now(),
		)
	}

	private fun canonicalHttpUrl(value: String?): String {
		val url = value?.trim()?.takeIf { it.isNotBlank() }
			?: throw AgentToolAccessException("SOURCE_ITEM_URL_INVALID")
		val uri = runCatching { URI(url) }.getOrNull()
		if (uri?.scheme?.lowercase() !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
			throw AgentToolAccessException("SOURCE_ITEM_URL_INVALID")
		}
		return uri.toASCIIString()
	}

	private fun normalizedExcerpt(value: String): String = value.replace(WHITESPACE, " ").trim().take(MAX_RESULT_EXCERPT)

	private fun escapeLike(value: String): String = value
		.replace("!", "!!")
		.replace("%", "!%")
		.replace("_", "!_")

	private data class AllowedSource(val label: String, val statusChangedAt: Instant)

	private companion object {
		const val MAX_RESULT_TITLE = 240
		const val MAX_RESULT_EXCERPT = 480
		val WHITESPACE = Regex("\\s+")
	}
}

class AgentToolAccessException(val safeCode: String) : IllegalArgumentException(safeCode)
