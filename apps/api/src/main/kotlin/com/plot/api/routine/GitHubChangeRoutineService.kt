package com.plot.api.routine

import com.plot.api.common.UuidGenerator
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.github.GitHubProperties
import com.plot.api.github.GitHubReleaseSourceContext
import com.plot.api.github.GitHubWebhookCommit
import com.plot.api.github.GitHubWebhookDelivery
import com.plot.api.github.ParsedGitHubWebhook
import com.plot.api.source.ImportedWritingBlock
import com.plot.api.writingblock.WritingBlockImportService
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class GitHubChangeRoutineService(
	private val persistence: RoutinePersistence,
	private val agentPersistence: RoutineAgentPersistence,
	private val writingBlockImportService: WritingBlockImportService,
	private val jdbcTemplate: JdbcTemplate,
	private val uuidGenerator: UuidGenerator,
	private val properties: GitHubProperties,
	private val evidenceBudget: RoutineEvidenceBudget,
	private val transactionTemplate: TransactionTemplate,
) {
	fun accept(
		context: GitHubReleaseSourceContext,
		delivery: GitHubWebhookDelivery,
		webhook: ParsedGitHubWebhook,
	): Int {
		val cadence = webhook.routineCadence() ?: return 0
		val routines = persistence.listEnabledGitHubEventRoutines(context.workspaceId, context.sourceScopeId, cadence)
		if (routines.isEmpty()) return 0

		val now = delivery.receivedAt
		val observationId = createObservation(context, delivery.externalDeliveryId, webhook.eventType, now)
		val importer = WorkspacePrincipal(context.workspaceId, context.createdByUserId)
		val blocks = boundedEvidenceBlocks(context, delivery.externalDeliveryId, webhook, observationId, now)
		if (blocks.isEmpty()) return 0
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
		val changedIds = upserts
			.filter { it.created || it.changed }
			.map { it.blockId }

		val enqueued = routines.count { routine ->
			try {
				transactionTemplate.execute {
					val execution = agentPersistence.createExecution(
						RoutineExecutionRequest(
							workspaceId = routine.workspaceId,
							routineId = routine.id,
							createdByUserId = routine.createdByUserId,
							triggerSourceScopeId = routine.sourceScopeId,
							triggerKind = RoutineExecutionTriggerKind.GITHUB,
							triggerKey = "github:${routine.id}:${delivery.id}",
							requestFingerprint = githubFingerprint(routine, delivery, blocks),
							triggerDeliveryId = delivery.id,
							refreshFrom = delivery.receivedAt,
							refreshTo = delivery.receivedAt,
							activityCursorBefore = routine.activityCursorSequence,
						),
					)
					agentPersistence.addEvidence(routine.workspaceId, execution.id, changedIds, now)
					true
				}
			} catch (_: RoutineExecutionIdempotencyConflictException) {
				false
			}
		}
		return enqueued
	}

	fun hasReleaseEventRoutines(context: GitHubReleaseSourceContext): Boolean =
		persistence.hasEnabledReleaseEventRoutines(context.workspaceId, context.sourceScopeId)

	private fun boundedEvidenceBlocks(
		context: GitHubReleaseSourceContext,
		externalDeliveryId: String,
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

		webhook.toEventWritingBlock(context, externalDeliveryId, observationId, now)?.let(::add)
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
		jdbcTemplate.update(
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

	private fun ParsedGitHubWebhook.toEventWritingBlock(
		context: GitHubReleaseSourceContext,
		externalDeliveryId: String,
		observationId: UUID,
		now: Instant,
	): ImportedWritingBlock? {
		val tag = tagName ?: return null
		val isRelease = eventType == "release"
		val kind = if (isRelease) "release" else "tag"
		val eventUrl = if (isRelease) {
			"${properties.webBaseUrl.trimEnd('/')}/${context.owner}/${context.repository}/releases/tag/$tag"
		} else {
			"${properties.webBaseUrl.trimEnd('/')}/${context.owner}/${context.repository}/tree/$tag"
		}
		return ImportedWritingBlock(
			sourceNamespaceId = context.sourceNamespaceId,
			sourceScopeId = context.sourceScopeId,
			observationId = observationId,
			externalObjectKey = "$kind:$tag:$externalDeliveryId",
			sourceOrigin = "integration",
			sourceKind = kind,
			title = if (isRelease) "Release $tag published" else "Tag $tag pushed",
			body = null,
			url = eventUrl,
			canonicalUrl = eventUrl,
			author = null,
			platform = "github",
			metadata = mapOf("tag" to tag, "sha" to afterSha),
			sourceCreatedAt = now,
			sourceUpdatedAt = now,
		)
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
