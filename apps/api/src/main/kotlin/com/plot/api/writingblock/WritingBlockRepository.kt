package com.plot.api.writingblock

import com.plot.api.persistence.generated.tables.AgentRunInputs.Companion.AGENT_RUN_INPUTS
import com.plot.api.persistence.generated.tables.AgentRuns.Companion.AGENT_RUNS
import com.plot.api.persistence.generated.tables.SourceScopes.Companion.SOURCE_SCOPES
import com.plot.api.persistence.generated.tables.WritingBlockScopes.Companion.WRITING_BLOCK_SCOPES
import com.plot.api.persistence.generated.tables.WritingBlocks.Companion.WRITING_BLOCKS
import java.time.OffsetDateTime
import java.util.UUID
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import org.jooq.impl.DSL.exists
import org.jooq.impl.DSL.noCondition

data class WritingBlockPage(
	val content: List<WritingBlock>,
	val totalElements: Long,
)

/** jOOQ-backed writing-block queries. All projections are explicit by design. */
@Repository
class WritingBlockRepository(
	private val dsl: DSLContext,
	private val objectMapper: ObjectMapper,
) {
	fun findAllByWorkspaceId(
		workspaceId: UUID,
		offset: Int,
		limit: Int,
	): WritingBlockPage {
		val conditions = listOf(WRITING_BLOCKS.WORKSPACE_ID.eq(workspaceId))
		return page(conditions, offset, limit)
	}

	fun findAllByWorkspaceIdAndSourceScopeId(
		workspaceId: UUID,
		sourceScopeId: UUID,
		offset: Int,
		limit: Int,
	): WritingBlockPage {
		val conditions = listOf(
			WRITING_BLOCKS.WORKSPACE_ID.eq(workspaceId),
			activeMembership(workspaceId, sourceScopeId),
		)
		return page(conditions, offset, limit)
	}

	fun findUnconsumedActiveAfterActivityCursor(
		workspaceId: UUID,
		routineId: UUID,
		sourceScopeId: UUID,
		cursorSequence: Long?,
		limit: Int,
	): List<WritingBlock> {
		val cursor = cursorSequence?.let { WRITING_BLOCKS.ACTIVITY_SEQUENCE.gt(it) } ?: noCondition()
		val conditions = listOf(
			WRITING_BLOCKS.WORKSPACE_ID.eq(workspaceId),
			WRITING_BLOCKS.STATUS.eq("ACTIVE"),
			cursor,
			activeSource(workspaceId, sourceScopeId),
			activeMembership(workspaceId, sourceScopeId),
			org.jooq.impl.DSL.notExists(
				dsl.selectOne()
					.from(AGENT_RUN_INPUTS)
					.join(AGENT_RUNS)
					.on(
						AGENT_RUNS.WORKSPACE_ID.eq(AGENT_RUN_INPUTS.WORKSPACE_ID),
						AGENT_RUNS.ID.eq(AGENT_RUN_INPUTS.AGENT_RUN_ID),
					)
					.where(
						AGENT_RUN_INPUTS.WORKSPACE_ID.eq(workspaceId),
						AGENT_RUN_INPUTS.ROUTINE_ID.eq(routineId),
						AGENT_RUN_INPUTS.WRITING_BLOCK_ID.eq(WRITING_BLOCKS.ID),
						AGENT_RUN_INPUTS.ACTIVITY_SEQUENCE.eq(WRITING_BLOCKS.ACTIVITY_SEQUENCE),
						AGENT_RUN_INPUTS.INPUT_KIND.eq("SEED"),
						AGENT_RUNS.STATUS.`in`("QUEUED", "RUNNING", "SUCCEEDED"),
					),
			),
		)
		return select(conditions)
			.orderBy(WRITING_BLOCKS.ACTIVITY_SEQUENCE.asc())
			.limit(limit)
			.fetch()
			.map { it.toModel() }
	}

	fun findSelectedReadable(
		workspaceId: UUID,
		sourceScopeId: UUID,
		ids: Collection<UUID>,
	): List<WritingBlock> {
		if (ids.isEmpty()) return emptyList()
		return select(
			listOf(
				WRITING_BLOCKS.WORKSPACE_ID.eq(workspaceId),
				WRITING_BLOCKS.ID.`in`(ids),
				WRITING_BLOCKS.STATUS.eq("ACTIVE"),
				activeSource(workspaceId, sourceScopeId),
				activeMembership(workspaceId, sourceScopeId),
			),
		)
			.fetch()
			.map { it.toModel() }
	}

	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): WritingBlock? = select(
		listOf(
			WRITING_BLOCKS.WORKSPACE_ID.eq(workspaceId),
			WRITING_BLOCKS.ID.eq(id),
		),
	)
		.fetchOne()
		?.toModel()

	private fun page(conditions: List<Condition>, offset: Int, limit: Int): WritingBlockPage {
		val content = select(conditions)
			.orderBy(
				WRITING_BLOCKS.SOURCE_CREATED_AT.desc().nullsLast(),
				WRITING_BLOCKS.EXTERNAL_OBJECT_KEY.desc().nullsLast(),
				WRITING_BLOCKS.CREATED_AT.desc(),
				WRITING_BLOCKS.ID.desc(),
			)
			.offset(offset)
			.limit(limit)
			.fetch()
			.map { it.toModel() }
		val total = if (content.size < limit) {
			offset.toLong() + content.size
		} else {
			dsl
				.selectCount()
				.from(WRITING_BLOCKS)
				.where(*conditions.toTypedArray())
				.fetchOne(0, Long::class.java) ?: 0L
		}
		return WritingBlockPage(content, total)
	}

	private fun select(conditions: List<Condition>) = dsl
		.select(
			WRITING_BLOCKS.ID,
			WRITING_BLOCKS.WORKSPACE_ID,
			WRITING_BLOCKS.SOURCE_NAMESPACE_ID,
			WRITING_BLOCKS.EXTERNAL_OBJECT_KEY,
			WRITING_BLOCKS.SOURCE_ORIGIN,
			WRITING_BLOCKS.SOURCE_KIND,
			WRITING_BLOCKS.TITLE,
			WRITING_BLOCKS.BODY,
			WRITING_BLOCKS.URL,
			WRITING_BLOCKS.CANONICAL_URL,
			WRITING_BLOCKS.AUTHOR,
			WRITING_BLOCKS.PLATFORM,
			WRITING_BLOCKS.METADATA,
			WRITING_BLOCKS.CONTENT_HASH,
			WRITING_BLOCKS.SOURCE_CREATED_AT,
			WRITING_BLOCKS.SOURCE_UPDATED_AT,
			WRITING_BLOCKS.INGESTED_AT,
			WRITING_BLOCKS.STATUS,
			WRITING_BLOCKS.CREATED_BY_USER_ID,
			WRITING_BLOCKS.CREATED_AT,
			WRITING_BLOCKS.UPDATED_AT,
			WRITING_BLOCKS.ACTIVITY_SEQUENCE,
		)
		.from(WRITING_BLOCKS)
		.where(*conditions.toTypedArray())

	private fun activeSource(workspaceId: UUID, sourceScopeId: UUID): Condition = exists(
		dsl.selectOne()
			.from(SOURCE_SCOPES)
			.where(
				SOURCE_SCOPES.WORKSPACE_ID.eq(workspaceId),
				SOURCE_SCOPES.ID.eq(sourceScopeId),
				SOURCE_SCOPES.STATUS.eq("ACTIVE"),
			),
	)

	private fun activeMembership(workspaceId: UUID, sourceScopeId: UUID): Condition = exists(
		dsl.selectOne()
			.from(WRITING_BLOCK_SCOPES)
			.where(
				WRITING_BLOCK_SCOPES.WORKSPACE_ID.eq(workspaceId),
				WRITING_BLOCK_SCOPES.SOURCE_SCOPE_ID.eq(sourceScopeId),
				WRITING_BLOCK_SCOPES.WRITING_BLOCK_ID.eq(WRITING_BLOCKS.ID),
				WRITING_BLOCK_SCOPES.STATUS.eq("ACTIVE"),
			),
	)

	private fun Record.toModel() = WritingBlock(
		id = requireNotNull(get(WRITING_BLOCKS.ID)),
		workspaceId = requireNotNull(get(WRITING_BLOCKS.WORKSPACE_ID)),
		sourceNamespaceId = get(WRITING_BLOCKS.SOURCE_NAMESPACE_ID),
		externalObjectKey = get(WRITING_BLOCKS.EXTERNAL_OBJECT_KEY),
		sourceOrigin = requireNotNull(get(WRITING_BLOCKS.SOURCE_ORIGIN)),
		sourceKind = requireNotNull(get(WRITING_BLOCKS.SOURCE_KIND)),
		title = get(WRITING_BLOCKS.TITLE),
		body = get(WRITING_BLOCKS.BODY),
		url = get(WRITING_BLOCKS.URL),
		canonicalUrl = get(WRITING_BLOCKS.CANONICAL_URL),
		author = get(WRITING_BLOCKS.AUTHOR),
		platform = get(WRITING_BLOCKS.PLATFORM),
		metadata = get(WRITING_BLOCKS.METADATA).toMap(),
		contentHash = get(WRITING_BLOCKS.CONTENT_HASH),
		sourceCreatedAt = get(WRITING_BLOCKS.SOURCE_CREATED_AT)?.toInstant(),
		sourceUpdatedAt = get(WRITING_BLOCKS.SOURCE_UPDATED_AT)?.toInstant(),
		ingestedAt = requireNotNull(get(WRITING_BLOCKS.INGESTED_AT)).toInstant(),
		status = requireNotNull(get(WRITING_BLOCKS.STATUS)),
		createdByUserId = get(WRITING_BLOCKS.CREATED_BY_USER_ID),
		createdAt = requireNotNull(get(WRITING_BLOCKS.CREATED_AT)).toInstant(),
		updatedAt = requireNotNull(get(WRITING_BLOCKS.UPDATED_AT)).toInstant(),
		activitySequence = requireNotNull(get(WRITING_BLOCKS.ACTIVITY_SEQUENCE)),
	)

	private fun JSONB?.toMap(): Map<String, Any?>? = this?.let {
		@Suppress("UNCHECKED_CAST")
		objectMapper.readValue(it.data(), Map::class.java) as Map<String, Any?>
	}
}
