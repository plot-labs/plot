package com.plot.api.routine

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.github.GitHubClient
import com.plot.api.github.GitHubConnectionService
import com.plot.api.github.GitHubGuard
import com.plot.api.github.GitHubImportEligibility
import com.plot.api.github.GitHubProperties
import com.plot.api.github.GitHubWritingBlockTransformer
import com.plot.api.writingblock.WritingBlockImportService
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

data class RoutineRefreshResult(
	val completed: Boolean,
	val importedCount: Int,
)

@Service
class GitHubRoutineRefreshService(
	private val guard: GitHubGuard,
	private val properties: GitHubProperties,
	private val connectionService: GitHubConnectionService,
	private val githubClient: GitHubClient,
	private val transformer: GitHubWritingBlockTransformer,
	private val writingBlockImportService: WritingBlockImportService,
	private val agentPersistence: RoutineAgentPersistence,
	private val jdbcTemplate: JdbcTemplate,
	private val transactionTemplate: TransactionTemplate,
	private val objectMapper: ObjectMapper,
	private val uuidGenerator: UuidGenerator,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun fail(execution: RoutineExecutionRecord, workerId: String, now: Instant = clock.instant()) {
		val observationId = execution.refreshContinuationJson?.let(::parseCursor)?.observationId ?: return
		jdbcTemplate.update(
			"""
			update source_observations observation
			set status = 'FAILED', completed_at = ?
			where observation.workspace_id = ? and observation.id = ? and observation.status = 'RUNNING'
			  and exists (
			    select 1 from routine_executions execution
			    where execution.workspace_id = observation.workspace_id and execution.id = ?
			      and execution.status = 'PROBING' and execution.claimed_by = ?
			  )
			""".trimIndent(),
			Timestamp.from(now),
			execution.workspaceId,
			observationId,
			execution.id,
			workerId,
		)
	}

	fun refreshOnePage(execution: RoutineExecutionRecord, workerId: String): RoutineRefreshResult {
		guard.requireEnabled()
		val from = execution.refreshFrom
			?: throw RoutineExecutionStateException("Scheduled Routine refresh window is missing")
		val to = execution.refreshTo
			?: throw RoutineExecutionStateException("Scheduled Routine refresh window is missing")
		if (!from.isBefore(to)) throw RoutineExecutionStateException("Scheduled Routine refresh window is invalid")

		val scope = connectionService.findScope(execution.workspaceId, execution.triggerSourceScopeId)
		connectionService.requireScopeActive(execution.workspaceId, scope)
		val cursor = execution.refreshContinuationJson?.let(::parseCursor)
			?: initializeCursor(execution, workerId, scope.bindingId)
		if (cursor.pagesFetched >= properties.importPageCap.coerceAtLeast(1)) {
			throw ApiException(HttpStatus.CONTENT_TOO_LARGE, "IMPORT_TOO_LARGE", "GitHub pull-request page cap exceeded")
		}

		val page = githubClient.listClosedPullRequestsPage(
			installationId = scope.installationId,
			repositoryId = scope.externalRepositoryId,
			owner = scope.externalKey.substringBefore('/'),
			repository = scope.externalKey.substringAfter('/', scope.displayName),
			continuation = cursor.nextPage,
		)
		connectionService.requireScopeActive(execution.workspaceId, scope)
		val principal = WorkspacePrincipal(execution.workspaceId, execution.createdByUserId)
		val eligible = GitHubImportEligibility.select(page.pullRequests, from, to)
		eligible.forEach { pullRequest ->
			writingBlockImportService.upsert(
				principal,
				transformer.transform(scope.sourceNamespaceId, scope.id, cursor.observationId, pullRequest),
				clock.instant(),
			)
		}

		val pagesFetched = cursor.pagesFetched + 1
		if (page.nextPage != null) {
			if (pagesFetched >= properties.importPageCap.coerceAtLeast(1)) {
				throw ApiException(HttpStatus.CONTENT_TOO_LARGE, "IMPORT_TOO_LARGE", "GitHub pull-request page cap exceeded")
			}
			agentPersistence.saveRefreshContinuation(
				execution.workspaceId,
				execution.id,
				workerId,
				objectMapper.writeValueAsString(cursor.copy(nextPage = page.nextPage, pagesFetched = pagesFetched)),
				clock.instant(),
			)
			log.info(
				"routine_refresh execution_id={} workspace_id={} page={} imported_count={} state=CONTINUE",
				execution.id,
				execution.workspaceId,
				pagesFetched,
				eligible.size,
			)
			return RoutineRefreshResult(completed = false, importedCount = eligible.size)
		}

		val completedAt = clock.instant()
		transactionTemplate.executeWithoutResult {
			jdbcTemplate.update(
				"""
				update source_observations
				set status = 'COMPLETED', completed_at = ?
				where workspace_id = ? and id = ? and status = 'RUNNING'
				""".trimIndent(),
				Timestamp.from(completedAt),
				execution.workspaceId,
				cursor.observationId,
			)
			agentPersistence.completeRefresh(execution.workspaceId, execution.id, workerId, completedAt)
		}
		log.info(
			"routine_refresh execution_id={} workspace_id={} page={} imported_count={} state=COMPLETED",
			execution.id,
			execution.workspaceId,
			pagesFetched,
			eligible.size,
		)
		return RoutineRefreshResult(completed = true, importedCount = eligible.size)
	}

	private fun initializeCursor(
		execution: RoutineExecutionRecord,
		workerId: String,
		bindingId: UUID,
	): RoutineRefreshCursor {
		val now = clock.instant()
		val cursor = RoutineRefreshCursor(uuidGenerator.next(), nextPage = null, pagesFetched = 0)
		transactionTemplate.executeWithoutResult {
			jdbcTemplate.update(
				"""
				insert into source_observations (
				 id, workspace_id, source_scope_id, binding_id, authority_owner, coverage_key,
				 observation_mode, generation, status, started_at, created_at
				) values (?, ?, ?, ?, ?, 'pull_requests', 'PARTIAL', 0, 'RUNNING', ?, ?)
				""".trimIndent(),
				cursor.observationId,
				execution.workspaceId,
				execution.triggerSourceScopeId,
				bindingId,
				"github:routine-refresh:${execution.id}",
				Timestamp.from(now),
				Timestamp.from(now),
			)
			agentPersistence.saveRefreshContinuation(
				execution.workspaceId,
				execution.id,
				workerId,
				objectMapper.writeValueAsString(cursor),
				now,
			)
		}
		return cursor
	}

	private fun parseCursor(value: String): RoutineRefreshCursor = try {
		objectMapper.readValue(value, RoutineRefreshCursor::class.java).also {
			require(it.pagesFetched >= 0)
		}
	} catch (_: RuntimeException) {
		throw RoutineExecutionStateException("Scheduled Routine refresh continuation is invalid")
	}

	private data class RoutineRefreshCursor(
		val observationId: UUID,
		val nextPage: String?,
		val pagesFetched: Int,
	)

	private companion object {
		val log = LoggerFactory.getLogger(GitHubRoutineRefreshService::class.java)
	}
}
