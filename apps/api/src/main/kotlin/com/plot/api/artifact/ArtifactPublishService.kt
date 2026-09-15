package com.plot.api.artifact

import com.plot.api.artifact.dto.PublishContentVariantResponse
import com.plot.api.artifact.dto.UnpublishContentVariantResponse
import com.plot.api.auth.RequestActorResolver
import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.dev.DevContext
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.TransactionExecutor
import com.plot.api.workspace.WorkspaceRepository
import java.sql.Timestamp
import java.time.Clock
import java.util.Locale
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class ArtifactPublishService(
	private val sqlExecutor: SqlExecutor,
	private val transactionExecutor: TransactionExecutor,
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val deliveryGate: ArtifactDeliveryGate,
	private val workspaceRepository: WorkspaceRepository,
	private val deliveryEventService: ProductDeliveryEventService,
	private val actorResolver: RequestActorResolver? = null,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun publish(
		variantId: UUID,
		expectedRevisionNumber: Int,
		acknowledge: Boolean,
		acknowledgedWarningKeys: List<String>,
		legacyAcknowledgedRevisionIds: List<UUID>,
	): PublishContentVariantResponse = transactionExecutor.execute {
		requireChangelogContentType(variantId)
		when (val gate = deliveryGate.prepare(
			variantId,
			expectedRevisionNumber,
			includeSources = false,
			acknowledge,
			acknowledgedWarningKeys,
			legacyAcknowledgedRevisionIds,
		)) {
			is DeliveryGateOutcome.ConfirmationRequired -> throw PublishConfirmationRequiredException(gate.warnings)
			is DeliveryGateOutcome.Ready -> insertPublishedEntry(variantId, gate)
		}
	}

	fun unpublish(variantId: UUID): UnpublishContentVariantResponse = transactionExecutor.execute {
		requireOwner()
		val unpublishedAt = clock.instant()
		val updated = sqlExecutor.update(
			"""
			update published_changelog_entries
			set unpublished_at = ?, unpublished_by_user_id = ?
			where workspace_id = ? and content_variant_id = ? and unpublished_at is null
			""".trimIndent(),
			Timestamp.from(unpublishedAt),
			devContext.devUserId,
			devContext.devWorkspaceId,
			variantId,
		)
		if (updated == 0) {
			val existing = sqlExecutor.queryForObject(
				"""
				select count(*) from published_changelog_entries
				where workspace_id = ? and content_variant_id = ?
				""".trimIndent(),
				Int::class.java,
				devContext.devWorkspaceId,
				variantId,
			) ?: 0
			if (existing > 0) {
				throw ApiException(
					HttpStatus.CONFLICT,
					"ENTRY_NOT_LIVE",
					"This changelog entry is not publicly live",
				)
			}
			throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Published changelog entry not found")
		}
		loadUnpublishedEntry(variantId, unpublishedAt)
	}

	private fun insertPublishedEntry(variantId: UUID, gate: DeliveryGateOutcome.Ready): PublishContentVariantResponse {
		val metadata = loadPublishMetadata(variantId)
		val entryId = uuidGenerator.next()
		val entrySlug = metadata.tagName?.let(::entrySlugFromTag) ?: shortEntrySlug(entryId)
		val title = metadata.title?.trim()?.takeIf { it.isNotEmpty() }
			?: metadata.tagName
			?: "Changelog"
		val workspace = workspaceRepository.findById(devContext.devWorkspaceId).orElseThrow {
			ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		}
		val publishedAt = clock.instant()
		try {
			sqlExecutor.update(
				"""
				insert into published_changelog_entries (
				 id, workspace_id, content_variant_id, artifact_revision_id, artifact_revision_number,
				 entry_slug, title, body_markdown, tag_name, published_by_user_id, published_at
				) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent(),
				entryId,
				devContext.devWorkspaceId,
				variantId,
				gate.revision.id,
				gate.revision.revisionNumber,
				entrySlug,
				title,
				gate.rendered.markdown,
				metadata.tagName,
				devContext.devUserId,
				Timestamp.from(publishedAt),
			)
		} catch (_: DataIntegrityViolationException) {
			if (metadata.tagName != null) {
				throw ApiException(
					HttpStatus.CONFLICT,
					"PUBLISH_TAG_CONFLICT",
					"A changelog entry already exists for release tag ${metadata.tagName}",
				)
			}
			throw ApiException(
				HttpStatus.CONFLICT,
				"PUBLISH_VARIANT_CONFLICT",
				"This artifact variant is already published",
			)
		}
		insertCitationSnapshot(entryId, gate)
		deliveryEventService.recordPublished(entryId, variantId, gate.revision.id)
		return PublishContentVariantResponse(
			entryId = entryId,
			entrySlug = entrySlug,
			publicPath = "/${workspace.slug}/changelog/$entrySlug",
			publishedAt = publishedAt,
		)
	}

	private fun insertCitationSnapshot(entryId: UUID, gate: DeliveryGateOutcome.Ready) {
		gate.exportSentences
			.sortedBy { it.orderIndex }
			.forEach { sentence ->
				val sentenceId = uuidGenerator.next()
				val body = gate.rendered.renderedSentences[sentence.id]
					?: throw IllegalStateException("Rendered sentence is missing for ${sentence.id}")
				sqlExecutor.update(
					"""
					insert into published_changelog_entry_sentences (
					  id, workspace_id, published_changelog_entry_id, order_index, body
					) values (?, ?, ?, ?, ?)
					""".trimIndent(),
					sentenceId,
					devContext.devWorkspaceId,
					entryId,
					sentence.orderIndex,
					body,
				)

				gate.publicCitations[sentence.id].orEmpty()
					.filter {
						it.sourceVisibility.equals("PUBLIC", ignoreCase = true) &&
							it.provider.equals("GITHUB", ignoreCase = true) &&
							!it.originalUrl.isNullOrBlank()
					}
					.forEachIndexed { citationOrder, citation ->
						sqlExecutor.update(
							"""
							insert into published_changelog_entry_citations (
							  id, workspace_id, published_changelog_entry_sentence_id,
							  citation_order, provider, source_label, original_url
							) values (?, ?, ?, ?, ?, ?, ?)
							""".trimIndent(),
							uuidGenerator.next(),
							devContext.devWorkspaceId,
							sentenceId,
							citationOrder,
							citation.provider,
							citation.sourceLabel,
							citation.originalUrl,
						)
					}
			}
	}

	private fun loadUnpublishedEntry(variantId: UUID, unpublishedAt: java.time.Instant): UnpublishContentVariantResponse {
		val workspace = workspaceRepository.findById(devContext.devWorkspaceId).orElseThrow {
			ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		}
		val row = sqlExecutor.query(
			"""
			select id, entry_slug, published_at
			from published_changelog_entries
			where workspace_id = ? and content_variant_id = ? and unpublished_at is not null
			order by unpublished_at desc, id desc
			limit 1
			""".trimIndent(),
			{ rs, _ ->
				val entrySlug = requireNotNull(rs.getString(2))
				UnpublishContentVariantResponse(
					entryId = requireNotNull(rs.getObject(1, UUID::class.java)),
					entrySlug = entrySlug,
					publicPath = "/${workspace.slug}/changelog/$entrySlug",
					publishedAt = requireNotNull(rs.getTimestamp(3)).toInstant(),
					unpublishedAt = unpublishedAt,
				)
			},
			devContext.devWorkspaceId,
			variantId,
		).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Published changelog entry not found")
		return row
	}

	private fun requireOwner() {
		val actor = actorResolver?.current()
		if (actor != null && actorResolver.requireWorkspace().role != "OWNER") {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Workspace owner access is required")
		}
	}

	private fun requireChangelogContentType(variantId: UUID) {
		val contentType = sqlExecutor.query(
			"""
			select coalesce(ar.content_type, 'CHANGELOG')
			from content_variants cv
			join content_packs cp on cp.workspace_id = cv.workspace_id and cp.id = cv.content_pack_id
			left join generation_runs gr
			  on gr.workspace_id = cp.workspace_id and gr.id = cp.generation_run_id
			left join agent_runs ar
			  on ar.workspace_id = gr.workspace_id and ar.id = gr.agent_run_id
			where cv.workspace_id = ? and cv.id = ?
			""".trimIndent(),
			{ rs, _ -> requireNotNull(rs.getString(1)) },
			devContext.devWorkspaceId,
			variantId,
		).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Content pack not found")
		if (contentType != "CHANGELOG") {
			throw ApiException(
				HttpStatus.CONFLICT,
				"PUBLISH_CONTENT_TYPE_NOT_SUPPORTED",
				"Hosted publish is only available for changelog artifacts",
			)
		}
	}

	private fun loadPublishMetadata(variantId: UUID): PublishMetadata {
		val row = sqlExecutor.query(
			"""
			select cp.title, grdr.tag_name
			from content_variants cv
			join content_packs cp on cp.workspace_id = cv.workspace_id and cp.id = cv.content_pack_id
			left join github_release_draft_requests grdr
			  on grdr.workspace_id = cp.workspace_id and grdr.id = cp.release_request_id
			where cv.workspace_id = ? and cv.id = ?
			""".trimIndent(),
			{ rs, _ ->
				PublishMetadata(
					title = rs.getString(1),
					tagName = rs.getString(2),
				)
			},
			devContext.devWorkspaceId,
			variantId,
		).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Content pack not found")
		return row
	}

	private fun entrySlugFromTag(tagName: String): String {
		val normalized = tagName.trim().lowercase(Locale.ROOT)
		require(normalized.isNotEmpty()) { "Release tag is blank" }
		return normalized.replace(SLUG_UNSAFE, "-").trim('-').ifEmpty { shortEntrySlug(uuidGenerator.next()) }
	}

	private fun shortEntrySlug(entryId: UUID): String = entryId.toString().replace("-", "").takeLast(8)

	private data class PublishMetadata(val title: String?, val tagName: String?)

	private companion object {
		val SLUG_UNSAFE = Regex("[^a-z0-9.-]+")
	}
}
