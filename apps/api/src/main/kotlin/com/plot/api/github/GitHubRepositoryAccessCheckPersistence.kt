package com.plot.api.github

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

enum class GitHubAccessCheckTrigger {
	LIFECYCLE_EVENT,
	RETRY,
	CHECK_AGAIN,
}

enum class GitHubAccessCheckStatus {
	QUEUED,
	CHECKING,
	VERIFIED,
	FAILED,
}

data class GitHubRepositoryAccessCheckRecord(
	val id: UUID,
	val workspaceId: UUID,
	val connectionId: UUID,
	val sourceScopeId: UUID,
	val trigger: GitHubAccessCheckTrigger,
	val status: GitHubAccessCheckStatus,
	val attemptCount: Int,
	val transitionVersion: Long,
	val claimedBy: String?,
	val claimedAt: Instant?,
	val nextAttemptAt: Instant?,
	val errorCode: String?,
	val verifiedAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

data class GitHubRepositoryAccessCheckWorkItem(
	val check: GitHubRepositoryAccessCheckRecord,
	val installationId: Long,
	val repositoryId: Long,
	val owner: String,
	val repository: String,
)

class GitHubAccessCheckClaimLostException : DataAccessException("GitHub access check claim was lost")

@Repository
class GitHubRepositoryAccessCheckPersistence(
	private val dsl: DSLContext,
) {
	fun find(workspaceId: UUID, sourceScopeId: UUID): GitHubRepositoryAccessCheckRecord? = fetchRows(
		"""
		select $columns
		from github_repository_access_checks ac
		where ac.workspace_id = ? and ac.source_scope_id = ?
		""".trimIndent(),
		workspaceId,
		sourceScopeId,
	).firstOrNull()?.toAccessCheck()

	fun queue(
		workspaceId: UUID,
		connectionId: UUID,
		sourceScopeId: UUID,
		trigger: GitHubAccessCheckTrigger,
		now: Instant,
	) {
		execute(
			"""
			insert into github_repository_access_checks (
			  id, workspace_id, connection_id, source_scope_id, trigger, status,
			  attempt_count, transition_version, created_at, updated_at
			) values (?, ?, ?, ?, ?, 'QUEUED', 0, 0, ?, ?)
			on conflict (workspace_id, source_scope_id) do update set
			  connection_id = excluded.connection_id,
			  trigger = excluded.trigger,
			  status = case
			    when github_repository_access_checks.status = 'CHECKING' then github_repository_access_checks.status
			    else 'QUEUED'
			  end,
			  attempt_count = case
			    when github_repository_access_checks.status = 'CHECKING' then github_repository_access_checks.attempt_count
			    else 0
			  end,
			  transition_version = github_repository_access_checks.transition_version + case
			    when github_repository_access_checks.status = 'CHECKING' then 0 else 1
			  end,
			  claimed_by = case
			    when github_repository_access_checks.status = 'CHECKING' then github_repository_access_checks.claimed_by
			    else null
			  end,
			  claimed_at = case
			    when github_repository_access_checks.status = 'CHECKING' then github_repository_access_checks.claimed_at
			    else null
			  end,
			  next_attempt_at = null,
			  error_code = null,
			  verified_at = null,
			  updated_at = excluded.updated_at
			""".trimIndent(),
			UUID.randomUUID(), workspaceId, connectionId, sourceScopeId, trigger.name,
			Timestamp.from(now), Timestamp.from(now),
		)
	}

	@Transactional
	fun claimNext(
		workerId: String,
		now: Instant,
	): GitHubRepositoryAccessCheckWorkItem? {
		val candidate = fetchRows(
			"""
			select $columns,
			       c.external_connection_key as installation_key,
			       sc.external_scope_key as repository_key,
			       sc.external_key
			from github_repository_access_checks ac
			join connections c on c.workspace_id = ac.workspace_id and c.id = ac.connection_id
			join source_scopes sc on sc.workspace_id = ac.workspace_id and sc.id = ac.source_scope_id
			where ac.status = 'QUEUED'
			  and (ac.next_attempt_at is null or ac.next_attempt_at <= ?)
			order by ac.created_at, ac.id
			for update of ac skip locked
			limit 1
			""".trimIndent(),
			Timestamp.from(now),
		).firstOrNull()?.toAccessCheckCandidate() ?: return null
		val updated = execute(
			"""
			update github_repository_access_checks
			set status = 'CHECKING', attempt_count = attempt_count + 1,
			    transition_version = transition_version + 1,
			    claimed_by = ?, claimed_at = ?, next_attempt_at = null, updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ? and status = 'QUEUED'
			""".trimIndent(),
			workerId,
			Timestamp.from(now),
			Timestamp.from(now),
			candidate.check.workspaceId,
			candidate.check.id,
			candidate.check.transitionVersion,
		)
		if (updated != 1) return null
		return candidate.copy(
			check = candidate.check.copy(
				status = GitHubAccessCheckStatus.CHECKING,
				attemptCount = candidate.check.attemptCount + 1,
				transitionVersion = candidate.check.transitionVersion + 1,
				claimedBy = workerId,
				claimedAt = now,
				nextAttemptAt = null,
				updatedAt = now,
			),
		)
	}

	/** Earliest queued retry that becomes eligible after [after], or null when none is pending. */
	fun earliestNextAttemptAt(after: Instant): Instant? = fetchRows(
		"""
		select min(next_attempt_at) as next_attempt_at
		from github_repository_access_checks
		where status = 'QUEUED' and next_attempt_at > ?
		""".trimIndent(),
		Timestamp.from(after),
	).firstOrNull()?.get("next_attempt_at", OffsetDateTime::class.java)?.toInstant()

	fun recoverStaleClaims(
		now: Instant,
		leaseTimeout: Duration,
		maxAttempts: Int,
	): Int {
		require(maxAttempts > 0) { "Access check max attempts must be positive" }
		return execute(
			"""
			update github_repository_access_checks
			set status = case when attempt_count >= ? then 'FAILED' else 'QUEUED' end,
			    error_code = case when attempt_count >= ? then 'ACCESS_CHECK_STALE_CLAIM_LIMIT'
			      else 'ACCESS_CHECK_STALE_CLAIM' end,
			    claimed_by = null, claimed_at = null,
			    next_attempt_at = case when attempt_count >= ? then null else cast(? as timestamptz) end,
			    transition_version = transition_version + 1, updated_at = ?
			where status = 'CHECKING' and claimed_at < ?
			""".trimIndent(),
			maxAttempts,
			maxAttempts,
			maxAttempts,
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now.minus(leaseTimeout)),
		)
	}

	fun scheduleRetry(
		item: GitHubRepositoryAccessCheckWorkItem,
		nextAttemptAt: Instant,
		errorCode: String,
		now: Instant,
	) {
		val updated = execute(
			"""
			update github_repository_access_checks
			set status = 'QUEUED', error_code = ?, next_attempt_at = ?,
			    claimed_by = null, claimed_at = null,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ?
			  and claimed_by = ? and status = 'CHECKING'
			""".trimIndent(),
			errorCode,
			Timestamp.from(nextAttemptAt),
			Timestamp.from(now),
			item.check.workspaceId,
			item.check.id,
			item.check.transitionVersion,
			item.check.claimedBy,
		)
		if (updated != 1) throw GitHubAccessCheckClaimLostException()
	}

	fun fail(
		item: GitHubRepositoryAccessCheckWorkItem,
		errorCode: String,
		now: Instant,
	) {
		val updated = execute(
			"""
			update github_repository_access_checks
			set status = 'FAILED', error_code = ?, next_attempt_at = null,
			    claimed_by = null, claimed_at = null,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ?
			  and claimed_by = ? and status = 'CHECKING'
			""".trimIndent(),
			errorCode,
			Timestamp.from(now),
			item.check.workspaceId,
			item.check.id,
			item.check.transitionVersion,
			item.check.claimedBy,
		)
		if (updated != 1) throw GitHubAccessCheckClaimLostException()
	}

	@Transactional
	fun completeVerified(
		item: GitHubRepositoryAccessCheckWorkItem,
		repository: GitHubRepository,
		now: Instant,
	) {
		val checked = execute(
			"""
			update github_repository_access_checks
			set status = 'VERIFIED', error_code = null, verified_at = ?,
			    claimed_by = null, claimed_at = null, next_attempt_at = null,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ?
			  and claimed_by = ? and status = 'CHECKING'
			""".trimIndent(),
			Timestamp.from(now),
			Timestamp.from(now),
			item.check.workspaceId,
			item.check.id,
			item.check.transitionVersion,
			item.check.claimedBy,
		)
		if (checked != 1) throw GitHubAccessCheckClaimLostException()

		val namespaceId = fetchRows(
			"select source_namespace_id from source_scopes where workspace_id = ? and id = ?",
			item.check.workspaceId,
			item.check.sourceScopeId,
		).firstOrNull()?.get("source_namespace_id", UUID::class.java)
			?: throw GitHubAccessCheckClaimLostException()
		execute(
			"""
			update connections
			set status = 'ACTIVE', status_reason = null, status_changed_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and provider = 'GITHUB'
			""".trimIndent(),
			Timestamp.from(now),
			Timestamp.from(now),
			item.check.workspaceId,
			item.check.connectionId,
		)
		execute(
			"""
			update source_namespaces
			set status = 'ACTIVE', display_name = ?, updated_at = ?
			where workspace_id = ? and id = ? and provider = 'GITHUB'
			""".trimIndent(),
			"${repository.owner}/${repository.name}",
			Timestamp.from(now),
			item.check.workspaceId,
			namespaceId,
		)
		execute(
			"""
			update connection_namespace_bindings
			set status = 'ACTIVE', valid_to = null, updated_at = ?
			where workspace_id = ? and connection_id = ? and source_namespace_id = ?
			""".trimIndent(),
			Timestamp.from(now),
			item.check.workspaceId,
			item.check.connectionId,
			namespaceId,
		)
		execute(
			"""
			update source_scopes
			set status = 'ACTIVE', status_reason = null, status_changed_at = ?,
			    external_key = ?, display_name = ?, url = ?,
			    metadata = jsonb_build_object('repositoryId', ?, 'defaultBranch', ?), updated_at = ?
			where workspace_id = ? and id = ? and provider = 'GITHUB'
			""".trimIndent(),
			Timestamp.from(now),
			"${repository.owner}/${repository.name}",
			"${repository.owner}/${repository.name}",
			repository.url,
			repository.id,
			repository.defaultBranch,
			Timestamp.from(now),
			item.check.workspaceId,
			item.check.sourceScopeId,
		)
	}

	fun fence(workspaceId: UUID, sourceScopeId: UUID, now: Instant): Int = execute(
		"""
		update github_repository_access_checks
		set status = 'FAILED', error_code = 'SOURCE_ACCESS_LOST',
		    claimed_by = null, claimed_at = null, next_attempt_at = null,
		    transition_version = transition_version + 1, updated_at = ?
		where workspace_id = ? and source_scope_id = ?
		  and status in ('QUEUED', 'CHECKING')
		""".trimIndent(),
		Timestamp.from(now), workspaceId, sourceScopeId,
	)

	private fun Record.toAccessCheckCandidate(): GitHubRepositoryAccessCheckWorkItem {
		val externalKey = get("external_key", String::class.java).orEmpty()
		return GitHubRepositoryAccessCheckWorkItem(
			check = toAccessCheck(),
			installationId = get("installation_key", String::class.java).toLongOrNull() ?: 0L,
			repositoryId = get("repository_key", String::class.java).toLongOrNull() ?: 0L,
			owner = externalKey.substringBefore('/'),
			repository = externalKey.substringAfter('/', ""),
		)
	}

	private fun Record.toAccessCheck() = GitHubRepositoryAccessCheckRecord(
		id = requireNotNull(get("id", UUID::class.java)),
		workspaceId = requireNotNull(get("workspace_id", UUID::class.java)),
		connectionId = requireNotNull(get("connection_id", UUID::class.java)),
		sourceScopeId = requireNotNull(get("source_scope_id", UUID::class.java)),
		trigger = GitHubAccessCheckTrigger.valueOf(requireNotNull(get("trigger", String::class.java))),
		status = GitHubAccessCheckStatus.valueOf(requireNotNull(get("status", String::class.java))),
		attemptCount = requireNotNull(get("attempt_count", Int::class.javaObjectType)),
		transitionVersion = requireNotNull(get("transition_version", Long::class.javaObjectType)),
		claimedBy = get("claimed_by", String::class.java),
		claimedAt = get("claimed_at", OffsetDateTime::class.java)?.toInstant(),
		nextAttemptAt = get("next_attempt_at", OffsetDateTime::class.java)?.toInstant(),
		errorCode = get("error_code", String::class.java),
		verifiedAt = get("verified_at", OffsetDateTime::class.java)?.toInstant(),
		createdAt = requireNotNull(get("created_at", OffsetDateTime::class.java)).toInstant(),
		updatedAt = requireNotNull(get("updated_at", OffsetDateTime::class.java)).toInstant(),
	)

	private fun fetchRows(sql: String, vararg bindings: Any?): List<Record> = dsl.fetch(sql, *bindings)

	private fun execute(sql: String, vararg bindings: Any?): Int = dsl.execute(sql, *bindings)

	private companion object {
		const val columns = """
			ac.id, ac.workspace_id, ac.connection_id, ac.source_scope_id, ac.trigger, ac.status,
			ac.attempt_count, ac.transition_version, ac.claimed_by, ac.claimed_at, ac.next_attempt_at,
			ac.error_code, ac.verified_at, ac.created_at, ac.updated_at
		"""
	}
}
