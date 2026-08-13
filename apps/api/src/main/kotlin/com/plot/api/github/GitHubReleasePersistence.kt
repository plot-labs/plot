package com.plot.api.github

import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate

interface GitHubReleasePersistence {
	fun insertDelivery(delivery: GitHubWebhookDelivery): GitHubWebhookDelivery
	fun findDelivery(id: UUID): GitHubWebhookDelivery?
	fun findDelivery(externalDeliveryId: String): GitHubWebhookDelivery?
	fun markDelivery(id: UUID, disposition: GitHubWebhookDisposition, errorCode: String? = null)
	fun enqueueRelease(
		workspaceId: UUID,
		sourceScopeId: UUID,
		deliveryId: UUID,
		tagName: String,
		observedHeadSha: String?,
	): GitHubReleaseDraftRequest
	fun releaseScopeExists(sourceScopeId: UUID, workspaceId: UUID): Boolean
	fun findLatestActivity(sourceScopeId: UUID, workspaceId: UUID): GitHubReleaseActivityRecord?
	fun findActivity(requestId: UUID, sourceScopeId: UUID, workspaceId: UUID): GitHubReleaseActivityRecord?
	fun findLatest(sourceScopeId: UUID, workspaceId: UUID): GitHubReleaseDraftRequest?
	fun findPreviousBoundaries(
		workspaceId: UUID,
		sourceScopeId: UUID,
		excludingRequestId: UUID,
	): List<GitHubReleaseDraftRequest>
	fun claimNext(workerId: String, now: Instant, leaseTimeout: Duration): GitHubReleaseDraftRequest?
	fun saveResolvedRange(
		requestId: UUID,
		transitionVersion: Long,
		baseSha: String,
		headSha: String,
		boundaryReason: String,
	)
	fun saveHeadAndFinishNeedsRange(
		requestId: UUID,
		transitionVersion: Long,
		headSha: String,
	)
	fun linkArtifactWorkflow(
		requestId: UUID,
		transitionVersion: Long,
		observationId: UUID,
		artifactWorkflowRunId: UUID,
	)
	fun linkAgentRun(
		requestId: UUID,
		transitionVersion: Long,
		observationId: UUID,
		agentRunId: UUID,
	): Unit = error("Agent-run release linkage is not supported by this persistence implementation")
	fun linkAgentArtifact(
		requestId: UUID,
		transitionVersion: Long,
		agentRunId: UUID,
		artifactWorkflowRunId: UUID,
	): Unit = error("Agent artifact release linkage is not supported by this persistence implementation")
	fun bindEvidence(
		requestId: UUID,
		transitionVersion: Long,
		evidence: GitHubReleaseEvidence,
	)
	fun findBoundEvidence(requestId: UUID): GitHubReleaseEvidence?
	fun renewClaim(
		requestId: UUID,
		transitionVersion: Long,
		workerId: String,
		now: Instant,
	): Boolean
	fun finish(
		requestId: UUID,
		transitionVersion: Long,
		status: GitHubReleaseDraftStatus,
		errorCode: String? = null,
	)
	fun retry(requestId: UUID, workspaceId: UUID, transitionVersion: Long): GitHubReleaseRetryResult
	fun scheduleRetry(
		requestId: UUID,
		transitionVersion: Long,
		nextAttemptAt: Instant,
		errorCode: String,
	)
	fun fenceSourceScope(
		workspaceId: UUID,
		sourceScopeId: UUID,
		now: Instant,
		errorCode: String = "SOURCE_ACCESS_LOST",
	): Int
	fun recoverStaleClaims(now: Instant, leaseTimeout: Duration): Int
	fun findGenerating(limit: Int): List<GitHubReleaseDraftRequest>
	fun recordReconcileDiagnostic(
		requestId: UUID,
		transitionVersion: Long,
		errorCode: String,
	)
}

