package com.plot.api.github

import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.SqlRow
import com.plot.api.persistence.generated.tables.ContentPacks.Companion.CONTENT_PACKS
import com.plot.api.persistence.generated.tables.GithubReleaseDraftEvidence.Companion.GITHUB_RELEASE_DRAFT_EVIDENCE
import com.plot.api.persistence.generated.tables.GithubReleaseDraftRequests.Companion.GITHUB_RELEASE_DRAFT_REQUESTS
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.jooq.DSLContext
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Owns release request admission, range/evidence linkage, and activity projections. */
@Component
class GitHubReleaseRequestPersistence(
	private val sqlExecutor: JooqSqlExecutor,
	dslContext: DSLContext,
	private val clock: Clock = Clock.systemUTC(),
) : GitHubReleaseRequestStore {
	private val dsl: DSLContext = dslContext.configuration()
		.derive(dslContext.settings().withRenderSchema(false))
		.dsl()

	override fun findLatest(sourceScopeId: UUID, workspaceId: UUID): GitHubReleaseDraftRequest? = sqlExecutor.query(
		"""
		select ${requestColumns}
		from github_release_draft_requests
		where workspace_id = ? and source_scope_id = ?
		order by created_at desc, id desc
		limit 1
		""".trimIndent(),
		{ rs, _ -> rs.toReleaseDraftRequest() },
		workspaceId,
		sourceScopeId,
	).firstOrNull()

	override fun releaseScopeExists(sourceScopeId: UUID, workspaceId: UUID): Boolean =
		sqlExecutor.queryForObject(
			"""
			select exists(
				select 1 from source_scopes
				where id = ? and workspace_id = ? and provider = 'GITHUB'
				  and scope_kind = 'REPOSITORY' and status = 'ACTIVE'
			)
			""".trimIndent(),
			Boolean::class.java,
			sourceScopeId,
			workspaceId,
		) == true

	override fun findLatestActivity(
		sourceScopeId: UUID,
		workspaceId: UUID,
	): GitHubReleaseActivityRecord? = sqlExecutor.query(
		"""
		select ${activityColumns}
		from github_release_draft_requests r
		left join content_packs cp
		  on cp.workspace_id = r.workspace_id and cp.release_request_id = r.id
		where r.workspace_id = ? and r.source_scope_id = ?
		order by r.created_at desc, r.id desc
		limit 1
		""".trimIndent(),
		{ rs, _ -> rs.toReleaseActivity() },
		workspaceId,
		sourceScopeId,
	).firstOrNull()

	override fun findActivity(
		requestId: UUID,
		sourceScopeId: UUID,
		workspaceId: UUID,
	): GitHubReleaseActivityRecord? = sqlExecutor.query(
		"""
		select ${activityColumns}
		from github_release_draft_requests r
		left join content_packs cp
		  on cp.workspace_id = r.workspace_id and cp.release_request_id = r.id
		where r.id = ? and r.workspace_id = ? and r.source_scope_id = ?
		""".trimIndent(),
		{ rs, _ -> rs.toReleaseActivity() },
		requestId,
		workspaceId,
		sourceScopeId,
	).firstOrNull()

	override fun findPreviousBoundaries(
		workspaceId: UUID,
		sourceScopeId: UUID,
		excludingRequestId: UUID,
	): List<GitHubReleaseDraftRequest> = sqlExecutor.query(
		"""
		select ${requestColumns}
		from github_release_draft_requests
		where workspace_id = ? and source_scope_id = ? and id <> ?
		  and head_sha is not null
		  and (created_at, id) < (
		    select created_at, id from github_release_draft_requests where id = ?
		  )
		order by created_at desc, id desc
		""".trimIndent(),
		{ rs, _ -> rs.toReleaseDraftRequest() },
		workspaceId,
		sourceScopeId,
		excludingRequestId,
		excludingRequestId,
	)

	override fun findBoundEvidence(requestId: UUID): GitHubReleaseEvidence? {
		val observationId = sqlExecutor.query(
			"select observation_id from github_release_draft_requests where id = ?",
			{ rs, _ -> rs.getObject(1, UUID::class.java) },
			requestId,
		).firstOrNull() ?: return null
		val writingBlockIds = sqlExecutor.query(
			"""
			select writing_block_id
			from github_release_draft_evidence
			where request_id = ? and observation_id = ?
			order by order_index
			""".trimIndent(),
			{ rs, _ -> rs.getObject(1, UUID::class.java) },
			requestId,
			observationId,
		).map(::requireNotNull)
		check(writingBlockIds.isNotEmpty()) { "Release request has an observation without bound evidence" }
		return GitHubReleaseEvidence(observationId, writingBlockIds)
	}

	override fun findGenerating(limit: Int): List<GitHubReleaseDraftRequest> {
		require(limit > 0) { "Generating request limit must be positive" }
		return sqlExecutor.query(
			"""
			select ${requestColumns}
			from github_release_draft_requests
			where status = 'GENERATING'
			order by updated_at, id
			limit ?
			""".trimIndent(),
			{ rs, _ -> rs.toReleaseDraftRequest() },
			limit,
		)
	}

	fun findRequest(id: UUID): GitHubReleaseDraftRequest? = sqlExecutor.query(
		"select ${requestColumns} from github_release_draft_requests where id = ?",
		{ rs, _ -> rs.toReleaseDraftRequest() },
		id,
	).firstOrNull()

	fun findRequest(
		workspaceId: UUID,
		sourceScopeId: UUID,
		tagName: String,
	): GitHubReleaseDraftRequest? = sqlExecutor.query(
		"""
		select ${requestColumns} from github_release_draft_requests
		where workspace_id = ? and source_scope_id = ? and tag_name = ?
		""".trimIndent(),
		{ rs, _ -> rs.toReleaseDraftRequest() },
		workspaceId,
		sourceScopeId,
		tagName,
	).firstOrNull()

	override fun enqueueRelease(
		workspaceId: UUID,
		sourceScopeId: UUID,
		deliveryId: UUID,
		tagName: String,
		observedHeadSha: String?,
	): GitHubReleaseDraftRequest {
		val id = UUID.randomUUID()
		val now = clock.instant()
		val upserted = sqlExecutor.query(
			"""
			insert into github_release_draft_requests
			(id, workspace_id, source_scope_id, initial_delivery_id, tag_name, observed_head_sha,
			 status, created_at, updated_at)
			values (?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
			on conflict (workspace_id, source_scope_id, tag_name) do update
			set observed_head_sha = coalesce(
					github_release_draft_requests.observed_head_sha,
					excluded.observed_head_sha
				),
				updated_at = excluded.updated_at
			where github_release_draft_requests.observed_head_sha is null
			   or excluded.observed_head_sha is null
			   or github_release_draft_requests.observed_head_sha = excluded.observed_head_sha
			returning ${requestColumns}
			""".trimIndent(),
			{ rs, _ -> rs.toReleaseDraftRequest() },
			id, workspaceId, sourceScopeId, deliveryId, tagName, observedHeadSha,
			Timestamp.from(now), Timestamp.from(now),
		).firstOrNull()
		if (upserted != null) return upserted
		val existing = findRequest(workspaceId, sourceScopeId, tagName)
			?: throw IllegalStateException("Release request was not found after a conflicted insert")
		if (observedHeadSha != null && existing.observedHeadSha != null && existing.observedHeadSha != observedHeadSha) {
			throw GitHubReleasePermanentException("GITHUB_TAG_MOVED")
		}
		return existing
	}

	override fun saveResolvedRange(requestId: UUID, transitionVersion: Long, baseSha: String, headSha: String, boundaryReason: String) {
		val now = clock.instant()
		val updated = dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.BASE_SHA, baseSha)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.HEAD_SHA, headSha)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.BOUNDARY_REASON, boundaryReason)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.STATUS, GitHubReleaseDraftStatus.GENERATING.name)
			.set(
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION,
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.plus(1),
			)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.HEARTBEAT_AT, now.toOffsetDateTime())
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.UPDATED_AT, now.toOffsetDateTime())
			.where(
				GITHUB_RELEASE_DRAFT_REQUESTS.WORKSPACE_ID.eq(requestWorkspaceId(requestId)),
				GITHUB_RELEASE_DRAFT_REQUESTS.ID.eq(requestId),
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.eq(transitionVersion),
			)
			.execute()
		requireExactlyOne(updated, "Release request transition was lost")
	}

	override fun saveHeadAndFinishNeedsRange(requestId: UUID, transitionVersion: Long, headSha: String) {
		val now = clock.instant()
		val updated = dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.HEAD_SHA, headSha)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.STATUS, GitHubReleaseDraftStatus.NEEDS_RANGE.name)
			.set(
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION,
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.plus(1),
			)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_BY, null as String?)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_AT, null as OffsetDateTime?)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.HEARTBEAT_AT, null as OffsetDateTime?)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.FINISHED_AT, now.toOffsetDateTime())
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.UPDATED_AT, now.toOffsetDateTime())
			.where(
				GITHUB_RELEASE_DRAFT_REQUESTS.WORKSPACE_ID.eq(requestWorkspaceId(requestId)),
				GITHUB_RELEASE_DRAFT_REQUESTS.ID.eq(requestId),
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.eq(transitionVersion),
			)
			.execute()
		requireExactlyOne(updated, "Release request transition was lost")
	}

	override fun linkAgentRun(requestId: UUID, transitionVersion: Long, observationId: UUID, agentRunId: UUID) {
		val now = clock.instant()
		val updated = dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.AGENT_RUN_ID, agentRunId)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.STATUS, GitHubReleaseDraftStatus.GENERATING.name)
			.set(
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION,
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.plus(1),
			)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_BY, null as String?)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_AT, null as OffsetDateTime?)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.HEARTBEAT_AT, null as OffsetDateTime?)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.UPDATED_AT, now.toOffsetDateTime())
			.where(
				GITHUB_RELEASE_DRAFT_REQUESTS.WORKSPACE_ID.eq(requestWorkspaceId(requestId)),
				GITHUB_RELEASE_DRAFT_REQUESTS.ID.eq(requestId),
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.eq(transitionVersion),
				GITHUB_RELEASE_DRAFT_REQUESTS.OBSERVATION_ID.eq(observationId),
				GITHUB_RELEASE_DRAFT_REQUESTS.AGENT_RUN_ID.isNull,
				GITHUB_RELEASE_DRAFT_REQUESTS.GENERATION_RUN_ID.isNull,
			)
			.execute()
		requireExactlyOne(updated, "Release Agent run transition was lost")
	}

	@Transactional
	override fun linkAgentArtifact(requestId: UUID, transitionVersion: Long, agentRunId: UUID, artifactWorkflowRunId: UUID) {
		val now = clock.instant()
		val updated = dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.GENERATION_RUN_ID, artifactWorkflowRunId)
			.set(
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION,
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.plus(1),
			)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.UPDATED_AT, now.toOffsetDateTime())
			.where(
				GITHUB_RELEASE_DRAFT_REQUESTS.WORKSPACE_ID.eq(requestWorkspaceId(requestId)),
				GITHUB_RELEASE_DRAFT_REQUESTS.ID.eq(requestId),
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.eq(transitionVersion),
				GITHUB_RELEASE_DRAFT_REQUESTS.AGENT_RUN_ID.eq(agentRunId),
				GITHUB_RELEASE_DRAFT_REQUESTS.GENERATION_RUN_ID.isNull,
			)
			.execute()
		requireExactlyOne(updated, "Release Artifact workflow transition was lost")
		val linkedPack = dsl.update(CONTENT_PACKS)
			.set(CONTENT_PACKS.RELEASE_REQUEST_ID, requestId)
			.where(
				CONTENT_PACKS.WORKSPACE_ID.eq(requestWorkspaceId(requestId)),
				CONTENT_PACKS.GENERATION_RUN_ID.eq(artifactWorkflowRunId),
				CONTENT_PACKS.RELEASE_REQUEST_ID.isNull.or(CONTENT_PACKS.RELEASE_REQUEST_ID.eq(requestId)),
			)
			.execute()
		requireExactlyOne(linkedPack, "Release Artifact materialization was not found")
	}

	@Transactional
	override fun bindEvidence(requestId: UUID, transitionVersion: Long, evidence: GitHubReleaseEvidence) {
		require(evidence.writingBlockIds.isNotEmpty()) { "Release evidence binding cannot be empty" }
		require(evidence.writingBlockIds.distinct().size == evidence.writingBlockIds.size) {
			"Release evidence binding IDs must be unique"
		}
		val now = clock.instant()
		val workspaceId = requestWorkspaceId(requestId)
		val updated = dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.OBSERVATION_ID, evidence.observationId)
			.set(
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION,
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.plus(1),
			)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.HEARTBEAT_AT, now.toOffsetDateTime())
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.UPDATED_AT, now.toOffsetDateTime())
			.where(
				GITHUB_RELEASE_DRAFT_REQUESTS.WORKSPACE_ID.eq(workspaceId),
				GITHUB_RELEASE_DRAFT_REQUESTS.ID.eq(requestId),
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.eq(transitionVersion),
				GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_BY.isNotNull,
				GITHUB_RELEASE_DRAFT_REQUESTS.OBSERVATION_ID.isNull,
				GITHUB_RELEASE_DRAFT_REQUESTS.GENERATION_RUN_ID.isNull,
			)
			.execute()
		requireExactlyOne(updated, "Release request transition was lost")
		evidence.writingBlockIds.forEachIndexed { index, writingBlockId ->
			val inserted = dsl.insertInto(GITHUB_RELEASE_DRAFT_EVIDENCE)
				.set(GITHUB_RELEASE_DRAFT_EVIDENCE.REQUEST_ID, requestId)
				.set(GITHUB_RELEASE_DRAFT_EVIDENCE.WORKSPACE_ID, workspaceId)
				.set(GITHUB_RELEASE_DRAFT_EVIDENCE.OBSERVATION_ID, evidence.observationId)
				.set(GITHUB_RELEASE_DRAFT_EVIDENCE.WRITING_BLOCK_ID, writingBlockId)
				.set(GITHUB_RELEASE_DRAFT_EVIDENCE.ORDER_INDEX, index)
				.execute()
			requireExactlyOne(inserted, "Release evidence binding was not inserted")
		}
	}

	private fun requestWorkspaceId(requestId: UUID): UUID =
		sqlExecutor.query(
			"select workspace_id from github_release_draft_requests where id = ?",
			{ row, _ -> requireNotNull(row.getObject("workspace_id", UUID::class.java)) },
			requestId,
		).firstOrNull() ?: throw InvalidDataAccessApiUsageException("Release request was not found")

	private fun requireExactlyOne(updated: Int, message: String) {
		if (updated != 1) throw InvalidDataAccessApiUsageException(message)
	}

}


