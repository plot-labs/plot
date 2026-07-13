package com.plot.api.github

import com.plot.api.common.ApiException
import com.plot.api.common.JdbcTime.timestamp
import com.plot.api.dev.DevContext
import com.plot.api.writingblock.WritingBlockImportService
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

data class GitHubImportRequest(
	val from: Instant,
	val to: Instant,
)

data class GitHubImportResponse(
	val id: UUID,
	val sourceScopeId: UUID,
	val from: Instant,
	val to: Instant,
	val status: String,
	val eligibleCount: Int,
	val blockCreatedCount: Int,
	val blockUpdatedCount: Int,
	val blockUnchangedCount: Int,
	val errorCode: String?,
	val errorMessage: String?,
	val startedAt: Instant,
	val completedAt: Instant?,
)

@Service
class GitHubImportService(
	private val properties: GitHubProperties,
	private val guard: GitHubGuard,
	private val devContext: DevContext,
	private val connectionService: GitHubConnectionService,
	private val githubClient: GitHubClient,
	private val jdbcTemplate: JdbcTemplate,
	private val persistence: GitHubImportPersistenceService,
	private val failureRecorder: GitHubImportFailureRecorder,
	private val transactionTemplate: TransactionTemplate,
) {
	fun start(scopeId: UUID, request: GitHubImportRequest): GitHubImportResponse {
		guard.requireEnabled()
		validateWindow(request)
		val scope = connectionService.findScope(scopeId)
		if (scope.status != "ACTIVE" || scope.connectionStatus != "ACTIVE") {
			throw ApiException(HttpStatus.CONFLICT, "REPOSITORY_INACTIVE", "GitHub repository is inactive")
		}
		val import = reserve(scope, request)
		try {
			val pullRequests = githubClient.listClosedPullRequests(
				installationId = scope.installationId,
				repositoryId = scope.externalRepositoryId,
				owner = scope.externalKey.substringBefore('/'),
				repository = scope.externalKey.substringAfter('/', scope.displayName),
				pageCap = properties.importPageCap,
			)
			val eligible = GitHubImportEligibility.select(pullRequests, request.from, request.to)
			return persistence.complete(import.id, import.observationId, scope, request, eligible)
		} catch (exception: ApiException) {
			failureRecorder.markFailed(
				import.id,
				exception.error,
				safeMessage(exception),
				connectionId = scope.connectionId.takeIf {
					exception.error == "GITHUB_ACCESS_DENIED" || exception.error == "GITHUB_NOT_FOUND"
				},
			)
			throw ApiException(exception.status, exception.error, exception.message, import.id)
		} catch (_: Exception) {
			failureRecorder.markFailed(import.id, "IMPORT_FAILED", "GitHub import failed")
			throw ApiException(HttpStatus.BAD_GATEWAY, "IMPORT_FAILED", "GitHub import failed", import.id)
		}
	}

	fun get(id: UUID): GitHubImportResponse {
		guard.requireEnabled()
		return jdbcTemplate.query(
			"""
			select id, source_scope_id, from_instant, to_instant, status,
			       eligible_count, block_created_count, block_updated_count,
			       block_unchanged_count, error_code, error_message, started_at, completed_at
			from source_imports
			where workspace_id = ? and id = ?
			""".trimIndent(),
			{ rs, _ ->
				GitHubImportResponse(
					id = rs.getObject(1, UUID::class.java),
					sourceScopeId = rs.getObject(2, UUID::class.java),
					from = rs.getTimestamp(3).toInstant(),
					to = rs.getTimestamp(4).toInstant(),
					status = rs.getString(5),
					eligibleCount = rs.getInt(6),
					blockCreatedCount = rs.getInt(7),
					blockUpdatedCount = rs.getInt(8),
					blockUnchangedCount = rs.getInt(9),
					errorCode = rs.getString(10),
					errorMessage = rs.getString(11),
					startedAt = rs.getTimestamp(12).toInstant(),
					completedAt = rs.getTimestamp(13)?.toInstant(),
				)
			},
			devContext.devWorkspaceId,
			id,
		).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "GitHub import not found")
	}

	private fun reserve(scope: GitHubScopeRecord, request: GitHubImportRequest): ReservedImport {
		val id = UUID.randomUUID()
		val observationId = UUID.randomUUID()
		val now = Instant.now()
		return try {
			transactionTemplate.execute {
				val authority = "github:scope:${scope.id}"
				val generation = jdbcTemplate.queryForObject(
					"select coalesce(max(generation), -1) + 1 from source_observations where workspace_id = ? and authority_owner = ? and coverage_key = 'pull_requests'",
					Long::class.java, devContext.devWorkspaceId, authority,
				) ?: 0L
				jdbcTemplate.update(
					"""
					insert into source_observations (
					 id, workspace_id, source_scope_id, binding_id, authority_owner, coverage_key,
					 observation_mode, generation, status, started_at, created_at
					) values (?, ?, ?, ?, ?, 'pull_requests', 'PARTIAL', ?, 'RUNNING', ?, ?)
					""".trimIndent(), observationId, devContext.devWorkspaceId, scope.id, scope.bindingId,
					authority, generation, timestamp(now), timestamp(now),
				)
				jdbcTemplate.update(
				"""
				insert into source_imports (
				  id, workspace_id, source_scope_id, observation_id, from_instant, to_instant, status,
				  eligible_count, block_created_count, block_updated_count, block_unchanged_count,
				  started_at, created_at
				) values (?, ?, ?, ?, ?, ?, 'RUNNING', 0, 0, 0, 0, ?, ?)
				""".trimIndent(),
				id,
				devContext.devWorkspaceId,
				scope.id,
				observationId,
				timestamp(request.from),
				timestamp(request.to),
				timestamp(now),
				timestamp(now),
			)
				ReservedImport(id, observationId, now)
			} ?: throw IllegalStateException("Import reservation transaction returned no result")
		} catch (_: DuplicateKeyException) {
			throw ApiException(HttpStatus.CONFLICT, "IMPORT_ALREADY_RUNNING", "A GitHub import is already running for this repository")
		}
	}

	private fun validateWindow(request: GitHubImportRequest) {
		if (!request.from.isBefore(request.to)) {
			throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMPORT_WINDOW", "Import window must have from before to")
		}
		if (Duration.between(request.from, request.to) > Duration.ofDays(366)) {
			throw ApiException(HttpStatus.BAD_REQUEST, "IMPORT_WINDOW_TOO_LARGE", "Import window cannot exceed 366 days")
		}
	}

	private fun safeMessage(exception: ApiException): String = when (exception.error) {
		"IMPORT_TOO_LARGE", "GITHUB_RATE_LIMITED", "GITHUB_PROVIDER_UNAVAILABLE", "GITHUB_ACCESS_DENIED",
		"GITHUB_NOT_FOUND", "GITHUB_INVALID_RESPONSE", "GITHUB_REDIRECT_REJECTED" -> exception.message
		else -> "GitHub import failed"
	}
}