@Repository
class JdbcGitHubReleasePersistence(
	private val jdbcTemplate: JdbcTemplate,
	private val transactionTemplate: TransactionTemplate,
	private val clock: Clock = Clock.systemUTC(),
) : GitHubReleasePersistence {
	override fun insertDelivery(delivery: GitHubWebhookDelivery): GitHubWebhookDelivery {
		return jdbcTemplate.query(
			"""
			insert into github_webhook_deliveries (
			 id, external_delivery_id, event_type, event_action, installation_id, repository_id, ref,
			 before_sha, after_sha, tag_name, ref_created, ref_deleted, forced, payload_hash,
			 disposition, error_code, received_at, processed_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			on conflict (external_delivery_id) do nothing
			returning id, external_delivery_id, event_type, event_action, installation_id, repository_id, ref,
			 before_sha, after_sha, tag_name, ref_created, ref_deleted, forced, payload_hash, disposition,
			 error_code, received_at, processed_at
			""".trimIndent(),
			{ rs, _ -> rs.toDelivery() },
			delivery.id, delivery.externalDeliveryId, delivery.eventType, delivery.eventAction,
			delivery.installationId, delivery.repositoryId, delivery.ref, delivery.beforeSha, delivery.afterSha,
			delivery.tagName, delivery.refCreated, delivery.refDeleted, delivery.forced, delivery.payloadHash,
			delivery.disposition.name, delivery.errorCode, Timestamp.from(delivery.receivedAt),
			delivery.processedAt?.let(Timestamp::from),
		).firstOrNull() ?: findDelivery(delivery.externalDeliveryId)
			?: throw IllegalStateException("GitHub webhook delivery was not found after a conflicted insert")
	}

	override fun findDelivery(externalDeliveryId: String): GitHubWebhookDelivery? = jdbcTemplate.query(
		"""
		select id, external_delivery_id, event_type, event_action, installation_id, repository_id, ref,
		 before_sha, after_sha, tag_name, ref_created, ref_deleted, forced, payload_hash, disposition,
		 error_code, received_at, processed_at
		from github_webhook_deliveries where external_delivery_id = ?
		""".trimIndent(),
		{ rs, _ -> rs.toDelivery() },
		externalDeliveryId,
	).firstOrNull()

	override fun findDelivery(id: UUID): GitHubWebhookDelivery? = jdbcTemplate.query(
		"""
		select id, external_delivery_id, event_type, event_action, installation_id, repository_id, ref,
		 before_sha, after_sha, tag_name, ref_created, ref_deleted, forced, payload_hash, disposition,
		 error_code, received_at, processed_at
		from github_webhook_deliveries where id = ?
		""".trimIndent(),
		{ rs, _ -> rs.toDelivery() },
		id,
	).firstOrNull()

	override fun markDelivery(id: UUID, disposition: GitHubWebhookDisposition, errorCode: String?) {
		val updated = jdbcTemplate.update(
			"""
			update github_webhook_deliveries
			set disposition = ?, error_code = ?, processed_at = ?
			where id = ?
			""".trimIndent(),
			disposition.name,
			errorCode,
			Timestamp.from(clock.instant()),
			id,
		)
		requireExactlyOne(updated, "Webhook delivery was not found")
	}

	override fun enqueueRelease(
		workspaceId: UUID,
		sourceScopeId: UUID,
		deliveryId: UUID,
		tagName: String,
		observedHeadSha: String?,
	): GitHubReleaseDraftRequest {
		val id = UUID.randomUUID()
		val now = clock.instant()
		val upserted = jdbcTemplate.query(
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
		if (
			observedHeadSha != null &&
			existing.observedHeadSha != null &&
			existing.observedHeadSha != observedHeadSha
		) {
			throw GitHubReleasePermanentException("GITHUB_TAG_MOVED")
		}
		return existing
	}

	override fun findLatest(sourceScopeId: UUID, workspaceId: UUID): GitHubReleaseDraftRequest? = jdbcTemplate.query(
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
		jdbcTemplate.queryForObject(
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
	): GitHubReleaseActivityRecord? = jdbcTemplate.query(
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
	): GitHubReleaseActivityRecord? = jdbcTemplate.query(
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
	): List<GitHubReleaseDraftRequest> = jdbcTemplate.query(
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

	override fun claimNext(workerId: String, now: Instant, leaseTimeout: Duration): GitHubReleaseDraftRequest? =
		transactionTemplate.execute {
			val staleBefore = now.minus(leaseTimeout)
			val candidate = jdbcTemplate.query(
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
				      and (predecessor.created_at, predecessor.id) < (candidate.created_at, candidate.id)
				      and predecessor.status not in ('READY', 'NO_ACTIVITY', 'NEEDS_RANGE', 'FAILED')
				  )
				order by candidate.created_at, candidate.id
				for update skip locked
				limit 1
				""".trimIndent(),
				{ rs, _ -> rs.toReleaseDraftRequest() },
				Timestamp.from(now),
				Timestamp.from(staleBefore),
			).firstOrNull() ?: return@execute null

			val updated = jdbcTemplate.update(
				"""
				update github_release_draft_requests
				set status = 'RESOLVING', attempt_count = attempt_count + 1,
					transition_version = transition_version + 1, claimed_by = ?, claimed_at = ?, heartbeat_at = ?,
					updated_at = ?
				where id = ? and transition_version = ?
				""".trimIndent(),
				workerId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
				candidate.id, candidate.transitionVersion,
			)
			requireExactlyOne(updated, "Release request transition was lost")
			candidate.copy(
				status = GitHubReleaseDraftStatus.RESOLVING,
				attemptCount = candidate.attemptCount + 1,
				transitionVersion = candidate.transitionVersion + 1,
			)
		}

	override fun saveResolvedRange(
		requestId: UUID,
		transitionVersion: Long,
		baseSha: String,
		headSha: String,
		boundaryReason: String,
	) {
		val now = clock.instant()
		val updated = jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set base_sha = ?, head_sha = ?, boundary_reason = ?, status = 'GENERATING',
				transition_version = transition_version + 1, heartbeat_at = ?, updated_at = ?
			where id = ? and transition_version = ?
			""".trimIndent(),
			baseSha, headSha, boundaryReason, Timestamp.from(now), Timestamp.from(now), requestId, transitionVersion,
		)
		requireExactlyOne(updated, "Release request transition was lost")
	}

	override fun saveHeadAndFinishNeedsRange(
		requestId: UUID,
		transitionVersion: Long,
		headSha: String,
	) {
		val now = clock.instant()
		val updated = jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set head_sha = ?, status = 'NEEDS_RANGE', transition_version = transition_version + 1,
				claimed_by = null, claimed_at = null, heartbeat_at = null, finished_at = ?, updated_at = ?
			where id = ? and transition_version = ?
			""".trimIndent(),
			headSha, Timestamp.from(now), Timestamp.from(now), requestId, transitionVersion,
		)
		requireExactlyOne(updated, "Release request transition was lost")
	}

	override fun linkArtifactWorkflow(
		requestId: UUID,
		transitionVersion: Long,
		observationId: UUID,
		artifactWorkflowRunId: UUID,
	) {
		transactionTemplate.executeWithoutResult {
			val now = clock.instant()
			val insertedAttempt = jdbcTemplate.update(
				"""
				insert into github_release_generation_attempts (
				 request_id, workspace_id, attempt_no, generation_run_id, created_at
				)
				select id, workspace_id, generation_attempt, ?, ?
				from github_release_draft_requests
				where id = ? and transition_version = ? and observation_id = ?
				""".trimIndent(),
				artifactWorkflowRunId,
				Timestamp.from(now),
				requestId,
				transitionVersion,
				observationId,
			)
			requireExactlyOne(insertedAttempt, "Release generation attempt transition was lost")
			val updated = jdbcTemplate.update(
				"""
				update github_release_draft_requests
				set observation_id = ?, generation_run_id = ?, status = 'GENERATING',
					transition_version = transition_version + 1,
					claimed_by = null, claimed_at = null, heartbeat_at = null, updated_at = ?
				where id = ? and transition_version = ? and observation_id = ?
				""".trimIndent(),
				observationId, artifactWorkflowRunId, Timestamp.from(now), requestId, transitionVersion, observationId,
			)
			requireExactlyOne(updated, "Release request transition was lost")
		}
	}

	override fun linkAgentRun(
		requestId: UUID,
		transitionVersion: Long,
		observationId: UUID,
		agentRunId: UUID,
	) {
		val updated = jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set agent_run_id = ?, status = 'GENERATING', transition_version = transition_version + 1,
				claimed_by = null, claimed_at = null, heartbeat_at = null, updated_at = ?
			where id = ? and transition_version = ? and observation_id = ?
			  and agent_run_id is null and generation_run_id is null
			""".trimIndent(),
			agentRunId, Timestamp.from(clock.instant()), requestId, transitionVersion, observationId,
		)
		requireExactlyOne(updated, "Release Agent run transition was lost")
	}

	override fun linkAgentArtifact(
		requestId: UUID,
		transitionVersion: Long,
		agentRunId: UUID,
		artifactWorkflowRunId: UUID,
	) {
		transactionTemplate.executeWithoutResult {
			val now = Timestamp.from(clock.instant())
			val updated = jdbcTemplate.update(
				"""
				update github_release_draft_requests
				set generation_run_id = ?, transition_version = transition_version + 1, updated_at = ?
				where id = ? and transition_version = ? and agent_run_id = ?
				  and generation_run_id is null
				""".trimIndent(),
				artifactWorkflowRunId, now, requestId, transitionVersion, agentRunId,
			)
			requireExactlyOne(updated, "Release Artifact workflow transition was lost")
			val linkedPack = jdbcTemplate.update(
				"""
				update content_packs
				set release_request_id = ?
				where workspace_id = (select workspace_id from github_release_draft_requests where id = ?)
				  and generation_run_id = ?
				  and (release_request_id is null or release_request_id = ?)
				""".trimIndent(),
				requestId, requestId, artifactWorkflowRunId, requestId,
			)
			requireExactlyOne(linkedPack, "Release Artifact materialization was not found")
		}
	}

	override fun bindEvidence(
		requestId: UUID,
		transitionVersion: Long,
		evidence: GitHubReleaseEvidence,
	) {
		require(evidence.writingBlockIds.isNotEmpty()) { "Release evidence binding cannot be empty" }
		require(evidence.writingBlockIds.distinct().size == evidence.writingBlockIds.size) {
			"Release evidence binding IDs must be unique"
		}
		transactionTemplate.executeWithoutResult {
			val now = clock.instant()
			val updated = jdbcTemplate.update(
				"""
				update github_release_draft_requests
				set observation_id = ?, transition_version = transition_version + 1,
					heartbeat_at = ?, updated_at = ?
				where id = ? and transition_version = ? and claimed_by is not null
				  and observation_id is null and generation_run_id is null
				""".trimIndent(),
				evidence.observationId,
				Timestamp.from(now),
				Timestamp.from(now),
				requestId,
				transitionVersion,
			)
			requireExactlyOne(updated, "Release request transition was lost")
			evidence.writingBlockIds.forEachIndexed { index, writingBlockId ->
				jdbcTemplate.update(
					"""
					insert into github_release_draft_evidence
					(request_id, workspace_id, observation_id, writing_block_id, order_index)
					select id, workspace_id, ?, ?, ?
					from github_release_draft_requests
					where id = ? and observation_id = ?
					""".trimIndent(),
					evidence.observationId,
					writingBlockId,
					index,
					requestId,
					evidence.observationId,
				).also { inserted ->
					requireExactlyOne(inserted, "Release evidence binding was not inserted")
				}
			}
		}
	}

	override fun findBoundEvidence(requestId: UUID): GitHubReleaseEvidence? {
		val observationId = jdbcTemplate.query(
			"select observation_id from github_release_draft_requests where id = ?",
			{ rs, _ -> rs.getObject(1, UUID::class.java) },
			requestId,
		).firstOrNull() ?: return null
		val writingBlockIds = jdbcTemplate.query(
			"""
			select writing_block_id
			from github_release_draft_evidence
			where request_id = ? and observation_id = ?
			order by order_index
			""".trimIndent(),
			{ rs, _ -> rs.getObject(1, UUID::class.java) },
			requestId,
			observationId,
		)
		check(writingBlockIds.isNotEmpty()) { "Release request has an observation without bound evidence" }
		return GitHubReleaseEvidence(observationId, writingBlockIds)
	}

	override fun renewClaim(
		requestId: UUID,
		transitionVersion: Long,
		workerId: String,
		now: Instant,
	): Boolean = jdbcTemplate.update(
		"""
		update github_release_draft_requests
		set heartbeat_at = ?, updated_at = ?
		where id = ? and transition_version = ? and claimed_by = ?
		  and status in ('RESOLVING', 'GENERATING')
		""".trimIndent(),
		Timestamp.from(now),
		Timestamp.from(now),
		requestId,
		transitionVersion,
		workerId,
	) == 1

	override fun finish(
		requestId: UUID,
		transitionVersion: Long,
		status: GitHubReleaseDraftStatus,
		errorCode: String?,
	) {
		require(status in terminalStatuses) { "Release request finish status must be terminal" }
		val now = clock.instant()
		val updated = jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set status = ?, error_code = ?, transition_version = transition_version + 1,
				claimed_by = null, claimed_at = null, heartbeat_at = null, finished_at = ?, updated_at = ?
			where id = ? and transition_version = ?
			""".trimIndent(),
			status.name, errorCode, Timestamp.from(now), Timestamp.from(now), requestId, transitionVersion,
		)
		requireExactlyOne(updated, "Release request transition was lost")
	}

	override fun retry(
		requestId: UUID,
		workspaceId: UUID,
		transitionVersion: Long,
	): GitHubReleaseRetryResult = checkNotNull(transactionTemplate.execute {
			val retry = jdbcTemplate.query(
				"""
				select status, generation_attempt
				from github_release_draft_requests
				where id = ? and workspace_id = ? and transition_version = ? and status = 'FAILED'
				for update
				""".trimIndent(),
				{ rs, _ ->
					ReleaseRetryRow(
						status = GitHubReleaseDraftStatus.valueOf(rs.getString("status")),
						generationAttempt = rs.getInt("generation_attempt"),
					)
				},
				requestId,
				workspaceId,
				transitionVersion,
			).firstOrNull() ?: throw GitHubReleaseRetryRejectedException()
			val now = clock.instant()
			val nextAttempt = retry.generationAttempt + 1
			val updated = jdbcTemplate.update(
				"""
				update github_release_draft_requests
				set status = 'QUEUED', generation_attempt = ?, generation_run_id = null, agent_run_id = null,
					attempt_count = 0, error_code = null, next_attempt_at = ?, finished_at = null,
					transition_version = transition_version + 1, claimed_by = null, claimed_at = null,
					heartbeat_at = null, updated_at = ?
				where id = ? and workspace_id = ? and transition_version = ? and status = 'FAILED'
				""".trimIndent(),
				nextAttempt,
				Timestamp.from(now),
				Timestamp.from(now),
				requestId,
				workspaceId,
				transitionVersion,
			)
			if (updated != 1) throw GitHubReleaseRetryRejectedException()
			GitHubReleaseRetryResult(
				requestId = requestId,
				artifactWorkflowRunId = null,
				generationAttempt = nextAttempt,
			)
		})

	override fun scheduleRetry(
		requestId: UUID,
		transitionVersion: Long,
		nextAttemptAt: Instant,
		errorCode: String,
	) {
		require(errorCode.isNotBlank()) { "Release retry error code is required" }
		val now = clock.instant()
		val updated = jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set status = 'QUEUED', error_code = ?, next_attempt_at = ?, finished_at = null,
				transition_version = transition_version + 1, claimed_by = null, claimed_at = null,
				heartbeat_at = null, updated_at = ?
			where id = ? and transition_version = ?
			  and status in ('RESOLVING', 'GENERATING')
			""".trimIndent(),
			errorCode,
			Timestamp.from(nextAttemptAt),
			Timestamp.from(now),
			requestId,
			transitionVersion,
		)
		requireExactlyOne(updated, "Release request transition was lost")
	}

	override fun fenceSourceScope(
		workspaceId: UUID,
		sourceScopeId: UUID,
		now: Instant,
		errorCode: String,
	): Int {
		require(errorCode.isNotBlank()) { "Release fence error code is required" }
		return jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set status = 'FAILED', error_code = ?, next_attempt_at = null,
			    transition_version = transition_version + 1,
			    claimed_by = null, claimed_at = null, heartbeat_at = null,
			    finished_at = ?, updated_at = ?
			where workspace_id = ? and source_scope_id = ?
			  and status in ('QUEUED', 'RESOLVING', 'GENERATING')
			""".trimIndent(),
			errorCode,
			Timestamp.from(now),
			Timestamp.from(now),
			workspaceId,
			sourceScopeId,
		)
	}

	override fun recoverStaleClaims(now: Instant, leaseTimeout: Duration): Int = transactionTemplate.execute {
		val staleBefore = now.minus(leaseTimeout)
		val candidates = jdbcTemplate.query(
			"""
			select id, transition_version, generation_run_id
			from github_release_draft_requests
			where claimed_by is not null and (heartbeat_at is null or heartbeat_at < ?)
			  and status in ('QUEUED', 'RESOLVING', 'GENERATING')
			order by heartbeat_at nulls first, id
			for update skip locked
			""".trimIndent(),
			{ rs, _ ->
				StaleReleaseClaim(
					requestId = rs.getObject("id", UUID::class.java),
					transitionVersion = rs.getLong("transition_version"),
					artifactWorkflowRunId = rs.getObject("generation_run_id", UUID::class.java),
				)
			},
			Timestamp.from(staleBefore),
		)
		candidates.sumOf { candidate ->
			val recoveredStatus = if (candidate.artifactWorkflowRunId == null) "QUEUED" else "GENERATING"
			val updated = jdbcTemplate.update(
				"""
				update github_release_draft_requests
				set status = ?, transition_version = transition_version + 1, claimed_by = null,
					claimed_at = null, heartbeat_at = null,
					next_attempt_at = case when generation_run_id is null then ? else next_attempt_at end,
					updated_at = ?
				where id = ? and transition_version = ?
				  and claimed_by is not null and (heartbeat_at is null or heartbeat_at < ?)
				  and status in ('QUEUED', 'RESOLVING', 'GENERATING')
				""".trimIndent(),
				recoveredStatus,
				Timestamp.from(now),
				Timestamp.from(now),
				candidate.requestId,
				candidate.transitionVersion,
				Timestamp.from(staleBefore),
			)
			when (updated) {
				0 -> 0
				1 -> 1
				else -> error("Stale release claim recovery updated $updated rows")
			}
		}
	} ?: 0

	override fun findGenerating(limit: Int): List<GitHubReleaseDraftRequest> {
		require(limit > 0) { "Generating request limit must be positive" }
		return jdbcTemplate.query(
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

	override fun recordReconcileDiagnostic(
		requestId: UUID,
		transitionVersion: Long,
		errorCode: String,
	) {
		require(errorCode.length in 1..100 && errorCode.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
			"Release diagnostic error code is invalid"
		}
		val updated = jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set error_code = ?, transition_version = transition_version + 1, updated_at = ?
			where id = ? and transition_version = ? and status = 'GENERATING'
			""".trimIndent(),
			errorCode,
			Timestamp.from(clock.instant()),
			requestId,
			transitionVersion,
		)
		requireExactlyOne(updated, "Release request transition was lost")
	}

	private fun findRequest(id: UUID): GitHubReleaseDraftRequest? = jdbcTemplate.query(
		"select ${requestColumns} from github_release_draft_requests where id = ?",
		{ rs, _ -> rs.toReleaseDraftRequest() },
		id,
	).firstOrNull()

	private fun findRequest(
		workspaceId: UUID,
		sourceScopeId: UUID,
		tagName: String,
	): GitHubReleaseDraftRequest? = jdbcTemplate.query(
		"""
		select ${requestColumns} from github_release_draft_requests
		where workspace_id = ? and source_scope_id = ? and tag_name = ?
		""".trimIndent(),
		{ rs, _ -> rs.toReleaseDraftRequest() },
		workspaceId,
		sourceScopeId,
		tagName,
	).firstOrNull()

	private fun requireExactlyOne(updated: Int, message: String) {
		check(updated == 1) { message }
	}
}