internal val requestColumns = """
id, workspace_id, source_scope_id, initial_delivery_id, tag_name, observed_head_sha,
base_sha, head_sha, boundary_reason,
status, attempt_count, generation_attempt, transition_version, agent_run_id, generation_run_id, observation_id, error_code
""".trimIndent()

internal val activityColumns = """
r.id, r.source_scope_id, r.tag_name, r.status, r.base_sha, r.head_sha, r.boundary_reason,
cp.id as content_pack_id, r.error_code, r.transition_version, r.created_at, r.updated_at
""".trimIndent()

internal fun SqlRow.toReleaseDraftRequest(): GitHubReleaseDraftRequest = GitHubReleaseDraftRequest(
	id = requireNotNull(getObject("id", UUID::class.java)),
	workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java)),
	sourceScopeId = requireNotNull(getObject("source_scope_id", UUID::class.java)),
	initialDeliveryId = requireNotNull(getObject("initial_delivery_id", UUID::class.java)),
	tagName = requireNotNull(getString("tag_name")),
	baseSha = getString("base_sha"),
	headSha = getString("head_sha"),
	boundaryReason = getString("boundary_reason"),
	status = GitHubReleaseDraftStatus.valueOf(requireNotNull(getString("status"))),
	attemptCount = getInt("attempt_count"),
	transitionVersion = getLong("transition_version"),
	agentRunId = getObject("agent_run_id", UUID::class.java),
	artifactWorkflowRunId = getObject("generation_run_id", UUID::class.java),
	observationId = getObject("observation_id", UUID::class.java),
	errorCode = getString("error_code"),
	runAttempt = getInt("generation_attempt"),
	observedHeadSha = getString("observed_head_sha"),
)

internal fun SqlRow.toReleaseActivity(): GitHubReleaseActivityRecord = GitHubReleaseActivityRecord(
	id = requireNotNull(getObject("id", UUID::class.java)),
	sourceScopeId = requireNotNull(getObject("source_scope_id", UUID::class.java)),
	tagName = requireNotNull(getString("tag_name")),
	status = GitHubReleaseDraftStatus.valueOf(requireNotNull(getString("status"))),
	baseSha = getString("base_sha"),
	headSha = getString("head_sha"),
	boundaryReason = getString("boundary_reason"),
	artifactId = getObject("content_pack_id", UUID::class.java),
	errorCode = getString("error_code"),
	transitionVersion = getLong("transition_version"),
	createdAt = requireNotNull(getTimestamp("created_at")).toInstant(),
	updatedAt = requireNotNull(getTimestamp("updated_at")).toInstant(),
)

private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
