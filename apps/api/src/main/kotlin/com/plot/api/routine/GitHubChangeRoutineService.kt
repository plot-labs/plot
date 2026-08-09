package com.plot.api.routine

import com.plot.api.common.UuidGenerator
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.config.PlotAiProperties
import com.plot.api.generation.GenerationRunService
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

@Service
class GitHubChangeRoutineService(
	private val persistence: RoutinePersistence,
	private val writingBlockImportService: WritingBlockImportService,
	private val generationRunService: GenerationRunService,
	private val jdbcTemplate: JdbcTemplate,
	private val uuidGenerator: UuidGenerator,
	private val properties: GitHubProperties,
	private val aiProperties: PlotAiProperties,
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
		var remainingCharacters = minOf(properties.maxReleaseEvidenceCharacters, aiProperties.maxEvidenceCharacters)
		val blocks = (webhook.commits
			.distinctBy { it.sha }
			.take(properties.maxReleaseEvidenceBlocks)
			.mapNotNull { commit ->
				val block = commit.toWritingBlock(context, observationId, now)
				val title = block.title.take(minOf(properties.maxReleaseTitleCharacters, remainingCharacters))
				if (title.isBlank()) return@mapNotNull null
				remainingCharacters -= title.length
				val body = block.body?.take(minOf(properties.maxReleaseBodyCharacters, remainingCharacters))
				remainingCharacters -= body.orEmpty().length
				block.copy(title = title, body = body)
			} + listOfNotNull(webhook.toEventWritingBlock(context, observationId, now)))
			.take(properties.maxReleaseEvidenceBlocks)
		if (blocks.isEmpty()) return 0
		val writingBlockIds = blocks.map { block ->
			writingBlockImportService.upsert(
				importer,
				block,
				now,
			).blockId
		}

		routines.forEach { routine ->
			val generation = generationRunService.createForPrincipal(
				principal = WorkspacePrincipal(routine.workspaceId, routine.createdByUserId),
				sourceScopeId = routine.sourceScopeId,
				writingBlockIds = writingBlockIds,
				instruction = routine.instruction,
				idempotencyKey = "routine-github:${routine.id}:${delivery.externalDeliveryId}",
			)
			persistence.recordGitHubEventRun(routine, generation.runId, now)
		}
		return routines.size
	}

	fun hasReleaseEventRoutines(context: GitHubReleaseSourceContext): Boolean =
		persistence.hasEnabledReleaseEventRoutines(context.workspaceId, context.sourceScopeId)

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

	private fun ParsedGitHubWebhook.routineCadence(): RoutineCadence? = when {
		eventType == "push" && tagName != null -> RoutineCadence.ON_GIT_TAG
		eventType == "push" -> RoutineCadence.ON_GITHUB_CHANGE
		eventType == "release" && eventAction == "published" -> RoutineCadence.ON_GITHUB_RELEASE
		else -> null
	}

	private fun ParsedGitHubWebhook.toEventWritingBlock(
		context: GitHubReleaseSourceContext,
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
			externalObjectKey = "$kind:$tag",
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