private val requestColumns = """
id, workspace_id, source_scope_id, initial_delivery_id, tag_name, observed_head_sha,
base_sha, head_sha, boundary_reason,
status, attempt_count, generation_attempt, transition_version, agent_run_id, generation_run_id, observation_id, error_code
""".trimIndent()

private val activityColumns = """
r.id, r.source_scope_id, r.tag_name, r.status, r.base_sha, r.head_sha, r.boundary_reason, r.generation_run_id,
cp.id as content_pack_id, r.error_code, r.transition_version, r.created_at, r.updated_at
""".trimIndent()

private val terminalStatuses = setOf(
	GitHubReleaseDraftStatus.READY,
	GitHubReleaseDraftStatus.NO_ACTIVITY,
	GitHubReleaseDraftStatus.NEEDS_RANGE,
	GitHubReleaseDraftStatus.FAILED,
)

private data class StaleReleaseClaim(
	val requestId: UUID,
	val transitionVersion: Long,
	val artifactWorkflowRunId: UUID?,
)

private data class ReleaseRetryRow(
	val status: GitHubReleaseDraftStatus,
	val generationAttempt: Int,
)

private fun java.sql.ResultSet.toDelivery(): GitHubWebhookDelivery = GitHubWebhookDelivery(
	id = getObject("id", UUID::class.java),
	externalDeliveryId = getString("external_delivery_id"),
	eventType = getString("event_type"),
	eventAction = getString("event_action"),
	installationId = getObject("installation_id") as Long?,
	repositoryId = getObject("repository_id") as Long?,
	ref = getString("ref"),
	beforeSha = getString("before_sha"),
	afterSha = getString("after_sha"),
	tagName = getString("tag_name"),
	refCreated = getObject("ref_created") as Boolean?,
	refDeleted = getObject("ref_deleted") as Boolean?,
	forced = getObject("forced") as Boolean?,
	payloadHash = getString("payload_hash"),
	disposition = GitHubWebhookDisposition.valueOf(getString("disposition")),
	errorCode = getString("error_code"),
	receivedAt = getTimestamp("received_at").toInstant(),
	processedAt = getTimestamp("processed_at")?.toInstant(),
)

