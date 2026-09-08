package com.plot.api.routine

import com.plot.api.common.UuidGenerator
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.github.GitHubProperties
import com.plot.api.github.GitHubReleaseSourceContext
import com.plot.api.github.GitHubReleaseRequestStore
import com.plot.api.github.GitHubReleaseRoutineService
import com.plot.api.github.GitHubWebhookCommit
import com.plot.api.github.GitHubWebhookDelivery
import com.plot.api.github.ParsedGitHubWebhook
import com.plot.api.source.ImportedWritingBlock
import com.plot.api.writingblock.WritingBlockImportService
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import org.springframework.stereotype.Service

@Service
class GitHubChangeRoutineService(
	private val persistence: RoutinePersistence,
	private val agentPersistence: RoutineAgentPersistence,
	private val writingBlockImportService: WritingBlockImportService,
	private val sqlExecutor: JooqSqlExecutor,
	private val uuidGenerator: UuidGenerator,
	private val properties: GitHubProperties,
	private val evidenceBudget: RoutineEvidenceBudget,
	private val transactionExecutor: JooqTransactionExecutor,
	private val releaseRequests: GitHubReleaseRequestStore,
	private val releaseRoutines: GitHubReleaseRoutineService,
) {

	fun accept(
		context: GitHubReleaseSourceContext,
		delivery: GitHubWebhookDelivery,
		webhook: ParsedGitHubWebhook,
	): Int {
		val cadence = webhook.routineCadence() ?: return 0
		val routines = persistence.listEnabledGitHubEventRoutines(context.workspaceId, context.sourceScopeId, cadence)
		if (routines.isEmpty()) return 0
		if (cadence != RoutineCadence.ON_GITHUB_CHANGE) {
			return routines.count { routine ->
				transactionExecutor.executeRequiresNew {
					val request = releaseRequests.enqueueRoutineRelease(
						context.workspaceId, context.sourceScopeId, delivery.id,
						requireNotNull(webhook.tagName),
						webhook.afterSha.takeIf { webhook.eventType == "push" },
						routine.id,
					)
					if (request.routineId != null) releaseRoutines.ensureExecution(request)
					request.routineId != null
				}
			}
		}

		val prepared = transactionExecutor.executeRequiresNew {
			val now = delivery.receivedAt
			val observationId = createObservation(context, delivery.externalDeliveryId, webhook.eventType, now)
			val importer = WorkspacePrincipal(context.workspaceId, context.createdByUserId)
			val blocks = boundedEvidenceBlocks(context, webhook, observationId, now)
			if (blocks.isEmpty()) return@executeRequiresNew PreparedEvidence(emptyList(), emptyList())
			evidenceBudget.requireWithinBudget(
				blocks.size,
				blocks.sumOf { evidenceBudget.characters(it.title, it.body) },
			)
			val upserts = blocks.map { block ->
				writingBlockImportService.upsert(
					importer,
					block,
					now,
				)
			}
			PreparedEvidence(
				blocks = blocks,
				changedIds = upserts.filter { it.created || it.changed }.map { it.blockId },
			)
		}
		if (prepared.blocks.isEmpty()) return 0

		var firstAdmissionFailure: RuntimeException? = null
		val enqueued = routines.count { routine ->
			try {
				transactionExecutor.executeRequiresNew {
					val execution = agentPersistence.createExecution(
						RoutineExecutionRequest(
							workspaceId = routine.workspaceId,
							routineId = routine.id,
							createdByUserId = routine.createdByUserId,
							triggerSourceScopeId = routine.sourceScopeId,
							triggerKind = RoutineExecutionTriggerKind.GITHUB,
							triggerKey = "github:${routine.id}:${delivery.id}",
							requestFingerprint = githubFingerprint(routine, delivery, prepared.blocks),
							triggerDeliveryId = delivery.id,
							refreshFrom = delivery.receivedAt,
							refreshTo = delivery.receivedAt,
							activityCursorBefore = routine.activityCursorSequence,
						),
					)
					agentPersistence.addEvidence(routine.workspaceId, execution.id, prepared.changedIds, delivery.receivedAt)
					true
				}
			} catch (_: RoutineExecutionIdempotencyConflictException) {
				false
			} catch (failure: RuntimeException) {
				firstAdmissionFailure = firstAdmissionFailure ?: failure
				false
			}
		}
		firstAdmissionFailure?.let { failure ->
			throw RoutineExecutionStateException("GitHub routine admission failed").also { it.initCause(failure) }
		}
		return enqueued
	}

	private data class PreparedEvidence(
		val blocks: List<ImportedWritingBlock>,
		val changedIds: List<UUID>,
	)

	fun hasReleaseEventRoutines(context: GitHubReleaseSourceContext): Boolean =
		persistence.hasReleaseEventRoutines(context.workspaceId, context.sourceScopeId)

	private fun boundedEvidenceBlocks(
		context: GitHubReleaseSourceContext,
		webhook: ParsedGitHubWebhook,
		observationId: UUID,
		now: Instant,
	): List<ImportedWritingBlock> {
		val blockLimit = minOf(properties.maxReleaseEvidenceBlocks, evidenceBudget.maxBlocks)
		val characterLimit = minOf(properties.maxReleaseEvidenceCharacters, evidenceBudget.maxCharacters)
		var remainingCharacters = characterLimit
		val blocks = mutableListOf<ImportedWritingBlock>()
		fun add(block: ImportedWritingBlock) {
			if (blocks.size == blockLimit || remainingCharacters == 0) return
			val title = block.title.take(minOf(properties.maxReleaseTitleCharacters, characterLimit))
			if (title.isBlank()) return
			val body = block.body?.take(minOf(properties.maxReleaseBodyCharacters, characterLimit - title.length))
			val canonical = block.copy(title = title, body = body)
			val characters = evidenceBudget.characters(canonical.title, canonical.body)
			if (characters > remainingCharacters) {
				if (blocks.isNotEmpty()) return
				val boundedBody = canonical.body?.take((characterLimit - canonical.title.length).coerceAtLeast(1))
				val bounded = canonical.copy(body = boundedBody)
				blocks += bounded
				remainingCharacters = 0
				return
			}
			blocks += canonical
			remainingCharacters -= characters
		}

		webhook.commits.distinctBy { it.sha }.forEach { commit ->
			if (blocks.size == blockLimit || remainingCharacters == 0) return@forEach
			add(commit.toWritingBlock(context, observationId, now))
		}
		return blocks
	}

	private fun githubFingerprint(
		routine: RoutineRecord,
		delivery: GitHubWebhookDelivery,
		blocks: List<ImportedWritingBlock>,
	): String = buildString {
		append(routine.id)
		append('|').append(routine.sourceScopeId)
		append('|').append(routine.cadence.name)
		append('|').append(routine.instruction)
		append('|').append(PROMPT_VERSION)
		append('|').append(TOOL_POLICY_VERSION)
		agentPersistence.listContextSources(routine.workspaceId, routine.id)
			.forEach { append('|').append(it.sourceScopeId) }
		append('|').append(delivery.payloadHash)
		append('|').append(delivery.eventType)
		append('|').append(delivery.eventAction.orEmpty())
		blocks.forEach {
			append('|').append(it.externalObjectKey)
			append('@').append(it.sourceUpdatedAt)
		}
	}

	private fun createObservation(
		context: GitHubReleaseSourceContext,
		deliveryId: String,
		eventType: String,
		now: Instant,
	): UUID = uuidGenerator.next().also { observationId ->
		sqlExecutor.update(
			"""
			insert into source_observations (
			 id, workspace_id, source_scope_id, binding_id, authority_owner, coverage_key,
			 observation_mode, generation, status, started_at, completed_at, created_at
			) values (?, ?, ?, ?, ?, ?, 'PARTIAL', 0, 'COMPLETED', ?, ?, ?)
			""".trimIndent(),
			observationId,
			context.workspaceId,
			context.sourceScopeId,
			context.bindingId,
			"GITHUB_${eventType.uppercase()}",
			deliveryId,
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
		)
	}

	private companion object {
		const val PROMPT_VERSION = "routine-agent-v1"
		const val TOOL_POLICY_VERSION = "read-only-v1"
	}

	private fun ParsedGitHubWebhook.routineCadence(): RoutineCadence? = when {
		eventType == "push" && tagName != null -> RoutineCadence.ON_GIT_TAG
		eventType == "push" -> RoutineCadence.ON_GITHUB_CHANGE
		eventType == "release" && eventAction == "published" -> RoutineCadence.ON_GITHUB_RELEASE
		else -> null
	}

	private fun GitHubWebhookCommit.toWritingBlock(
		context: GitHubReleaseSourceContext,
		observationId: UUID,
		fallbackTime: Instant,
	): ImportedWritingBlock {
		val lines = message.trim().lineSequence().toList()
		val title = lines.first()
		val body = lines.drop(1).joinToString("\n").trim().takeIf { it.isNotBlank() }
		val commitUrl = url ?: "${properties.webBaseUrl.trimEnd('/')}/${context.owner}/${context.repository}/commit/$sha"
		val committedAt = timestamp ?: fallbackTime
		return ImportedWritingBlock(
			sourceNamespaceId = context.sourceNamespaceId,
			sourceScopeId = context.sourceScopeId,
			observationId = observationId,
			externalObjectKey = "commit:$sha",
			sourceOrigin = "integration",
			sourceKind = "commit",
			title = title,
			body = body,
			url = commitUrl,
			canonicalUrl = commitUrl,
			author = author,
			platform = "github",
			metadata = mapOf("sha" to sha),
			sourceCreatedAt = committedAt,
			sourceUpdatedAt = committedAt,
		)
	}
}
