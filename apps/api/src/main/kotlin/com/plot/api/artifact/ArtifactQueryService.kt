package com.plot.api.artifact
import com.plot.api.common.ApiException
import com.plot.api.artifact.dto.ContentCitationResponse
import com.plot.api.artifact.dto.ArtifactPageResponse
import com.plot.api.artifact.dto.ArtifactPublicationResponse
import com.plot.api.artifact.dto.ArtifactResponse
import com.plot.api.artifact.dto.ArtifactSummaryResponse
import com.plot.api.artifact.dto.ContentSentenceResponse
import com.plot.api.artifact.dto.ContentSourceResponse
import com.plot.api.artifact.dto.ContentVariantResponse
import com.plot.api.artifact.dto.ContentVariantHistoryItemResponse
import com.plot.api.artifact.dto.ContentVariantHistoryDetailResponse
import com.plot.api.dev.DevContext
import com.plot.api.artifact.workflow.model.CitationStatus
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ExportSentence
import com.plot.api.artifact.workflow.model.ExportSentenceStatus
import com.plot.api.artifact.workflow.model.SentenceCitation
import com.plot.api.artifact.workflow.model.SourceProvider
import java.net.URI
import java.util.UUID
import org.springframework.http.HttpStatus
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.content.ContentSourceSnapshotService
import com.plot.api.content.ContentBrief
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
@Service
class ArtifactQueryService(
    private val sqlExecutor: JooqSqlExecutor,
    private val devContext: DevContext,
    private val materializer: ArtifactRevisionMaterializer,
    private val objectMapper: ObjectMapper,
    private val contentSourceSnapshotService: ContentSourceSnapshotService,
) {
	fun list(page: Int, size: Int): ArtifactPageResponse {
		require(page >= 0) { "Page must not be negative" }
		require(size in 1..100) { "Size must be between 1 and 100" }
		val total = sqlExecutor.queryForObject(
			"select count(*) from content_packs where workspace_id = ?", Long::class.java, devContext.devWorkspaceId,
		) ?: 0L
		val items = sqlExecutor.query(
			"""
			select cp.id, cp.status, cp.title, coalesce(ar.content_type, 'CHANGELOG'), cp.updated_at,
			  exists (
			    select 1 from content_variants cv
			    join published_changelog_entries pce
			      on pce.workspace_id = cv.workspace_id and pce.content_variant_id = cv.id
			    where cv.workspace_id = cp.workspace_id and cv.content_pack_id = cp.id
			      and cv.variant_index = 0 and pce.unpublished_at is null
			  ) as published
			from content_packs cp
			left join generation_runs gr
			  on gr.workspace_id = cp.workspace_id and gr.id = cp.generation_run_id
			left join agent_runs ar
			  on ar.workspace_id = gr.workspace_id and ar.id = gr.agent_run_id
			where cp.workspace_id = ?
			order by cp.updated_at desc, cp.id desc
			limit ? offset ?
			""".trimIndent(),
			{ rs, _ ->
				ArtifactSummaryResponse(
					id = requireNotNull(rs.getObject(1, UUID::class.java)),
					status = requireNotNull(rs.getString(2)),
					title = rs.getString(3),
					contentType = requireNotNull(rs.getString(4)),
					updatedAt = requireNotNull(rs.getTimestamp(5)).toInstant(),
					published = rs.getBoolean(6),
				)
			},
			devContext.devWorkspaceId, size, page * size,
		)
		return ArtifactPageResponse(items, page, size, total, if (total == 0L) 0 else ((total + size - 1) / size).toInt())
	}
	fun get(packId: UUID): ArtifactResponse = loadPack("cp.id = ?", packId)
	fun getVariant(variantId: UUID): ArtifactResponse = loadPack("cv.id = ?", variantId)
	fun history(variantId: UUID): List<ContentVariantHistoryItemResponse> {
		materializer.ensureArtifactRevision(variantId)
		return sqlExecutor.query(
			"""
			select id, revision_no, created_by_user_id, created_at
			from content_variant_revisions
			where workspace_id = ? and content_variant_id = ?
			order by created_at desc, revision_no desc, id desc
			""".trimIndent(),
			{ rs, index ->
				ContentVariantHistoryItemResponse(
					position = index,
					createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
					cause = historyCause(rs.getInt("revision_no"), rs.getObject("created_by_user_id", UUID::class.java)),
				)
			},
			devContext.devWorkspaceId,
			variantId,
		)
	}
	fun historyDetail(variantId: UUID, revisionId: UUID): ContentVariantHistoryDetailResponse {
		val row = sqlExecutor.query(
			"select revision_no, created_by_user_id, created_at from content_variant_revisions where workspace_id = ? and id = ? and content_variant_id = ?",
				{ rs, _ -> HistoryRevisionRow(rs.getInt(1), rs.getObject(2, UUID::class.java), requireNotNull(rs.getTimestamp(3)).toInstant()) },
			devContext.devWorkspaceId,
			revisionId,
			variantId,
		).firstOrNull() ?: notFound()
		val cause = historyCause(row.revisionNumber, row.createdByUserId)
		return ContentVariantHistoryDetailResponse(row.createdAt, cause, true, loadPackForRevision("cv.id = ?", variantId, revisionId))
	}
	fun historyDetailAt(variantId: UUID, position: Int): ContentVariantHistoryDetailResponse {
		if (position < 0) throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "History position must not be negative")
		val row = sqlExecutor.query(
			"""
			select id, revision_no, created_by_user_id, created_at
			from content_variant_revisions
			where workspace_id = ? and content_variant_id = ?
			order by created_at desc, revision_no desc, id desc
			limit 1 offset ?
			""".trimIndent(),
			{ rs, _ -> HistoryRevisionRow(requireNotNull(rs.getObject(1, UUID::class.java)), rs.getInt(2), rs.getObject(3, UUID::class.java), requireNotNull(rs.getTimestamp(4)).toInstant()) },
			devContext.devWorkspaceId,
			variantId,
			position,
		).firstOrNull() ?: notFound()
		val cause = historyCause(row.revisionNumber, row.createdByUserId)
		return ContentVariantHistoryDetailResponse(row.createdAt, cause, true, loadPackForRevision("cv.id = ?", variantId, row.revisionId))
	}
	private fun loadPack(predicate: String, id: UUID): ArtifactResponse = loadPackForRevision(predicate, id, null)
	internal fun artifactWorkflowRunIdForVariant(variantId: UUID): UUID = sqlExecutor.queryForObject(
		"select generation_run_id from content_variants where workspace_id = ? and id = ?",
		UUID::class.java,
		devContext.devWorkspaceId,
		variantId,
	) ?: notFound()
	private fun loadPackForRevision(predicate: String, id: UUID, revisionId: UUID?): ArtifactResponse {
		val header = sqlExecutor.query(
			"""
			select cp.id, cp.status, cp.title, cv.id, cv.status, coalesce(ar.content_type, 'CHANGELOG')
			from content_packs cp
			join content_variants cv on cv.workspace_id = cp.workspace_id and cv.content_pack_id = cp.id
			left join generation_runs gr
			  on gr.workspace_id = cp.workspace_id and gr.id = cp.generation_run_id
			left join agent_runs ar
			  on ar.workspace_id = gr.workspace_id and ar.id = gr.agent_run_id
			where cp.workspace_id = ? and $predicate and cv.variant_index = 0
			""".trimIndent(),
			{ rs, _ -> listOf(
				requireNotNull(rs.getObject(1, UUID::class.java)),
				requireNotNull(rs.getString(2)),
				rs.getString(3),
				requireNotNull(rs.getObject(4, UUID::class.java)),
				requireNotNull(rs.getString(5)),
				requireNotNull(rs.getString(6)),
			) },
			devContext.devWorkspaceId, id,
		).firstOrNull() ?: notFound()
		val variantId = header[3] as UUID
		val revision = revisionId?.let { requestedRevision ->
			sqlExecutor.query(
				"select id, generation_run_id, revision_no, lexical_content::text from content_variant_revisions where workspace_id = ? and id = ? and content_variant_id = ?",
					{ rs, _ -> CurrentArtifactRevision(
						requireNotNull(rs.getObject(1, UUID::class.java)),
						requireNotNull(rs.getObject(2, UUID::class.java)),
						rs.getInt(3),
						objectMapper.readTree(requireNotNull(rs.getString(4))),
					) },
				devContext.devWorkspaceId,
				requestedRevision,
				variantId,
			).firstOrNull() ?: notFound()
		} ?: materializer.currentArtifactRevision(variantId)
		val citations = loadPublicCitations(variantId, revision.id, includeHistoricalLifecycle = revisionId != null)
		val sentences = loadSentences(variantId, revision.id, citations)
		val relatedArtifacts = contentSourceSnapshotService.findRelatedArtifacts(devContext.devWorkspaceId, header[0] as UUID)
		return ArtifactResponse(
			header[0] as UUID,
			header[1] as String,
			header[2] as String?,
			header[5] as String,
				ContentVariantResponse(
					variantId,
					header[4] as String,
					revision.id,
					revision.revisionNumber,
					revision.lexicalContent,
					sentences,
					publicSources(citations),
					documentVersion(revision.lexicalContent),
					confirmedDestinations(variantId),
				),
			loadLivePublication(variantId),
			relatedArtifacts,
		)
	}

	private fun loadLivePublication(variantId: UUID): ArtifactPublicationResponse? = sqlExecutor.query(
		"""
		select pce.id, pce.entry_slug, pce.published_at, w.slug
		from published_changelog_entries pce
		join workspaces w on w.id = pce.workspace_id
		where pce.workspace_id = ? and pce.content_variant_id = ? and pce.unpublished_at is null
		""".trimIndent(),
		{ rs, _ ->
			val entrySlug = requireNotNull(rs.getString(2))
			ArtifactPublicationResponse(
				entryId = requireNotNull(rs.getObject(1, UUID::class.java)),
				entrySlug = entrySlug,
				publicPath = "/${requireNotNull(rs.getString(4))}/changelog/$entrySlug",
				publishedAt = requireNotNull(rs.getTimestamp(3)).toInstant(),
			)
		},
		devContext.devWorkspaceId,
		variantId,
	).firstOrNull()
	private fun loadSentences(
		variantId: UUID,
		revisionId: UUID,
		citations: Map<UUID, List<PublicCitation>>,
	): List<ContentSentenceResponse> = sqlExecutor.query(
		"""
		select rs.sentence_id, r.id, r.revision_no, rs.order_index, r.body, r.origin
		from content_variant_revision_sentences rs
		join content_variant_sentence_revisions r
		  on r.workspace_id = rs.workspace_id and r.id = rs.sentence_revision_id
		where rs.workspace_id = ? and rs.content_variant_revision_id = ? and rs.content_variant_id = ?
		order by rs.order_index
		""".trimIndent(),
		{ rs, _ ->
			val sentenceId = requireNotNull(rs.getObject(1, UUID::class.java))
			ContentSentenceResponse(
				sentenceId,
				requireNotNull(rs.getObject(2, UUID::class.java)),
				rs.getInt(3),
				rs.getInt(4),
				requireNotNull(rs.getString(5)),
				requireNotNull(rs.getString(6)),
				citations[sentenceId].orEmpty().map { it.response },
			)
		},
		devContext.devWorkspaceId, revisionId, variantId,
	)
	internal fun loadPublicCitations(
		variantId: UUID,
		revisionId: UUID,
		includeHistoricalLifecycle: Boolean = false,
	): Map<UUID, List<PublicCitation>> = sqlExecutor.query(
		"""
			select rs.sentence_id, c.generation_input_id, i.source_provider, i.source_label, i.original_url,
		       case
		         when coalesce(i.source_scope_id, gr.source_scope_id) is null then true
		         when sc.status = 'ACTIVE' and exists (
		           select 1
		           from connection_namespace_bindings b
		           join connections conn on conn.workspace_id = b.workspace_id and conn.id = b.connection_id
		           where b.workspace_id = sc.workspace_id
		             and b.source_namespace_id = sc.source_namespace_id
		             and b.provider = sc.provider
		             and b.status = 'ACTIVE'
		             and conn.provider = sc.provider
		             and conn.status = 'ACTIVE'
		         ) then true
		         else false
		       end as source_access
		       , sc.metadata ->> 'visibility' as source_visibility,
		       c.status
		from content_variant_revision_sentences rs
		join sentence_citations c
		  on c.workspace_id = rs.workspace_id and c.sentence_id = rs.sentence_id
		 and c.sentence_revision_id = rs.sentence_revision_id
		 and c.status ${if (includeHistoricalLifecycle) "in ('ACTIVE', 'STALE', 'REMOVED')" else "= 'ACTIVE'"}
		join generation_inputs i on i.workspace_id = c.workspace_id and i.id = c.generation_input_id
		join generation_runs gr on gr.workspace_id = c.workspace_id and gr.id = i.generation_run_id
		left join source_scopes sc on sc.workspace_id = i.workspace_id and sc.id = coalesce(i.source_scope_id, gr.source_scope_id)
		where rs.workspace_id = ? and rs.content_variant_revision_id = ? and rs.content_variant_id = ?
		order by rs.sentence_id, c.citation_order
		""".trimIndent(),
		{ rs, _ ->
			val provider = requireNotNull(rs.getString(3))
			val sourceLabel = requireNotNull(rs.getString(4)).trim()
			if (sourceLabel.isBlank()) {
				null
			} else if (provider.equals("USER_CONFIRMED", ignoreCase = true)) {
				PublicCitation(
					requireNotNull(rs.getObject(1, UUID::class.java)),
					requireNotNull(rs.getObject(2, UUID::class.java)),
					provider,
					sourceLabel,
						 null,
						rs.getString(7),
						requireNotNull(rs.getString(8)),
				)
			} else {
				val originalUrl = safeHttpUrl(rs.getString(5))
				val accessible = rs.getBoolean(6) && originalUrl != null && approvedPublicUrl(provider, originalUrl)
				if (!accessible) {
					null
				} else {
					PublicCitation(
						requireNotNull(rs.getObject(1, UUID::class.java)),
						requireNotNull(rs.getObject(2, UUID::class.java)),
						provider,
						sourceLabel,
						 originalUrl,
						rs.getString(7),
						requireNotNull(rs.getString(8)),
					)
				}
			}
		},
		devContext.devWorkspaceId, revisionId, variantId,
	).filterNotNull().filter { it.sourceLabel.isNotBlank() }.groupBy { it.sentenceId }
	private fun publicSources(citations: Map<UUID, List<PublicCitation>>): List<ContentSourceResponse> = citations.values
		.flatten()
		.groupBy { it.evidenceId }
		.values
		.map { rows ->
			val first = rows.first()
			ContentSourceResponse(first.evidenceId, first.provider, first.sourceLabel, first.originalUrl, rows.map { it.sentenceId }.distinct())
		}
		.sortedWith(compareBy(ContentSourceResponse::sourceLabel, ContentSourceResponse::evidenceId))
	private fun confirmedDestinations(variantId: UUID): List<com.plot.api.artifact.dto.ContentBriefDestinationResponse> {
		val snapshot = sqlExecutor.query(
			"""
			select ar.content_brief_snapshot::text
			from content_variants cv
			join generation_runs gr on gr.workspace_id = cv.workspace_id and gr.id = cv.generation_run_id
			join agent_runs ar on ar.workspace_id = gr.workspace_id and ar.id = gr.agent_run_id
			where cv.workspace_id = ? and cv.id = ?
			""".trimIndent(),
			{ rs, _ -> rs.getString(1) },
			devContext.devWorkspaceId,
			variantId,
		).firstOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()
		return runCatching {
			objectMapper.readValue(snapshot, ContentBrief::class.java).destinations.map { destination ->
				com.plot.api.artifact.dto.ContentBriefDestinationResponse(destination.id, destination.label, destination.url)
			}
		}.getOrDefault(emptyList())
	}
	internal fun loadExportSentences(
		variantId: UUID,
		revisionId: UUID,
		publicCitations: Map<UUID, List<PublicCitation>>,
	): List<ExportSentence> = sqlExecutor.query(
		"""
		select rs.sentence_id, r.id, r.revision_no, rs.order_index, r.body, r.origin,
		       e.verdict, e.reason, gr.error_code
		from content_variant_revision_sentences rs
		join content_variant_sentence_revisions r
		  on r.workspace_id = rs.workspace_id and r.id = rs.sentence_revision_id
		join content_variants cv on cv.workspace_id = rs.workspace_id and cv.id = rs.content_variant_id
		join generation_runs gr on gr.workspace_id = cv.workspace_id and gr.id = cv.generation_run_id
		left join lateral (
		  select verdict, reason from sentence_evaluations se
		  where se.workspace_id = rs.workspace_id and se.sentence_revision_id = rs.sentence_revision_id
		  order by se.review_attempt desc limit 1
		) e on true
		where rs.workspace_id = ? and rs.content_variant_revision_id = ? and rs.content_variant_id = ?
		order by rs.order_index
		""".trimIndent(),
		{ rs, _ ->
				val sentenceId = requireNotNull(rs.getObject(1, UUID::class.java))
				val revisionIdValue = requireNotNull(rs.getObject(2, UUID::class.java))
				val origin = requireNotNull(rs.getString(6))
			val verdict = rs.getString(7)
			val status = when {
				origin == "USER_MODIFIED" -> ExportSentenceStatus.USER_MODIFIED
				verdict == "SUPPORTED" && publicCitations[sentenceId].orEmpty().isNotEmpty() -> ExportSentenceStatus.SUPPORTED
				verdict == "SUPPORTED" -> ExportSentenceStatus.NEEDS_SUPPORT
				verdict == "NOT_REQUIRED" -> ExportSentenceStatus.NOT_REQUIRED
				verdict == "CONFLICT" -> ExportSentenceStatus.CONFLICT
				verdict == "NEEDS_SUPPORT" -> ExportSentenceStatus.NEEDS_SUPPORT
				rs.getString(9) != null -> ExportSentenceStatus.REVIEW_FAILED
				else -> ExportSentenceStatus.NEEDS_SUPPORT
			}
			ExportSentence(
					sentenceId, revisionIdValue, rs.getInt(4), requireNotNull(rs.getString(5)), status,
				publicCitations[sentenceId].orEmpty().mapIndexed { index, citation ->
					SentenceCitation(sentenceId, revisionIdValue, citation.evidenceId, index, CitationStatus.ACTIVE)
				},
			)
		},
		devContext.devWorkspaceId, revisionId, variantId,
	)
	internal fun loadEvidence(runId: UUID): List<EvidenceSnapshot> = sqlExecutor.query(
		"""
		select id, writing_block_id, order_index, source_provider, source_kind, source_label, snapshot_title,
		 snapshot_body, snapshot_excerpt, original_url, source_created_at, source_updated_at, content_hash, captured_at
		from generation_inputs where workspace_id = ? and generation_run_id = ? order by order_index
		""".trimIndent(),
			{ rs, _ -> EvidenceSnapshot(
				requireNotNull(rs.getObject(1, UUID::class.java)),
				runId,
				rs.getObject(2, UUID::class.java),
				rs.getInt(3),
				SourceProvider.valueOf(requireNotNull(rs.getString(4))),
				requireNotNull(rs.getString(5)),
				requireNotNull(rs.getString(6)),
				rs.getString(7),
				requireNotNull(rs.getString(8)),
				rs.getString(9),
				rs.getString(10),
				rs.getTimestamp(11)?.toInstant(),
				rs.getTimestamp(12)?.toInstant(),
				requireNotNull(rs.getString(13)),
				requireNotNull(rs.getTimestamp(14)).toInstant(),
		) },
		devContext.devWorkspaceId, runId,
	)
	private fun documentVersion(lexicalContent: JsonNode): Int = lexicalContent.get("documentVersion")
		?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
		?.asInt()
		?: 1

	private fun historyCause(revisionNumber: Int, createdByUserId: UUID?): String {
		if (revisionNumber == 1) return "Initial draft"
		if (createdByUserId == devContext.devUserId) return "Edited by you"
		val displayName = createdByUserId?.let {
			sqlExecutor.query(
				"select display_name from users where id = ?",
				{ rs, _ -> rs.getString(1) },
				it,
			).firstOrNull()
		}
		return "Edited by ${displayName?.takeIf { it.isNotBlank() } ?: "someone"}"
	}
	private fun safeHttpUrl(value: String?): String? = try {
		val uri = URI(value?.trim() ?: return null)
		if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.isOpaque || uri.rawUserInfo != null) return null
		uri.toASCIIString().takeIf { encoded -> encoded.none { it.isISOControl() || it == '<' || it == '>' || it == '"' || it == '\'' } }
	} catch (_: IllegalArgumentException) {
		null
	}
	private fun approvedPublicUrl(provider: String, value: String): Boolean = try {
		val uri = URI(value)
		provider.uppercase() == "GITHUB" && uri.host?.lowercase() in setOf("github.com", "github.test") &&
			uri.scheme?.lowercase() == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)
	} catch (_: IllegalArgumentException) {
		false
	}
	private fun notFound(): Nothing = throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Content pack not found")
}
private data class HistoryRevisionRow(
    val revisionId: UUID,
    val revisionNumber: Int,
    val createdByUserId: UUID?,
    val createdAt: java.time.Instant,
) {
    constructor(revisionNumber: Int, createdByUserId: UUID?, createdAt: java.time.Instant) : this(
        revisionId = UUID(0, 0),
        revisionNumber = revisionNumber,
        createdByUserId = createdByUserId,
        createdAt = createdAt,
    )
}
internal data class PublicCitation(
    val sentenceId: UUID,
    val evidenceId: UUID,
    val provider: String,
    val sourceLabel: String,
	val originalUrl: String?,
	val sourceVisibility: String?,
	val status: String,
) {
	val response: ContentCitationResponse
		get() = ContentCitationResponse(evidenceId, provider, sourceLabel, originalUrl, status)
}