private fun java.sql.ResultSet.toReleaseDraftRequest(): GitHubReleaseDraftRequest = GitHubReleaseDraftRequest(
	id = getObject("id", UUID::class.java),
	workspaceId = getObject("workspace_id", UUID::class.java),
	sourceScopeId = getObject("source_scope_id", UUID::class.java),
	initialDeliveryId = getObject("initial_delivery_id", UUID::class.java),
	tagName = getString("tag_name"),
	baseSha = getString("base_sha"),
	headSha = getString("head_sha"),
	boundaryReason = getString("boundary_reason"),
	status = GitHubReleaseDraftStatus.valueOf(getString("status")),
	attemptCount = getInt("attempt_count"),
	transitionVersion = getLong("transition_version"),
	agentRunId = getObject("agent_run_id", UUID::class.java),
	artifactWorkflowRunId = getObject("generation_run_id", UUID::class.java),
	observationId = getObject("observation_id", UUID::class.java),
	errorCode = getString("error_code"),
	generationAttempt = getInt("generation_attempt"),
	observedHeadSha = getString("observed_head_sha"),
)

private fun java.sql.ResultSet.toReleaseActivity(): GitHubReleaseActivityRecord = GitHubReleaseActivityRecord(
	id = getObject("id", UUID::class.java),
	sourceScopeId = getObject("source_scope_id", UUID::class.java),
	tagName = getString("tag_name"),
	status = GitHubReleaseDraftStatus.valueOf(getString("status")),
	baseSha = getString("base_sha"),
	headSha = getString("head_sha"),
	boundaryReason = getString("boundary_reason"),
	artifactWorkflowRunId = getObject("generation_run_id", UUID::class.java),
	artifactId = getObject("content_pack_id", UUID::class.java),
	errorCode = getString("error_code"),
	transitionVersion = getLong("transition_version"),
	createdAt = getTimestamp("created_at").toInstant(),
	updatedAt = getTimestamp("updated_at").toInstant(),
)
