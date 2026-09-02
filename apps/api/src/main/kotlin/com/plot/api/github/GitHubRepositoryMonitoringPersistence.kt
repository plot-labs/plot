package com.plot.api.github

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class GitHubRepositoryMonitoringPersistence(
	private val dsl: DSLContext,
) {
	fun activate(
		workspaceId: UUID,
		sourceScopeId: UUID,
		now: Instant,
	): GitHubRepositoryMonitoringRecord = fetchRows(
		"""
		insert into github_repository_monitoring (
		  id, workspace_id, source_scope_id, monitoring_status, analysis_status,
		  sample_size, sample_truncated, attempt_count, transition_version, created_at, updated_at
		) values (?, ?, ?, 'ACTIVE', 'QUEUED', 0, false, 0, 0, ?, ?)
		on conflict (workspace_id, source_scope_id) do update
		set monitoring_status = 'ACTIVE',
		    analysis_status = case
		      when github_repository_monitoring.monitoring_status = 'DISABLED' then 'QUEUED'
		      else github_repository_monitoring.analysis_status
		    end,
		    release_convention = case
		      when github_repository_monitoring.monitoring_status = 'DISABLED' then null
		      else github_repository_monitoring.release_convention
		    end,
		    tag_prefix = case
		      when github_repository_monitoring.monitoring_status = 'DISABLED' then null
		      else github_repository_monitoring.tag_prefix
		    end,
		    sample_source = case
		      when github_repository_monitoring.monitoring_status = 'DISABLED' then null
		      else github_repository_monitoring.sample_source
		    end,
		    sample_size = case when github_repository_monitoring.monitoring_status = 'DISABLED' then 0
		      else github_repository_monitoring.sample_size end,
		    sample_truncated = case when github_repository_monitoring.monitoring_status = 'DISABLED' then false
		      else github_repository_monitoring.sample_truncated end,
		    attempt_count = case when github_repository_monitoring.monitoring_status = 'DISABLED' then 0
		      else github_repository_monitoring.attempt_count end,
		    transition_version = case when github_repository_monitoring.monitoring_status = 'DISABLED'
		      then github_repository_monitoring.transition_version + 1
		      else github_repository_monitoring.transition_version end,
		    claimed_by = case when github_repository_monitoring.monitoring_status = 'DISABLED' then null
		      else github_repository_monitoring.claimed_by end,
		    claimed_at = case when github_repository_monitoring.monitoring_status = 'DISABLED' then null
		      else github_repository_monitoring.claimed_at end,
		    next_attempt_at = case when github_repository_monitoring.monitoring_status = 'DISABLED' then null
		      else github_repository_monitoring.next_attempt_at end,
		    last_error_code = case when github_repository_monitoring.monitoring_status = 'DISABLED' then null
		      else github_repository_monitoring.last_error_code end,
		    analyzed_at = case when github_repository_monitoring.monitoring_status = 'DISABLED' then null
		      else github_repository_monitoring.analyzed_at end,
		    updated_at = excluded.updated_at
		returning $columns
		""".trimIndent(),
		UUID.randomUUID(), workspaceId, sourceScopeId, Timestamp.from(now), Timestamp.from(now),
	).single().toMonitoring()

	fun disable(workspaceId: UUID, sourceScopeId: UUID, now: Instant) {
		execute(
			"""
			update github_repository_monitoring
			set monitoring_status = 'DISABLED', analysis_status = case
			      when analysis_status = 'ANALYZING' then 'FAILED' else analysis_status end,
			    last_error_code = case
			      when analysis_status = 'ANALYZING' then 'SOURCE_ACCESS_LOST' else last_error_code end,
			    claimed_by = null, claimed_at = null, next_attempt_at = null,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and source_scope_id = ?
			""".trimIndent(),
			Timestamp.from(now), workspaceId, sourceScopeId,
		)
	}

	fun find(workspaceId: UUID, sourceScopeId: UUID): GitHubRepositoryMonitoringRecord? =
		fetchRows(
			"select $columns from github_repository_monitoring where workspace_id = ? and source_scope_id = ?",
			workspaceId, sourceScopeId,
		).firstOrNull()?.toMonitoring()

	@Transactional
	fun claimNext(workerId: String, now: Instant): GitHubRepositoryMonitoringWorkItem? {
		val candidate = fetchRows(
			"""
			select $qualifiedColumns, c.id as connection_id,
			       c.external_connection_key as installation_key,
			       sc.external_scope_key as repository_key, sc.external_key as external_key
			from github_repository_monitoring m
			join source_scopes sc on sc.workspace_id = m.workspace_id and sc.id = m.source_scope_id
			  and sc.provider = 'GITHUB' and sc.scope_kind = 'REPOSITORY' and sc.status = 'ACTIVE'
			join connection_namespace_bindings b on b.workspace_id = sc.workspace_id
			  and b.source_namespace_id = sc.source_namespace_id and b.status = 'ACTIVE'
			join connections c on c.workspace_id = b.workspace_id and c.id = b.connection_id
			  and c.provider = 'GITHUB' and c.status = 'ACTIVE'
			where m.monitoring_status = 'ACTIVE' and m.analysis_status = 'QUEUED'
			  and (m.next_attempt_at is null or m.next_attempt_at <= ?)
			order by m.created_at, m.id
			for update of m skip locked
			limit 1
			""".trimIndent(),
			Timestamp.from(now),
		).firstOrNull()?.let { row ->
			val monitoring = row.toMonitoring()
			val externalKey = row.get("external_key", String::class.java).orEmpty()
			GitHubRepositoryMonitoringWorkItem(
				monitoring = monitoring,
				connectionId = requireNotNull(row.get("connection_id", UUID::class.java)),
				installationId = row.get("installation_key", String::class.java).toLong(),
				repositoryId = row.get("repository_key", String::class.java).toLong(),
				owner = externalKey.substringBefore('/'),
				repository = externalKey.substringAfter('/', ""),
			)
		} ?: return null
		val updated = execute(
			"""
			update github_repository_monitoring
			set analysis_status = 'ANALYZING', attempt_count = attempt_count + 1,
			    transition_version = transition_version + 1, claimed_by = ?, claimed_at = ?,
			    next_attempt_at = null, updated_at = ?
			where id = ? and transition_version = ? and monitoring_status = 'ACTIVE'
			  and analysis_status = 'QUEUED'
			""".trimIndent(),
			workerId, Timestamp.from(now), Timestamp.from(now),
			candidate.monitoring.id, candidate.monitoring.transitionVersion,
		)
		requireExactlyOne(updated, "Repository monitoring claim was lost")
		return candidate.copy(
			monitoring = candidate.monitoring.copy(
				analysisStatus = GitHubRepositoryAnalysisStatus.ANALYZING,
				attemptCount = candidate.monitoring.attemptCount + 1,
				transitionVersion = candidate.monitoring.transitionVersion + 1,
				claimedBy = workerId,
				claimedAt = now,
				nextAttemptAt = null,
				updatedAt = now,
			),
		)
	}

	fun complete(
		id: UUID,
		transitionVersion: Long,
		workerId: String,
		analysis: GitHubReleaseConventionAnalysis,
		now: Instant,
	) {
		val updated = execute(
			"""
			update github_repository_monitoring
			set analysis_status = 'COMPLETED', release_convention = ?, tag_prefix = ?,
			    sample_source = ?, sample_size = ?, sample_truncated = ?,
			    claimed_by = null, claimed_at = null, next_attempt_at = null,
			    last_error_code = null, analyzed_at = ?, transition_version = transition_version + 1,
			    updated_at = ?
			where id = ? and transition_version = ? and claimed_by = ?
			  and monitoring_status = 'ACTIVE' and analysis_status = 'ANALYZING'
			""".trimIndent(),
			analysis.convention.name, analysis.tagPrefix, analysis.sampleSource?.name,
			analysis.sampleSize, analysis.sampleTruncated, Timestamp.from(now), Timestamp.from(now),
			id, transitionVersion, workerId,
		)
		requireExactlyOne(updated, "Repository monitoring completion was lost")
	}

	fun scheduleRetry(
		id: UUID,
		transitionVersion: Long,
		workerId: String,
		nextAttemptAt: Instant,
		errorCode: String,
	) {
		val updated = execute(
			"""
			update github_repository_monitoring
			set analysis_status = 'QUEUED', claimed_by = null, claimed_at = null,
			    next_attempt_at = ?, last_error_code = ?, transition_version = transition_version + 1,
			    updated_at = now()
			where id = ? and transition_version = ? and claimed_by = ?
			  and monitoring_status = 'ACTIVE' and analysis_status = 'ANALYZING'
			""".trimIndent(),
			Timestamp.from(nextAttemptAt), errorCode, id, transitionVersion, workerId,
		)
		requireExactlyOne(updated, "Repository monitoring retry transition was lost")
	}

	fun fail(
		id: UUID,
		transitionVersion: Long,
		workerId: String,
		errorCode: String,
		now: Instant,
	) {
		val updated = execute(
			"""
			update github_repository_monitoring
			set analysis_status = 'FAILED', claimed_by = null, claimed_at = null,
			    next_attempt_at = null, last_error_code = ?, transition_version = transition_version + 1,
			    updated_at = ?
			where id = ? and transition_version = ? and claimed_by = ?
			  and monitoring_status = 'ACTIVE' and analysis_status = 'ANALYZING'
			""".trimIndent(),
			errorCode, Timestamp.from(now), id, transitionVersion, workerId,
		)
		requireExactlyOne(updated, "Repository monitoring failure transition was lost")
	}

	/** Earliest persisted retry, including rows already due, or null when none is pending. */
	fun earliestNextAttemptAt(after: Instant): Instant? = fetchRows(
		"""
		select min(next_attempt_at) as next_attempt_at
		from github_repository_monitoring
		where monitoring_status = 'ACTIVE' and analysis_status = 'QUEUED' and next_attempt_at is not null
		""".trimIndent(),
	).firstOrNull()?.get("next_attempt_at", OffsetDateTime::class.java)?.toInstant()

	fun recoverStaleClaims(now: Instant, leaseTimeout: Duration, maxAttempts: Int): Int = execute(
		"""
		update github_repository_monitoring
		set analysis_status = case when attempt_count >= ? then 'FAILED' else 'QUEUED' end,
		    claimed_by = null, claimed_at = null, next_attempt_at = null,
		    last_error_code = case when attempt_count >= ? then 'MONITORING_STALE_CLAIM_LIMIT'
		      else last_error_code end,
		    transition_version = transition_version + 1, updated_at = ?
		where monitoring_status = 'ACTIVE' and analysis_status = 'ANALYZING' and claimed_at < ?
		""".trimIndent(),
		maxAttempts, maxAttempts, Timestamp.from(now), Timestamp.from(now.minus(leaseTimeout)),
	)

	fun retry(
		workspaceId: UUID,
		sourceScopeId: UUID,
		now: Instant,
	): GitHubRepositoryMonitoringRecord? = fetchRows(
		"""
		update github_repository_monitoring m
		set analysis_status = 'QUEUED', attempt_count = 0, next_attempt_at = null,
		    last_error_code = null, transition_version = transition_version + 1, updated_at = ?
		where m.workspace_id = ? and m.source_scope_id = ? and m.monitoring_status = 'ACTIVE'
		  and m.analysis_status = 'FAILED'
		  and exists (
		    select 1 from source_scopes sc
		    join connection_namespace_bindings b on b.workspace_id = sc.workspace_id
		      and b.source_namespace_id = sc.source_namespace_id and b.status = 'ACTIVE'
		    join connections c on c.workspace_id = b.workspace_id and c.id = b.connection_id and c.status = 'ACTIVE'
		    where sc.workspace_id = m.workspace_id and sc.id = m.source_scope_id and sc.status = 'ACTIVE'
		  )
		returning $columns
		""".trimIndent(),
		Timestamp.from(now), workspaceId, sourceScopeId,
	).firstOrNull()?.toMonitoring()

	fun requeueAuthenticationFailures(workspaceId: UUID, connectionId: UUID, now: Instant): Int =
		execute(
			"""
			update github_repository_monitoring m
			set analysis_status = 'QUEUED', attempt_count = 0, next_attempt_at = null,
			    last_error_code = null, transition_version = transition_version + 1, updated_at = ?
			from source_scopes sc
			join connection_namespace_bindings b on b.workspace_id = sc.workspace_id
			  and b.source_namespace_id = sc.source_namespace_id and b.status = 'ACTIVE'
			where m.workspace_id = ? and m.source_scope_id = sc.id and b.connection_id = ?
			  and m.monitoring_status = 'ACTIVE' and m.analysis_status = 'FAILED'
			  and m.last_error_code in ('GITHUB_ACCESS_DENIED', 'GITHUB_NOT_FOUND')
			""".trimIndent(),
			Timestamp.from(now), workspaceId, connectionId,
		)

	private fun Record.toMonitoring() = GitHubRepositoryMonitoringRecord(
		id = requireNotNull(get("id", UUID::class.java)),
		workspaceId = requireNotNull(get("workspace_id", UUID::class.java)),
		sourceScopeId = requireNotNull(get("source_scope_id", UUID::class.java)),
		monitoringStatus = GitHubRepositoryMonitoringStatus.valueOf(requireNotNull(get("monitoring_status", String::class.java))),
		analysisStatus = GitHubRepositoryAnalysisStatus.valueOf(requireNotNull(get("analysis_status", String::class.java))),
		releaseConvention = get("release_convention", String::class.java)?.let(GitHubReleaseConvention::valueOf),
		tagPrefix = get("tag_prefix", String::class.java),
		sampleSource = get("sample_source", String::class.java)?.let(GitHubReleaseSampleSource::valueOf),
		sampleSize = requireNotNull(get("sample_size", Int::class.javaObjectType)),
		sampleTruncated = requireNotNull(get("sample_truncated", Boolean::class.javaObjectType)),
		attemptCount = requireNotNull(get("attempt_count", Int::class.javaObjectType)),
		transitionVersion = requireNotNull(get("transition_version", Long::class.javaObjectType)),
		claimedBy = get("claimed_by", String::class.java),
		claimedAt = get("claimed_at", OffsetDateTime::class.java)?.toInstant(),
		nextAttemptAt = get("next_attempt_at", OffsetDateTime::class.java)?.toInstant(),
		lastErrorCode = get("last_error_code", String::class.java),
		analyzedAt = get("analyzed_at", OffsetDateTime::class.java)?.toInstant(),
		createdAt = requireNotNull(get("created_at", OffsetDateTime::class.java)).toInstant(),
		updatedAt = requireNotNull(get("updated_at", OffsetDateTime::class.java)).toInstant(),
	)

	private fun fetchRows(sql: String, vararg bindings: Any?): List<Record> = dsl.fetch(sql, *bindings)

	private fun execute(sql: String, vararg bindings: Any?): Int = dsl.execute(sql, *bindings)

	private fun requireExactlyOne(updated: Int, message: String) {
		if (updated != 1) throw InvalidDataAccessApiUsageException(message)
	}

	private companion object {
		const val columns =
			"""id, workspace_id, source_scope_id, monitoring_status, analysis_status,
			release_convention, tag_prefix, sample_source, sample_size, sample_truncated,
			attempt_count, transition_version, claimed_by, claimed_at, next_attempt_at,
			last_error_code, analyzed_at, created_at, updated_at"""
		const val qualifiedColumns =
			"""m.id, m.workspace_id, m.source_scope_id, m.monitoring_status, m.analysis_status,
			m.release_convention, m.tag_prefix, m.sample_source, m.sample_size, m.sample_truncated,
			m.attempt_count, m.transition_version, m.claimed_by, m.claimed_at, m.next_attempt_at,
			m.last_error_code, m.analyzed_at, m.created_at, m.updated_at"""
	}
}
