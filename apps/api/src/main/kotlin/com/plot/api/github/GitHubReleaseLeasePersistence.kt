package com.plot.api.github

import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.generated.tables.GithubReleaseDraftRequests.Companion.GITHUB_RELEASE_DRAFT_REQUESTS
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.jooq.DSLContext
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Owns release-request claims, fencing, retries, recovery, and terminal transitions. */
@Component
class GitHubReleaseLeasePersistence(
	private val sqlExecutor: JooqSqlExecutor,
	dslContext: DSLContext,
	private val routineProjection: GitHubReleaseRoutineProjection,
	private val clock: Clock = Clock.systemUTC(),
) : GitHubReleaseLeaseStore {
	private val dsl: DSLContext = dslContext.configuration()
		.derive(dslContext.settings().withRenderSchema(false))
		.dsl()

	private fun requestWorkspaceId(requestId: UUID): UUID =
		sqlExecutor.query(
			"select workspace_id from github_release_draft_requests where id = ?",
			{ row, _ -> requireNotNull(row.getObject("workspace_id", UUID::class.java)) },
			requestId,
		).firstOrNull() ?: throw InvalidDataAccessApiUsageException("Release request was not found")
	@Transactional
	override fun claimNext(workerId: String, now: Instant, leaseTimeout: Duration): GitHubReleaseDraftRequest? {
		val staleBefore = now.minus(leaseTimeout)
		val candidate = sqlExecutor.query(
			"""
			select ${requestColumns}
			from github_release_draft_requests candidate
			where candidate.status in ('QUEUED', 'RESOLVING')
			  and (candidate.next_attempt_at is null or candidate.next_attempt_at <= ?)
			  and (candidate.claimed_by is null or candidate.heartbeat_at is null or candidate.heartbeat_at < ?)
			  and exists (
			    select 1
			    from source_scopes scope
			    where scope.workspace_id = candidate.workspace_id
			      and scope.id = candidate.source_scope_id
			      and scope.status = 'ACTIVE'
			  )
			  and not exists (
			    select 1
			    from github_release_draft_requests predecessor
			    where predecessor.workspace_id = candidate.workspace_id
			      and predecessor.source_scope_id = candidate.source_scope_id
			      and predecessor.routine_id is not distinct from candidate.routine_id
			      and (predecessor.created_at, predecessor.id) < (candidate.created_at, candidate.id)
			      and predecessor.status not in ('READY', 'NO_ACTIVITY', 'NEEDS_RANGE', 'DEFERRED', 'FAILED')
			  )
			order by candidate.created_at, candidate.id
			for update skip locked
			limit 1
			""".trimIndent(),
			{ row, _ -> row.toReleaseDraftRequest() },
			Timestamp.from(now),
			Timestamp.from(staleBefore),
		).firstOrNull() ?: return null

		val updated = dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.STATUS, GitHubReleaseDraftStatus.RESOLVING.name)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.ATTEMPT_COUNT, GITHUB_RELEASE_DRAFT_REQUESTS.ATTEMPT_COUNT.plus(1))
			.set(
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION,
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.plus(1),
			)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_BY, workerId)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_AT, now.toOffsetDateTime())
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.HEARTBEAT_AT, now.toOffsetDateTime())
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.UPDATED_AT, now.toOffsetDateTime())
			.where(
				GITHUB_RELEASE_DRAFT_REQUESTS.WORKSPACE_ID.eq(candidate.workspaceId),
				GITHUB_RELEASE_DRAFT_REQUESTS.ID.eq(candidate.id),
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.eq(candidate.transitionVersion),
			)
			.execute()
		requireExactlyOne(updated, "Release request transition was lost")
		return candidate.copy(
			status = GitHubReleaseDraftStatus.RESOLVING,
			attemptCount = candidate.attemptCount + 1,
			transitionVersion = candidate.transitionVersion + 1,
		)
	}

	override fun renewClaim(requestId: UUID, transitionVersion: Long, workerId: String, now: Instant): Boolean =
		dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.HEARTBEAT_AT, now.toOffsetDateTime())
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.UPDATED_AT, now.toOffsetDateTime())
			.where(
				GITHUB_RELEASE_DRAFT_REQUESTS.WORKSPACE_ID.eq(requestWorkspaceId(requestId)),
				GITHUB_RELEASE_DRAFT_REQUESTS.ID.eq(requestId),
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.eq(transitionVersion),
				GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_BY.eq(workerId),
				GITHUB_RELEASE_DRAFT_REQUESTS.STATUS.`in`(
					GitHubReleaseDraftStatus.RESOLVING.name,
					GitHubReleaseDraftStatus.GENERATING.name,
				),
			)
			.execute() == 1

	@Transactional
	override fun finish(requestId: UUID, transitionVersion: Long, status: GitHubReleaseDraftStatus, errorCode: String?) {
		require(status in terminalStatuses) { "Release request finish status must be terminal" }
		val now = clock.instant()
		val updated = dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.STATUS, status.name)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.ERROR_CODE, errorCode)
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
		routineProjection.finish(requestId, status, errorCode)
	}


	@Transactional
	override fun retry(requestId: UUID, workspaceId: UUID, transitionVersion: Long): GitHubReleaseRetryResult {
		val retry = sqlExecutor.query(
			"""
			select status, generation_attempt
			from github_release_draft_requests request
			where id = ? and workspace_id = ? and transition_version = ? and status = 'FAILED'
			  and exists (
			    select 1 from source_scopes scope where scope.workspace_id = request.workspace_id
			      and scope.id = request.source_scope_id and scope.status = 'ACTIVE'
			  )
			  and (routine_id is null or exists (
			    select 1 from routines routine where routine.workspace_id = request.workspace_id
			      and routine.id = request.routine_id and routine.enabled = true
			  ))
			for update
			""".trimIndent(),
			{ rs, _ -> ReleaseRetryRow(GitHubReleaseDraftStatus.valueOf(requireNotNull(rs.getString("status"))), rs.getInt("generation_attempt")) },
			requestId,
			workspaceId,
			transitionVersion,
		).firstOrNull() ?: throw GitHubReleaseRetryRejectedException()
		val now = clock.instant()
		val nextAttempt = retry.runAttempt + 1
		val updated = dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.STATUS, GitHubReleaseDraftStatus.QUEUED.name)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.GENERATION_ATTEMPT, nextAttempt)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.GENERATION_RUN_ID, null as UUID?)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.AGENT_RUN_ID, null as UUID?)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.ATTEMPT_COUNT, 0)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.ERROR_CODE, null as String?)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.NEXT_ATTEMPT_AT, now.toOffsetDateTime())
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.FINISHED_AT, null as OffsetDateTime?)
			.set(
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION,
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.plus(1),
			)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_BY, null as String?)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_AT, null as OffsetDateTime?)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.HEARTBEAT_AT, null as OffsetDateTime?)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.UPDATED_AT, now.toOffsetDateTime())
			.where(
				GITHUB_RELEASE_DRAFT_REQUESTS.WORKSPACE_ID.eq(workspaceId),
				GITHUB_RELEASE_DRAFT_REQUESTS.ID.eq(requestId),
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.eq(transitionVersion),
				GITHUB_RELEASE_DRAFT_REQUESTS.STATUS.eq(GitHubReleaseDraftStatus.FAILED.name),
			)
			.execute()
		if (updated != 1) throw GitHubReleaseRetryRejectedException()
		return GitHubReleaseRetryResult(requestId = requestId, artifactWorkflowRunId = null, runAttempt = nextAttempt)
	}

	override fun scheduleRetry(requestId: UUID, transitionVersion: Long, nextAttemptAt: Instant, errorCode: String) {
		require(errorCode.isNotBlank()) { "Release retry error code is required" }
		val now = clock.instant()
		val updated = dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.STATUS, GitHubReleaseDraftStatus.QUEUED.name)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.ERROR_CODE, errorCode)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.NEXT_ATTEMPT_AT, nextAttemptAt.toOffsetDateTime())
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.FINISHED_AT, null as OffsetDateTime?)
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
				GITHUB_RELEASE_DRAFT_REQUESTS.STATUS.`in`(
					GitHubReleaseDraftStatus.RESOLVING.name,
					GitHubReleaseDraftStatus.GENERATING.name,
				),
			)
			.execute()
		requireExactlyOne(updated, "Release request transition was lost")
	}

	@Transactional
	override fun selectRange(
		requestId: UUID, workspaceId: UUID, transitionVersion: Long, baseSha: String, headSha: String,
	): GitHubReleaseRetryResult {
		require(baseSha.matches(Regex("[0-9a-f]{40}")) && headSha.matches(Regex("[0-9a-f]{40}"))) {
			"Release boundaries must be full commit SHAs"
		}
		return sqlExecutor.query(
			"""
			update github_release_draft_requests request
			set base_sha = ?, head_sha = ?, observed_head_sha = coalesce(observed_head_sha, ?),
			    boundary_reason = 'EXPLICIT_RANGE', status = 'QUEUED',
			    generation_attempt = generation_attempt + 1, attempt_count = 0, error_code = null,
			    next_attempt_at = now(), finished_at = null, claimed_by = null, claimed_at = null,
			    heartbeat_at = null, transition_version = transition_version + 1, updated_at = now()
			where id = ? and workspace_id = ? and transition_version = ?
			  and status in ('NEEDS_RANGE', 'FAILED') and observation_id is null and agent_run_id is null
			  and (head_sha is null or head_sha = ?)
			  and (observed_head_sha is null or observed_head_sha = ?)
			  and exists (
			    select 1 from source_scopes scope where scope.workspace_id = request.workspace_id
			      and scope.id = request.source_scope_id and scope.status = 'ACTIVE'
			  )
			  and (routine_id is null or exists (
			    select 1 from routines routine where routine.workspace_id = request.workspace_id
			      and routine.id = request.routine_id and routine.enabled = true
			  ))
			returning generation_attempt
			""".trimIndent(),
			{ row, _ -> GitHubReleaseRetryResult(requestId, null, row.getInt("generation_attempt")) },
			baseSha, headSha, headSha, requestId, workspaceId, transitionVersion, headSha, headSha,
		).firstOrNull() ?: throw GitHubReleaseRetryRejectedException()
	}

	@Transactional
	override fun fenceSourceScope(workspaceId: UUID, sourceScopeId: UUID, now: Instant, errorCode: String): Int {
		require(errorCode.isNotBlank()) { "Release fence error code is required" }
		val updated = dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.STATUS, GitHubReleaseDraftStatus.FAILED.name)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.ERROR_CODE, errorCode)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.NEXT_ATTEMPT_AT, null as OffsetDateTime?)
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
				GITHUB_RELEASE_DRAFT_REQUESTS.WORKSPACE_ID.eq(workspaceId),
				GITHUB_RELEASE_DRAFT_REQUESTS.SOURCE_SCOPE_ID.eq(sourceScopeId),
				GITHUB_RELEASE_DRAFT_REQUESTS.STATUS.`in`(
					GitHubReleaseDraftStatus.QUEUED.name,
					GitHubReleaseDraftStatus.RESOLVING.name,
					GitHubReleaseDraftStatus.GENERATING.name,
				),
			)
			.execute()
		sqlExecutor.query(
			"select id from github_release_draft_requests where workspace_id = ? and source_scope_id = ? and status = 'FAILED'",
			{ row, _ -> requireNotNull(row.getObject("id", UUID::class.java)) },
			workspaceId, sourceScopeId,
		).forEach { routineProjection.finish(it, GitHubReleaseDraftStatus.FAILED, errorCode) }
		return updated
	}

	@Transactional
	override fun recoverStaleClaims(now: Instant, leaseTimeout: Duration): Int {
		val staleBefore = now.minus(leaseTimeout)
		val candidates = sqlExecutor.query(
			"""
			select id, transition_version, generation_run_id, agent_run_id
			from github_release_draft_requests
			where claimed_by is not null and (heartbeat_at is null or heartbeat_at < ?)
			  and status in ('QUEUED', 'RESOLVING', 'GENERATING')
			order by heartbeat_at nulls first, id
			for update skip locked
			""".trimIndent(),
			Timestamp.from(staleBefore),
		).map { row ->
			StaleReleaseClaim(
				requestId = requireNotNull(row.getObject("id", UUID::class.java)),
				transitionVersion = row.getLong("transition_version"),
				artifactWorkflowRunId = row.getObject("generation_run_id", UUID::class.java),
				agentRunId = row.getObject("agent_run_id", UUID::class.java),
			)
		}
		return candidates.sumOf { candidate ->
			val recoveredStatus = if (candidate.artifactWorkflowRunId == null && candidate.agentRunId == null) {
				GitHubReleaseDraftStatus.QUEUED.name
			} else {
				GitHubReleaseDraftStatus.GENERATING.name
			}
			val update = dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
				.set(GITHUB_RELEASE_DRAFT_REQUESTS.STATUS, recoveredStatus)
				.set(
					GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION,
					GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.plus(1),
				)
				.set(GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_BY, null as String?)
				.set(GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_AT, null as OffsetDateTime?)
				.set(GITHUB_RELEASE_DRAFT_REQUESTS.HEARTBEAT_AT, null as OffsetDateTime?)
			if (candidate.artifactWorkflowRunId == null && candidate.agentRunId == null) {
				update.set(GITHUB_RELEASE_DRAFT_REQUESTS.NEXT_ATTEMPT_AT, now.toOffsetDateTime())
			}
			val updated = update
				.set(GITHUB_RELEASE_DRAFT_REQUESTS.UPDATED_AT, now.toOffsetDateTime())
				.where(
					GITHUB_RELEASE_DRAFT_REQUESTS.WORKSPACE_ID.eq(requestWorkspaceId(candidate.requestId)),
					GITHUB_RELEASE_DRAFT_REQUESTS.ID.eq(candidate.requestId),
					GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.eq(candidate.transitionVersion),
					GITHUB_RELEASE_DRAFT_REQUESTS.CLAIMED_BY.isNotNull,
					GITHUB_RELEASE_DRAFT_REQUESTS.HEARTBEAT_AT.isNull.or(
						GITHUB_RELEASE_DRAFT_REQUESTS.HEARTBEAT_AT.lt(staleBefore.toOffsetDateTime()),
					),
					GITHUB_RELEASE_DRAFT_REQUESTS.STATUS.`in`(
						GitHubReleaseDraftStatus.QUEUED.name,
						GitHubReleaseDraftStatus.RESOLVING.name,
						GitHubReleaseDraftStatus.GENERATING.name,
					),
				)
				.execute()
			when (updated) {
				0 -> 0
				1 -> 1
				else -> error("Stale release claim recovery updated $updated rows")
			}
		}
	}

	override fun earliestNextAttemptAt(after: Instant): Instant? = sqlExecutor.queryForObject(
		"""
		select min(next_attempt_at)
		from github_release_draft_requests
		where status in ('QUEUED', 'RESOLVING') and next_attempt_at is not null
		""".trimIndent(),
		OffsetDateTime::class.java,
	)?.toInstant()

	override fun recordReconcileDiagnostic(requestId: UUID, transitionVersion: Long, errorCode: String) {
		require(errorCode.length in 1..100 && errorCode.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
			"Release diagnostic error code is invalid"
		}
		val updated = dsl.update(GITHUB_RELEASE_DRAFT_REQUESTS)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.ERROR_CODE, errorCode)
			.set(
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION,
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.plus(1),
			)
			.set(GITHUB_RELEASE_DRAFT_REQUESTS.UPDATED_AT, clock.instant().toOffsetDateTime())
			.where(
				GITHUB_RELEASE_DRAFT_REQUESTS.WORKSPACE_ID.eq(requestWorkspaceId(requestId)),
				GITHUB_RELEASE_DRAFT_REQUESTS.ID.eq(requestId),
				GITHUB_RELEASE_DRAFT_REQUESTS.TRANSITION_VERSION.eq(transitionVersion),
				GITHUB_RELEASE_DRAFT_REQUESTS.STATUS.eq(GitHubReleaseDraftStatus.GENERATING.name),
			)
			.execute()
		requireExactlyOne(updated, "Release request transition was lost")
	}

	private fun requireExactlyOne(updated: Int, message: String) {
		if (updated != 1) throw InvalidDataAccessApiUsageException(message)
	}
}

private val terminalStatuses = setOf(
	GitHubReleaseDraftStatus.READY,
	GitHubReleaseDraftStatus.NO_ACTIVITY,
	GitHubReleaseDraftStatus.DEFERRED,
	GitHubReleaseDraftStatus.NEEDS_RANGE,
	GitHubReleaseDraftStatus.FAILED,
)

private data class StaleReleaseClaim(
	val requestId: UUID,
	val transitionVersion: Long,
	val artifactWorkflowRunId: UUID?,
	val agentRunId: UUID?,
)

private data class ReleaseRetryRow(
	val status: GitHubReleaseDraftStatus,
	val runAttempt: Int,
)

private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
