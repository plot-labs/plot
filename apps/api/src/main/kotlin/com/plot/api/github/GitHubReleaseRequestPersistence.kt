package com.plot.api.github

import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.SqlRow
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Owns release request admission, range/evidence linkage, and activity projections. */
@Component
class GitHubReleaseRequestPersistence(
	private val sqlExecutor: SqlExecutor,
	private val routineProjection: GitHubReleaseRoutineProjection,
	private val clock: Clock = Clock.systemUTC(),
) : GitHubReleaseRequestStore {
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
		  and tag_name <> (select tag_name from github_release_draft_requests where id = ?)
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

	override fun hasGeneratingRequestForAgentRun(workspaceId: UUID, agentRunId: UUID): Boolean =
		sqlExecutor.queryForObject(
			"""
			select exists(
				select 1
				from github_release_draft_requests
				where workspace_id = ? and agent_run_id = ? and status = 'GENERATING'
			)
			""".trimIndent(),
			Boolean::class.java,
			workspaceId,
			agentRunId,
		) ?: false

	fun findRequest(id: UUID): GitHubReleaseDraftRequest? = sqlExecutor.query(
		"select ${requestColumns} from github_release_draft_requests where id = ?",
		{ rs, _ -> rs.toReleaseDraftRequest() },
		id,
	).firstOrNull()

	fun findRequest(
		workspaceId: UUID,
		sourceScopeId: UUID,
		tagName: String,
		routineId: UUID? = null,
	): GitHubReleaseDraftRequest? = sqlExecutor.query(
		"""
		select ${requestColumns} from github_release_draft_requests
		where workspace_id = ? and source_scope_id = ? and tag_name = ? and routine_id is not distinct from ?
		""".trimIndent(),
		{ rs, _ -> rs.toReleaseDraftRequest() },
		workspaceId,
		sourceScopeId,
		tagName,
		routineId,
	).firstOrNull()

	@Transactional
	override fun enqueueRelease(
		workspaceId: UUID,
		sourceScopeId: UUID,
		deliveryId: UUID,
		tagName: String,
		observedHeadSha: String?,
	): GitHubReleaseDraftRequest = enqueue(workspaceId, sourceScopeId, deliveryId, tagName, observedHeadSha, null)

	@Transactional
	override fun enqueueRoutineRelease(
		workspaceId: UUID,
		sourceScopeId: UUID,
		deliveryId: UUID,
		tagName: String,
		observedHeadSha: String?,
		routineId: UUID,
	): GitHubReleaseDraftRequest = enqueue(workspaceId, sourceScopeId, deliveryId, tagName, observedHeadSha, routineId)

	private fun enqueue(
		workspaceId: UUID,
		sourceScopeId: UUID,
		deliveryId: UUID,
		tagName: String,
		observedHeadSha: String?,
		routineId: UUID?,
	): GitHubReleaseDraftRequest {
		val id = UUID.randomUUID()
		val now = clock.instant()
		sqlExecutor.query(
			"select pg_advisory_xact_lock(hashtextextended(?, 0))",
			{ _, _ -> Unit },
			"github-release:$workspaceId:$sourceScopeId:$tagName",
		)
		// The first event chooses default automation or configured Routines for this
		// real tag. A configuration change between tag/release deliveries cannot add a fallback draft.
		val oppositeJob = sqlExecutor.query(
			"""
			select $requestColumns from github_release_draft_requests
			where workspace_id = ? and source_scope_id = ? and tag_name = ?
			  and (routine_id is null) = ?
			order by created_at, id limit 1
			""".trimIndent(),
			{ row, _ -> row.toReleaseDraftRequest() },
			workspaceId, sourceScopeId, tagName, routineId != null,
		).firstOrNull()
		if (oppositeJob != null) return oppositeJob
		val identity = if (routineId == null) {
			"(workspace_id, source_scope_id, tag_name) where routine_id is null"
		} else {
			"(workspace_id, source_scope_id, tag_name, routine_id) where routine_id is not null"
		}
		val observed = observedHeadSha ?: sqlExecutor.query(
			"""
			select tag.after_sha from github_webhook_deliveries tag
			join github_webhook_deliveries initial on initial.id = ?
			  and initial.installation_id = tag.installation_id and initial.repository_id = tag.repository_id
			where tag.event_type = 'push' and tag.tag_name = ? and tag.after_sha is not null
			  and tag.ref_deleted is not true and tag.forced is not true
			order by tag.received_at, tag.id limit 1
			""".trimIndent(),
			{ row, _ -> row.getString("after_sha") },
			deliveryId, tagName,
		).firstOrNull()
		val upserted = sqlExecutor.query(
			"""
			insert into github_release_draft_requests
			(id, workspace_id, source_scope_id, initial_delivery_id, tag_name, observed_head_sha, routine_id,
			 status, created_at, updated_at)
			values (?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
			on conflict $identity do update
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
			id, workspaceId, sourceScopeId, deliveryId, tagName, observed, routineId,
			Timestamp.from(now), Timestamp.from(now),
		).firstOrNull()
		if (upserted != null) return upserted
		val existing = findRequest(workspaceId, sourceScopeId, tagName, routineId)
			?: throw IllegalStateException("Release request was not found after a conflicted insert")
		if (observed != null && existing.observedHeadSha != null && existing.observedHeadSha != observed) {
			throw GitHubReleasePermanentException("GITHUB_TAG_MOVED")
		}
		return existing
	}

	override fun observeTag(workspaceId: UUID, sourceScopeId: UUID, tagName: String, headSha: String) {
		sqlExecutor.update(
			"""
			update github_release_draft_requests
			set observed_head_sha = coalesce(observed_head_sha, ?)
			where workspace_id = ? and source_scope_id = ? and tag_name = ?
			""".trimIndent(),
			headSha, workspaceId, sourceScopeId, tagName,
		)
	}

	override fun saveResolvedRange(requestId: UUID, transitionVersion: Long, baseSha: String, headSha: String, boundaryReason: String) {
		val now = clock.instant()
		val updated = sqlExecutor.update(
			"""
			update github_release_draft_requests
			set base_sha = ?, head_sha = ?, boundary_reason = ?, status = 'GENERATING',
			    transition_version = transition_version + 1, heartbeat_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ?
			""".trimIndent(),
			baseSha, headSha, boundaryReason, Timestamp.from(now), Timestamp.from(now),
			requestWorkspaceId(requestId), requestId, transitionVersion,
		)
		requireExactlyOne(updated, "Release request transition was lost")
	}

	@Transactional
	override fun saveHeadAndFinishNeedsRange(requestId: UUID, transitionVersion: Long, headSha: String) {
		val now = clock.instant()
		val updated = sqlExecutor.update(
			"""
			update github_release_draft_requests
			set head_sha = ?, status = 'NEEDS_RANGE', transition_version = transition_version + 1,
			    claimed_by = null, claimed_at = null, heartbeat_at = null, finished_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ?
			""".trimIndent(),
			headSha, Timestamp.from(now), Timestamp.from(now), requestWorkspaceId(requestId), requestId, transitionVersion,
		)
		requireExactlyOne(updated, "Release request transition was lost")
		routineProjection.finish(requestId, GitHubReleaseDraftStatus.NEEDS_RANGE)
	}

	override fun linkAgentRun(requestId: UUID, transitionVersion: Long, observationId: UUID, agentRunId: UUID) {
		val now = clock.instant()
		val updated = sqlExecutor.update(
			"""
			update github_release_draft_requests
			set agent_run_id = ?, status = 'GENERATING', transition_version = transition_version + 1,
			    claimed_by = null, claimed_at = null, heartbeat_at = null, updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ? and observation_id = ?
			  and agent_run_id is null and generation_run_id is null
			""".trimIndent(),
			agentRunId, Timestamp.from(now), requestWorkspaceId(requestId), requestId, transitionVersion, observationId,
		)
		requireExactlyOne(updated, "Release Agent run transition was lost")
	}

	@Transactional
	override fun linkAgentArtifact(requestId: UUID, transitionVersion: Long, agentRunId: UUID, artifactWorkflowRunId: UUID) {
		val now = clock.instant()
		val workspaceId = requestWorkspaceId(requestId)
		val updated = sqlExecutor.update(
			"""
			update github_release_draft_requests
			set generation_run_id = ?, transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ?
			  and agent_run_id = ? and generation_run_id is null
			""".trimIndent(),
			artifactWorkflowRunId, Timestamp.from(now), workspaceId, requestId, transitionVersion, agentRunId,
		)
		requireExactlyOne(updated, "Release Artifact workflow transition was lost")
		val linkedPack = sqlExecutor.update(
			"""
			update content_packs set release_request_id = ?
			where workspace_id = ? and generation_run_id = ?
			  and (release_request_id is null or release_request_id = ?)
			""".trimIndent(),
			requestId, workspaceId, artifactWorkflowRunId, requestId,
		)
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
		val updated = sqlExecutor.update(
			"""
			update github_release_draft_requests
			set observation_id = ?, transition_version = transition_version + 1, heartbeat_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ? and claimed_by is not null
			  and observation_id is null and generation_run_id is null
			""".trimIndent(),
			evidence.observationId, Timestamp.from(now), Timestamp.from(now), workspaceId, requestId, transitionVersion,
		)
		requireExactlyOne(updated, "Release request transition was lost")
		evidence.writingBlockIds.forEachIndexed { index, writingBlockId ->
			val inserted = sqlExecutor.update(
				"""
				insert into github_release_draft_evidence
				(request_id, workspace_id, observation_id, writing_block_id, order_index)
				values (?, ?, ?, ?, ?)
				""".trimIndent(),
				requestId, workspaceId, evidence.observationId, writingBlockId, index,
			)
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
id, workspace_id, source_scope_id, initial_delivery_id, tag_name, observed_head_sha, routine_id,
base_sha, head_sha, boundary_reason,
status, attempt_count, generation_attempt, transition_version, agent_run_id, generation_run_id, observation_id, error_code
""".trimIndent()

internal val activityColumns = """
r.id, r.source_scope_id, r.tag_name, r.status, r.base_sha, r.head_sha, r.boundary_reason,
cp.id as content_pack_id, r.error_code, r.transition_version, r.created_at, r.updated_at, r.agent_run_id
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
	routineId = getObject("routine_id", UUID::class.java),
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
	agentRunId = getObject("agent_run_id", UUID::class.java),
)