data class ReservedImport(val id: UUID, val observationId: UUID, val startedAt: Instant)

@Service
class GitHubImportPersistenceService(
	private val devContext: DevContext,
	private val jdbcTemplate: JdbcTemplate,
	private val connectionService: GitHubConnectionService,
	private val transformer: GitHubWritingBlockTransformer,
	private val writingBlockImportService: WritingBlockImportService,
) {
	@Transactional
	fun complete(
		importId: UUID,
		observationId: UUID,
		scope: GitHubScopeRecord,
		request: GitHubImportRequest,
		pullRequests: List<GitHubPullRequest>,
	): GitHubImportResponse {
		connectionService.requireScopeActive(scope)
		var created = 0
		var updated = 0
		var unchanged = 0
		val now = Instant.now()
		pullRequests.sortedBy { it.id }.forEach { pullRequest ->
			val result = writingBlockImportService.upsert(
				transformer.transform(scope.sourceNamespaceId, scope.id, observationId, pullRequest),
				now,
			)
			when {
				result.created -> created++
				result.changed -> updated++
				else -> unchanged++
			}
		}
		val completedAt = Instant.now()
		val updatedRows = jdbcTemplate.update(
			"""
			update source_imports
			set status = 'COMPLETED', eligible_count = ?, block_created_count = ?,
			    block_updated_count = ?, block_unchanged_count = ?, completed_at = ?
			where workspace_id = ? and id = ? and status = 'RUNNING'
			""".trimIndent(),
			pullRequests.size,
			created,
			updated,
			unchanged,
			timestamp(completedAt),
			devContext.devWorkspaceId,
			importId,
		)
		if (updatedRows != 1) throw IllegalStateException("Import is no longer running")
		jdbcTemplate.update(
			"update source_observations set status = 'COMPLETED', completed_at = ? where workspace_id = ? and id = ? and status = 'RUNNING'",
			timestamp(completedAt), devContext.devWorkspaceId, observationId,
		)
		return GitHubImportResponse(
			id = importId,
			sourceScopeId = scope.id,
			from = request.from,
			to = request.to,
			status = "COMPLETED",
			eligibleCount = pullRequests.size,
			blockCreatedCount = created,
			blockUpdatedCount = updated,
			blockUnchangedCount = unchanged,
			errorCode = null,
			errorMessage = null,
			startedAt = jdbcTemplate.queryForObject(
				"select started_at from source_imports where workspace_id = ? and id = ?",
				Instant::class.java,
				devContext.devWorkspaceId,
				importId,
			) ?: now,
			completedAt = completedAt,
		)
	}
}

@Service
class GitHubImportFailureRecorder(
	private val devContext: DevContext,
	private val jdbcTemplate: JdbcTemplate,
) {
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun markFailed(importId: UUID, code: String, message: String, connectionId: UUID? = null) {
		val completedAt = Instant.now()
		jdbcTemplate.update(
			"""
			update source_imports
			set status = 'FAILED', error_code = ?, error_message = ?, completed_at = ?
			where workspace_id = ? and id = ? and status = 'RUNNING'
			""".trimIndent(),
			code.take(80),
			message.take(500),
			timestamp(completedAt),
			devContext.devWorkspaceId,
			importId,
		)
		jdbcTemplate.update(
			"""
			update source_observations so set status = 'FAILED', completed_at = ?
			from source_imports si
			where si.workspace_id = so.workspace_id and si.observation_id = so.id
			  and si.workspace_id = ? and si.id = ? and so.status = 'RUNNING'
			""".trimIndent(), timestamp(completedAt), devContext.devWorkspaceId, importId,
		)
		if (connectionId != null) {
			jdbcTemplate.update(
				"""
				update connections
				set status = 'NEEDS_REAUTH', updated_at = ?
				where workspace_id = ? and id = ? and status = 'ACTIVE'
				""".trimIndent(),
				timestamp(Instant.now()),
				devContext.devWorkspaceId,
				connectionId,
			)
		}
	}
}
