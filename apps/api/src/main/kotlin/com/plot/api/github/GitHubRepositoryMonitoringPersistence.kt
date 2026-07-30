package com.plot.api.github

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate

@Repository
class GitHubRepositoryMonitoringPersistence(
	private val jdbcTemplate: JdbcTemplate,
	private val transactionTemplate: TransactionTemplate,
) {
	fun activate(
		workspaceId: UUID,
		sourceScopeId: UUID,
		now: Instant,
	): GitHubRepositoryMonitoringRecord = jdbcTemplate.query(
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
		{ rs, _ -> rs.toMonitoring() },
		UUID.randomUUID(), workspaceId, sourceScopeId, Timestamp.from(now), Timestamp.from(now),
	).single()

	fun disable(workspaceId: UUID, sourceScopeId: UUID, now: Instant) {
		jdbcTemplate.update(
			"""
			update github_repository_monitoring
			set monitoring_status = 'DISABLED', analysis_status = case
			      when analysis_status = 'ANALYZING' then 'FAILED' else analysis_status end,
			    claimed_by = null, claimed_at = null, next_attempt_at = null,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and source_scope_id = ?
			""".trimIndent(),
			Timestamp.from(now), workspaceId, sourceScopeId,
		)
	}

	fun find(workspaceId: UUID, sourceScopeId: UUID): GitHubRepositoryMonitoringRecord? =
		jdbcTemplate.query(
			"select $columns from github_repository_monitoring where workspace_id = ? and source_scope_id = ?",
			{ rs, _ -> rs.toMonitoring() },
			workspaceId, sourceScopeId,
		).firstOrNull()

	fun claimNext(workerId: String, now: Instant): GitHubRepositoryMonitoringWorkItem? =
		transactionTemplate.execute {
			val candidate = jdbcTemplate.query(
				"""
				select $qualifiedColumns, c.id, c.external_connection_key, sc.external_scope_key, sc.external_key
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
				{ rs, _ ->
					val monitoring = rs.toMonitoring()
					val externalKey = rs.getString(23).orEmpty()
					GitHubRepositoryMonitoringWorkItem(
						monitoring = monitoring,
						connectionId = rs.getObject(20, UUID::class.java),
						installationId = rs.getString(21).toLong(),
						repositoryId = rs.getString(22).toLong(),
						owner = externalKey.substringBefore('/'),
						repository = externalKey.substringAfter('/', ""),
					)
				},
				Timestamp.from(now),
			).firstOrNull() ?: return@execute null
			val updated = jdbcTemplate.update(
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
			check(updated == 1) { "Repository monitoring claim was lost" }
			candidate.copy(
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
		val updated = jdbcTemplate.update(
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
		check(updated == 1) { "Repository monitoring completion was lost" }
	}

	fun scheduleRetry(
		id: UUID,
		transitionVersion: Long,
		workerId: String,
		nextAttemptAt: Instant,
		errorCode: String,
	) {
		val updated = jdbcTemplate.update(
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
		check(updated == 1) { "Repository monitoring retry transition was lost" }
	}

	fun fail(
		id: UUID,
		transitionVersion: Long,
		workerId: String,
		errorCode: String,
		now: Instant,
	) {
		val updated = jdbcTemplate.update(
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
		check(updated == 1) { "Repository monitoring failure transition was lost" }
	}

	fun recoverStaleClaims(now: Instant, leaseTimeout: Duration, maxAttempts: Int): Int = jdbcTemplate.update(
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
	): GitHubRepositoryMonitoringRecord? = jdbcTemplate.query(
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
		{ rs, _ -> rs.toMonitoring() },
		Timestamp.from(now), workspaceId, sourceScopeId,
	).firstOrNull()

	fun requeueAuthenticationFailures(workspaceId: UUID, connectionId: UUID, now: Instant): Int =
		jdbcTemplate.update(
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

	private fun ResultSet.toMonitoring() = GitHubRepositoryMonitoringRecord(
		id = getObject(1, UUID::class.java),
		workspaceId = getObject(2, UUID::class.java),
		sourceScopeId = getObject(3, UUID::class.java),
		monitoringStatus = GitHubRepositoryMonitoringStatus.valueOf(getString(4)),
		analysisStatus = GitHubRepositoryAnalysisStatus.valueOf(getString(5)),
		releaseConvention = getString(6)?.let(GitHubReleaseConvention::valueOf),
		tagPrefix = getString(7),
		sampleSource = getString(8)?.let(GitHubReleaseSampleSource::valueOf),
		sampleSize = getInt(9),
		sampleTruncated = getBoolean(10),
		attemptCount = getInt(11),
		transitionVersion = getLong(12),
		claimedBy = getString(13),
		claimedAt = getTimestamp(14)?.toInstant(),
		nextAttemptAt = getTimestamp(15)?.toInstant(),
		lastErrorCode = getString(16),
		analyzedAt = getTimestamp(17)?.toInstant(),
		createdAt = getTimestamp(18).toInstant(),
		updatedAt = getTimestamp(19).toInstant(),
	)

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
