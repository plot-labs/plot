package com.plot.api.contentpack

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.contentpack.dto.ContentCitationResponse
import com.plot.api.contentpack.dto.ContentExportResponse
import com.plot.api.contentpack.dto.ContentPackPageResponse
import com.plot.api.contentpack.dto.ContentPackResponse
import com.plot.api.contentpack.dto.ContentPackSummaryResponse
import com.plot.api.contentpack.dto.ContentSentenceResponse
import com.plot.api.contentpack.dto.ContentSourceResponse
import com.plot.api.contentpack.dto.ContentStatementInput
import com.plot.api.contentpack.dto.ContentVariantResponse
import com.plot.api.contentpack.dto.ExportDisposition
import com.plot.api.contentpack.dto.ExportWarningResponse
import com.plot.api.dev.DevContext
import com.plot.api.generation.model.CitationStatus
import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ExportSentence
import com.plot.api.generation.model.ExportSentenceStatus
import com.plot.api.generation.model.SentenceCitation
import com.plot.api.generation.model.SourceProvider
import com.plot.api.generation.model.ExportSource
import java.net.URI
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.util.HexFormat
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Service
class ContentPackService(
	private val jdbcTemplate: JdbcTemplate,
	private val transactionTemplate: TransactionTemplate,
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val markdownExportService: MarkdownExportService,
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun list(page: Int, size: Int): ContentPackPageResponse {
		require(page >= 0) { "Page must not be negative" }
		require(size in 1..100) { "Size must be between 1 and 100" }
		val total = jdbcTemplate.queryForObject(
			"select count(*) from content_packs where workspace_id = ?", Long::class.java, devContext.devWorkspaceId,
		) ?: 0L
		val items = jdbcTemplate.query(
			"""
			select id, generation_run_id, status, title from content_packs
			where workspace_id = ? order by created_at desc, id desc limit ? offset ?
			""".trimIndent(),
			{ rs, _ -> ContentPackSummaryResponse(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4)) },
			devContext.devWorkspaceId, size, page * size,
		)
		return ContentPackPageResponse(items, page, size, total, if (total == 0L) 0 else ((total + size - 1) / size).toInt())
	}

	fun get(packId: UUID): ContentPackResponse = loadPack("cp.id = ?", packId)

	fun getVariant(variantId: UUID): ContentPackResponse = loadPack("cv.id = ?", variantId)

	fun findByRun(runId: UUID): ContentPackResponse? = try {
		loadPack("cp.generation_run_id = ?", runId)
	} catch (_: ApiException) {
		null
	}

	fun saveVariant(
		variantId: UUID,
		expectedRevisionNumber: Int,
		lexicalContent: JsonNode,
		statements: List<ContentStatementInput>,
	): ContentPackResponse = transactionTemplate.execute {
		saveVariantInTransaction(variantId, expectedRevisionNumber, lexicalContent, statements)
	}

	/**
	 * Compatibility endpoint for older clients. It still creates an artifact
	 * revision, so the old sentence operation cannot bypass optimistic locking
	 * or the public-source projection.
	 */
	fun editSentence(variantId: UUID, sentenceId: UUID, expectedRevisionNumber: Int, body: String): ContentPackResponse =
		transactionTemplate.execute {
			lockVariant(variantId)
			val currentSentence = jdbcTemplate.query(
				"""
				select r.revision_no
				from content_variant_sentence_revisions r
				where r.workspace_id = ? and r.content_variant_id = ? and r.sentence_id = ? and r.is_current
				for update
				""".trimIndent(),
				{ rs, _ -> rs.getInt(1) },
				devContext.devWorkspaceId, variantId, sentenceId,
			).firstOrNull() ?: notFound()
			if (currentSentence != expectedRevisionNumber) {
				throw ApiException(HttpStatus.CONFLICT, "STALE_SENTENCE_REVISION", "Sentence revision is stale", sentenceId)
			}
			val current = ensureArtifactRevision(variantId)
			val replacement = body.trim().takeIf { it.isNotBlank() }
				?: throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Sentence body is required")
			val statements = loadCurrentStatements(current.id, variantId).map { statement ->
				ContentStatementInput(statement.id, statement.orderIndex, if (statement.id == sentenceId) replacement else statement.body)
			}
			saveVariantInTransaction(variantId, current.revisionNumber, current.lexicalContent, statements)
		}

	fun export(
		variantId: UUID,
		expectedRevisionNumber: Int?,
		includeSources: Boolean,
		acknowledge: Boolean,
		acknowledgedWarningKeys: List<String>,
		legacyAcknowledgedRevisionIds: List<UUID>,
		disposition: ExportDisposition,
	): ContentExportResponse {
		val outcome = transactionTemplate.execute {
			jdbcTemplate.query(
				"select id from content_variants where workspace_id = ? and id = ? for update",
				{ rs, _ -> rs.getObject(1, UUID::class.java) },
				devContext.devWorkspaceId,
				variantId,
			).firstOrNull() ?: notFound()
			val revision = ensureArtifactRevision(variantId)
			if (expectedRevisionNumber != null && expectedRevisionNumber != revision.revisionNumber) {
				throw staleArtifactRevision(variantId)
			}
			val projection = loadPack("cv.id = ?", variantId)
			val evidence = loadEvidence(projection.generationRunId)
			val publicCitations = loadPublicCitations(variantId, revision.id)
			val exportSentences = loadExportSentences(variantId, revision.id, publicCitations)
			val unresolved = exportSentences.filter { it.status.isUnresolved }
			val warnings = unresolved.map { sentence ->
				ExportWarningResponse(
					warningKey(revision.id, sentence),
					sentence.orderIndex + 1,
					sentence.body.trim().replace(WHITESPACE, " ").take(MAX_WARNING_EXCERPT),
				)
			}
			val expectedWarningKeys = warnings.mapTo(linkedSetOf()) { it.key }
			val legacyRevisionSet = legacyAcknowledgedRevisionIds.toSet()
			val legacyMatches = legacyRevisionSet == unresolved.map { it.revisionId }.toSet()
			val keyMatches = acknowledgedWarningKeys.toSet() == expectedWarningKeys
			if (unresolved.isNotEmpty() && !acknowledge) {
				recordExport(
					revision, projection.generationRunId, variantId, disposition, includeSources,
					unresolved, warnings.map { it.key },  false, "REJECTED", null, null,
				)
				return@execute ExportAttempt.ConfirmationRequired(warnings, unresolved.map { it.id }, unresolved.map { it.revisionId })
			}
			if (unresolved.isNotEmpty() && acknowledge && !keyMatches && !legacyMatches) {
				recordExport(
					revision, projection.generationRunId, variantId, disposition, includeSources,
					unresolved, acknowledgedWarningKeys, false, "REJECTED", null, null,
				)
				return@execute ExportAttempt.ConfirmationRequired(warnings, unresolved.map { it.id }, unresolved.map { it.revisionId })
			}

			val rendered = markdownExportService.render(
				exportSentences,
				evidence,
				acknowledgeUnresolved = acknowledge && unresolved.isNotEmpty(),
				includeSources = includeSources,
				 sources = publicCitations.values.flatten()
					.distinctBy { it.originalUrl }
					.map { ExportSource(it.evidenceId, it.provider, it.sourceLabel, it.originalUrl) },
			)
			val outputHash = sha256(rendered.markdown)
			val sourceInputs = publicCitations.values.flatten()
				.distinctBy { it.originalUrl }
				.sortedBy { it.originalUrl }
				.joinToString("|") { "${it.originalUrl}|${it.provider}|${it.sourceLabel}" }
			val inputHash = sha256(
				listOf(
					revision.id,
					revision.revisionNumber,
					MARKDOWN_RENDERER_VERSION,
					includeSources,
					acknowledge && unresolved.isNotEmpty(),
					warnings.map { it.key }.sorted(),
					sourceInputs,
				).joinToString("|"),
			)
			val exportId = findSuccessfulExport(
				revision, projection.generationRunId, variantId, disposition, includeSources,
				rendered.unresolvedCount, rendered.warningAcknowledged, inputHash, outputHash,
			) ?: recordExport(
				revision, projection.generationRunId, variantId, disposition, includeSources,
				exportSentences, warnings.map { it.key }, rendered.warningAcknowledged,
				"SUCCEEDED", inputHash, outputHash,
			)
			ExportAttempt.Completed(ContentExportResponse(
				exportId,
				revision.id,
				revision.revisionNumber,
				disposition,
				"plot-changelog-${projection.id}.md",
				"text/markdown;charset=UTF-8",
				rendered.markdown,
				rendered.unresolvedCount,
				rendered.warningAcknowledged,
				includeSources,
			))
		}
		return when (outcome) {
			is ExportAttempt.Completed -> outcome.response
			is ExportAttempt.ConfirmationRequired -> throw ExportConfirmationRequiredException(
				outcome.warnings,
				outcome.sentenceIds,
				outcome.revisionIds,
			)
		}
	}

	private fun saveVariantInTransaction(
		variantId: UUID,
		expectedRevisionNumber: Int,
		lexicalContent: JsonNode,
		statements: List<ContentStatementInput>,
	): ContentPackResponse {
		if (!lexicalContent.isObject) {
			throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Lexical content must be a JSON object")
		}
		lockVariant(variantId)
		val currentRevision = currentArtifactRevisionForUpdate(variantId)
		if (currentRevision.revisionNumber != expectedRevisionNumber) throw staleArtifactRevision(variantId)
		val now = clock.instant()
		val previousStatements = loadCurrentStatements(currentRevision.id, variantId)
		val previousById = previousStatements.associateBy { it.id }
		val allSentenceIds = jdbcTemplate.query(
			"select id from content_variant_sentences where workspace_id = ? and content_variant_id = ?",
			{ rs, _ -> rs.getObject(1, UUID::class.java) },
			devContext.devWorkspaceId, variantId,
		).toSet()
		val currentRevisionBySentence = jdbcTemplate.query(
			"select sentence_id, id, revision_no, body, origin from content_variant_sentence_revisions where workspace_id = ? and content_variant_id = ? and is_current",
			{ rs, _ -> rs.getObject(1, UUID::class.java) to StatementRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getInt(3), 0, rs.getString(4), rs.getString(5)) },
			devContext.devWorkspaceId, variantId,
		).toMap()
		val requested = statements
		val normalized = requested.mapIndexed { index, input ->
			val id = input.id ?: uuidGenerator.next()
			val order = input.orderIndex ?: index
			val body = input.body?.trim()?.takeIf { it.isNotBlank() }
				?: throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Statement body is required")
			if (input.id != null && id !in allSentenceIds) {
				throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Statement belongs to another artifact", id)
			}
			NormalizedStatement(id, order, body)
		}.also { rows ->
			if (rows.map { it.id }.distinct().size != rows.size) {
				throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Statement IDs must be unique")
			}
			if (rows.map { it.orderIndex }.distinct().size != rows.size) {
				throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Statement order must be unique")
			}
		}

		val nextRevisionBySentence = linkedMapOf<UUID, UUID>()
		normalized.sortedBy { it.orderIndex }.forEach { statement ->
			val previous = previousById[statement.id]
			val existingRevision = currentRevisionBySentence[statement.id]
			if (previous == null && existingRevision == null) {
				insertNewSentence(variantId, statement.id, now)
				val revisionId = insertSentenceRevision(variantId, statement.id, 1, statement.body, now)
				nextRevisionBySentence[statement.id] = revisionId
			} else if ((previous?.body ?: existingRevision?.body) != statement.body) {
				val previousRevisionId = previous?.revisionId ?: existingRevision!!.revisionId
				val previousRevisionNumber = previous?.revisionNumber ?: existingRevision!!.revisionNumber
				jdbcTemplate.update(
					"update content_variant_sentence_revisions set is_current = false where workspace_id = ? and id = ? and is_current",
					devContext.devWorkspaceId, previousRevisionId,
				)
				val revisionId = insertSentenceRevision(variantId, statement.id, previousRevisionNumber + 1, statement.body, now)
				nextRevisionBySentence[statement.id] = revisionId
				jdbcTemplate.update(
					"update sentence_citations set status = 'STALE', stale_reason = 'STATEMENT_CHANGED', updated_at = ? where workspace_id = ? and sentence_id = ? and status = 'ACTIVE'",
					Timestamp.from(now), devContext.devWorkspaceId, statement.id,
				)
			} else {
				nextRevisionBySentence[statement.id] = previous?.revisionId ?: existingRevision!!.revisionId
			}
		}

		val retainedIds = normalized.mapTo(linkedSetOf()) { it.id }
		previousStatements.filter { it.id !in retainedIds }.forEach { removed ->
			jdbcTemplate.update(
				"update sentence_citations set status = 'REMOVED', stale_reason = 'STATEMENT_REMOVED', updated_at = ? where workspace_id = ? and sentence_id = ? and status = 'ACTIVE'",
				Timestamp.from(now), devContext.devWorkspaceId, removed.id,
			)
		}
		val nextRevisionId = uuidGenerator.next()
		val nextRevisionNumber = currentRevision.revisionNumber + 1
		jdbcTemplate.update(
			"update content_variant_revisions set is_current = false where workspace_id = ? and id = ? and is_current",
			devContext.devWorkspaceId, currentRevision.id,
		)
		jdbcTemplate.update(
			"""
			insert into content_variant_revisions (
			 id, workspace_id, generation_run_id, content_variant_id, revision_no,
			 lexical_content, is_current, created_by_user_id, created_at
			) values (?, ?, ?, ?, ?, ?::jsonb, true, ?, ?)
			""".trimIndent(),
			nextRevisionId,
			devContext.devWorkspaceId,
			currentRevision.generationRunId,
			variantId,
			nextRevisionNumber,
			lexicalContent.toString(),
			devContext.devUserId,
			Timestamp.from(now),
		)
		normalized.sortedBy { it.orderIndex }.forEach { statement ->
			jdbcTemplate.update(
				"""
				insert into content_variant_revision_sentences (
				 id, workspace_id, content_variant_revision_id, generation_run_id,
				 content_variant_id, sentence_id, sentence_revision_id, order_index
				) values (?, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent(),
				uuidGenerator.next(), devContext.devWorkspaceId, nextRevisionId,
				currentRevision.generationRunId, variantId, statement.id,
				nextRevisionBySentence.getValue(statement.id), statement.orderIndex,
			)
		}
		jdbcTemplate.update(
			"update content_variants set updated_at = ? where workspace_id = ? and id = ?",
			Timestamp.from(now), devContext.devWorkspaceId, variantId,
		)
		jdbcTemplate.update(
			"update content_packs set updated_at = ? where workspace_id = ? and id = (select content_pack_id from content_variants where workspace_id = ? and id = ?)",
			Timestamp.from(now), devContext.devWorkspaceId, devContext.devWorkspaceId, variantId,
		)
		return loadPack("cv.id = ?", variantId)
	}

	private fun insertNewSentence(variantId: UUID, sentenceId: UUID, now: java.time.Instant) {
		val generationRunId = jdbcTemplate.queryForObject(
			"select generation_run_id from content_variants where workspace_id = ? and id = ?",
			UUID::class.java, devContext.devWorkspaceId, variantId,
		) ?: notFound()
		val orderIndex = (jdbcTemplate.queryForObject(
			"select coalesce(max(order_index), -1) + 1 from content_variant_sentences where workspace_id = ? and content_variant_id = ?",
			Int::class.java, devContext.devWorkspaceId, variantId,
		) ?: 0)
		jdbcTemplate.update(
			"insert into content_variant_sentences (id, workspace_id, generation_run_id, content_variant_id, stable_key, order_index, created_at) values (?, ?, ?, ?, ?, ?, ?)",
			sentenceId, devContext.devWorkspaceId, generationRunId, variantId, sentenceId.toString(), orderIndex, Timestamp.from(now),
		)
	}

	private fun lockVariant(variantId: UUID) {
		jdbcTemplate.query(
			"select id from content_variants where workspace_id = ? and id = ? for update",
			{ rs, _ -> rs.getObject(1, UUID::class.java) },
			devContext.devWorkspaceId, variantId,
		).firstOrNull() ?: notFound()
	}

	private fun insertSentenceRevision(variantId: UUID, sentenceId: UUID, revisionNumber: Int, body: String, now: java.time.Instant): UUID {
		val generationRunId = jdbcTemplate.queryForObject(
			"select generation_run_id from content_variants where workspace_id = ? and id = ?",
			UUID::class.java, devContext.devWorkspaceId, variantId,
		) ?: notFound()
		val revisionId = uuidGenerator.next()
		jdbcTemplate.update(
			"""
			insert into content_variant_sentence_revisions (
			 id, workspace_id, generation_run_id, content_variant_id, sentence_id,
			 revision_no, origin, body, is_current, created_by_user_id, created_at
			) values (?, ?, ?, ?, ?, ?, 'USER_MODIFIED', ?, true, ?, ?)
			""".trimIndent(),
			revisionId, devContext.devWorkspaceId, generationRunId, variantId, sentenceId,
			revisionNumber, body, devContext.devUserId, Timestamp.from(now),
		)
		return revisionId
	}

	private fun findSuccessfulExport(
		revision: CurrentArtifactRevision,
		runId: UUID,
		variantId: UUID,
		disposition: ExportDisposition,
		includeSources: Boolean,
		unresolved: Int,
		acknowledged: Boolean,
		inputHash: String,
		outputHash: String,
	): UUID? = jdbcTemplate.query(
		"""
		select id from generation_export_events
		where workspace_id = ? and generation_run_id = ? and content_variant_id = ?
		  and artifact_revision_id = ? and artifact_revision_no = ?
		  and format = 'MARKDOWN' and disposition = ? and status = 'SUCCEEDED'
		  and unresolved_count = ? and warning_acknowledged = ?
		  and include_sources = ? and renderer_version = ?
		  and export_input_hash = ? and output_content_hash = ? and created_by_user_id = ?
		order by created_at, id limit 1
		""".trimIndent(),
		{ rs, _ -> rs.getObject(1, UUID::class.java) },
		devContext.devWorkspaceId, runId, variantId, revision.id, revision.revisionNumber,
		disposition.name, unresolved, acknowledged, includeSources, MARKDOWN_RENDERER_VERSION,
		inputHash, outputHash, devContext.devUserId,
	).firstOrNull()

	private fun loadPack(predicate: String, id: UUID): ContentPackResponse {
		val header = jdbcTemplate.query(
			"""
			select cp.id, cp.generation_run_id, cp.status, cp.title, cv.id, cv.status
			from content_packs cp join content_variants cv on cv.workspace_id = cp.workspace_id and cv.content_pack_id = cp.id
			where cp.workspace_id = ? and $predicate and cv.variant_index = 0
			""".trimIndent(),
			{ rs, _ -> listOf(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getObject(5, UUID::class.java), rs.getString(6)) },
			devContext.devWorkspaceId, id,
		).firstOrNull() ?: notFound()
		val variantId = header[4] as UUID
		val revision = currentArtifactRevision(variantId)
		val citations = loadPublicCitations(variantId, revision.id)
		val sentences = loadSentences(variantId, revision.id, citations)
		return ContentPackResponse(
			header[0] as UUID,
			header[1] as UUID,
			header[2] as String,
			header[3] as String?,
			ContentVariantResponse(
				variantId,
				header[5] as String,
				revision.id,
				revision.revisionNumber,
				revision.lexicalContent,
				sentences,
				publicSources(citations),
			),
		)
	}

	private fun loadSentences(
		variantId: UUID,
		revisionId: UUID,
		citations: Map<UUID, List<PublicCitation>>,
	): List<ContentSentenceResponse> = jdbcTemplate.query(
		"""
		select rs.sentence_id, r.id, r.revision_no, rs.order_index, r.body, r.origin
		from content_variant_revision_sentences rs
		join content_variant_sentence_revisions r
		  on r.workspace_id = rs.workspace_id and r.id = rs.sentence_revision_id
		where rs.workspace_id = ? and rs.content_variant_revision_id = ? and rs.content_variant_id = ?
		order by rs.order_index
		""".trimIndent(),
		{ rs, _ ->
			val sentenceId = rs.getObject(1, UUID::class.java)
			ContentSentenceResponse(
				sentenceId,
				rs.getObject(2, UUID::class.java),
				rs.getInt(3),
				rs.getInt(4),
				rs.getString(5),
				rs.getString(6),
				citations[sentenceId].orEmpty().map { it.response },
			)
		},
		devContext.devWorkspaceId, revisionId, variantId,
	)

	private fun loadPublicCitations(variantId: UUID, revisionId: UUID): Map<UUID, List<PublicCitation>> = jdbcTemplate.query(
		"""
		select rs.sentence_id, c.generation_input_id, i.source_provider, i.source_label, i.original_url,
		       case
		         when gr.source_scope_id is null then true
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
		from content_variant_revision_sentences rs
		join sentence_citations c
		  on c.workspace_id = rs.workspace_id and c.sentence_id = rs.sentence_id
		 and c.sentence_revision_id = rs.sentence_revision_id and c.status = 'ACTIVE'
		join generation_inputs i on i.workspace_id = c.workspace_id and i.id = c.generation_input_id
		join generation_runs gr on gr.workspace_id = c.workspace_id and gr.id = i.generation_run_id
		left join source_scopes sc on sc.workspace_id = gr.workspace_id and sc.id = gr.source_scope_id
		where rs.workspace_id = ? and rs.content_variant_revision_id = ? and rs.content_variant_id = ?
		order by rs.sentence_id, c.citation_order
		""".trimIndent(),
		{ rs, _ ->
			val originalUrl = safeHttpUrl(rs.getString(5))
			val accessible = rs.getBoolean(6) && originalUrl != null && approvedPublicUrl(rs.getString(3), originalUrl)
			if (!accessible) {
				null
			} else {
				PublicCitation(
					rs.getObject(1, UUID::class.java),
					rs.getObject(2, UUID::class.java),
					rs.getString(3),
					rs.getString(4).trim(),
					originalUrl,
				)
			}
		},
		devContext.devWorkspaceId, revisionId, variantId,
	).filterNotNull().filter { it.sourceLabel.isNotBlank() }.groupBy { it.sentenceId }

	private fun publicSources(citations: Map<UUID, List<PublicCitation>>): List<ContentSourceResponse> = citations.values
		.flatten()
		.groupBy { it.originalUrl }
		.values
		.map { rows ->
			val first = rows.first()
			ContentSourceResponse(first.evidenceId, first.provider, first.sourceLabel, first.originalUrl, rows.map { it.sentenceId }.distinct())
		}
		.sortedWith(compareBy(ContentSourceResponse::sourceLabel, ContentSourceResponse::evidenceId))

	private fun loadExportSentences(
		variantId: UUID,
		revisionId: UUID,
		publicCitations: Map<UUID, List<PublicCitation>>,
	): List<ExportSentence> = jdbcTemplate.query(
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
			val sentenceId = rs.getObject(1, UUID::class.java)
			val revisionIdValue = rs.getObject(2, UUID::class.java)
			val origin = rs.getString(6)
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
				sentenceId, revisionIdValue, rs.getInt(4), rs.getString(5), status,
				publicCitations[sentenceId].orEmpty().mapIndexed { index, citation ->
					SentenceCitation(sentenceId, revisionIdValue, citation.evidenceId, index, CitationStatus.ACTIVE)
				},
			)
		},
		devContext.devWorkspaceId, revisionId, variantId,
	)

	private fun loadEvidence(runId: UUID): List<EvidenceSnapshot> = jdbcTemplate.query(
		"""
		select id, writing_block_id, order_index, source_provider, source_kind, source_label, snapshot_title,
		 snapshot_body, snapshot_excerpt, original_url, source_created_at, source_updated_at, content_hash, captured_at
		from generation_inputs where workspace_id = ? and generation_run_id = ? order by order_index
		""".trimIndent(),
		{ rs, _ -> EvidenceSnapshot(
			rs.getObject(1, UUID::class.java), runId, rs.getObject(2, UUID::class.java), rs.getInt(3), SourceProvider.valueOf(rs.getString(4)),
			rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
			rs.getTimestamp(11)?.toInstant(), rs.getTimestamp(12)?.toInstant(), rs.getString(13), rs.getTimestamp(14).toInstant(),
		) },
		devContext.devWorkspaceId, runId,
	)

	private fun recordExport(
		revision: CurrentArtifactRevision,
		runId: UUID,
		variantId: UUID,
		disposition: ExportDisposition,
		includeSources: Boolean,
		sentences: List<ExportSentence>,
		warningKeys: List<String>,
		acknowledged: Boolean,
		status: String,
		inputHash: String?,
		outputHash: String?,
	): UUID = uuidGenerator.next().also { id ->
			jdbcTemplate.update(
				"""
				insert into generation_export_events (
				 id, workspace_id, generation_run_id, content_variant_id, artifact_revision_id,
				 artifact_revision_no, format, disposition, status, unresolved_count,
				 warning_acknowledged, sentence_ids, acknowledged_warning_keys, include_sources,
				 renderer_version, export_input_hash, output_content_hash, failure_code,
				 created_by_user_id, created_at
				) values (?, ?, ?, ?, ?, ?, 'MARKDOWN', ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent(),
				id, devContext.devWorkspaceId, runId, variantId, revision.id, revision.revisionNumber,
				disposition.name, status, sentences.count { it.status.isUnresolved }, acknowledged,
				objectMapper.writeValueAsString(sentences.map { it.id }), objectMapper.writeValueAsString(warningKeys),
				includeSources, MARKDOWN_RENDERER_VERSION, inputHash, outputHash,
				if (status == "REJECTED") "EXPORT_CONFIRMATION_REQUIRED" else null,
				devContext.devUserId, Timestamp.from(clock.instant()),
			)
		}

	private fun ensureArtifactRevision(variantId: UUID): CurrentArtifactRevision {
		val existing = currentArtifactRevisionOrNull(variantId)
		if (existing != null) return existing
		val runId = jdbcTemplate.query(
			"select generation_run_id from content_variants where workspace_id = ? and id = ?",
			{ rs, _ -> rs.getObject(1, UUID::class.java) },
			devContext.devWorkspaceId, variantId,
		).firstOrNull() ?: notFound()
		val rows = jdbcTemplate.query(
			"""
			select s.id, r.id, r.revision_no, s.order_index, r.body, r.origin
			from content_variant_sentences s
			join content_variant_sentence_revisions r on r.workspace_id = s.workspace_id and r.sentence_id = s.id and r.is_current
			where s.workspace_id = ? and s.content_variant_id = ? order by s.order_index
			""".trimIndent(),
			{ rs, _ -> StatementRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6)) },
			devContext.devWorkspaceId, variantId,
		)
		val revisionId = variantId
		val now = clock.instant()
		jdbcTemplate.update(
			"insert into content_variant_revisions (id, workspace_id, generation_run_id, content_variant_id, revision_no, lexical_content, is_current, created_at) values (?, ?, ?, ?, 1, ?::jsonb, true, ?)",
			revisionId, devContext.devWorkspaceId, runId, variantId, lexicalContentFor(rows).toString(), Timestamp.from(now),
		)
		rows.forEach { row ->
			jdbcTemplate.update(
				"insert into content_variant_revision_sentences (id, workspace_id, content_variant_revision_id, generation_run_id, content_variant_id, sentence_id, sentence_revision_id, order_index) values (?, ?, ?, ?, ?, ?, ?, ?)",
				uuidGenerator.next(), devContext.devWorkspaceId, revisionId, runId, variantId, row.id, row.revisionId, row.orderIndex,
			)
		}
		return CurrentArtifactRevision(revisionId, runId, 1, lexicalContentFor(rows))
	}

	private fun currentArtifactRevision(variantId: UUID): CurrentArtifactRevision = currentArtifactRevisionOrNull(variantId) ?: ensureArtifactRevision(variantId)

	private fun currentArtifactRevisionForUpdate(variantId: UUID): CurrentArtifactRevision =
		currentArtifactRevisionOrNull(variantId, forUpdate = true) ?: ensureArtifactRevision(variantId)

	private fun currentArtifactRevisionOrNull(variantId: UUID, forUpdate: Boolean = false): CurrentArtifactRevision? = jdbcTemplate.query(
		"select id, generation_run_id, revision_no, lexical_content::text from content_variant_revisions where workspace_id = ? and content_variant_id = ? and is_current${if (forUpdate) " for update" else ""}",
		{ rs, _ -> CurrentArtifactRevision(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getInt(3), objectMapper.readTree(rs.getString(4))) },
		devContext.devWorkspaceId, variantId,
	).firstOrNull()

	private fun loadCurrentStatements(revisionId: UUID, variantId: UUID): List<StatementRow> {
		val artifactRevisionExists = jdbcTemplate.queryForObject(
			"select exists (select 1 from content_variant_revisions where workspace_id = ? and id = ? and content_variant_id = ?)",
			Boolean::class.java,
			devContext.devWorkspaceId, revisionId, variantId,
		) ?: false
		if (artifactRevisionExists) {
			return jdbcTemplate.query(
				"""
				select rs.sentence_id, rs.sentence_revision_id, r.revision_no, rs.order_index, r.body, r.origin
				from content_variant_revision_sentences rs
				join content_variant_sentence_revisions r on r.workspace_id = rs.workspace_id and r.id = rs.sentence_revision_id
				where rs.workspace_id = ? and rs.content_variant_revision_id = ? and rs.content_variant_id = ? order by rs.order_index
				""".trimIndent(),
				{ rs, _ -> StatementRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6)) },
				devContext.devWorkspaceId, revisionId, variantId,
			)
		}
		return jdbcTemplate.query(
			"""
			select s.id, r.id, r.revision_no, s.order_index, r.body, r.origin
			from content_variant_sentences s
			join content_variant_sentence_revisions r on r.workspace_id = s.workspace_id and r.sentence_id = s.id and r.is_current
			where s.workspace_id = ? and s.content_variant_id = ? order by s.order_index
			""".trimIndent(),
			{ rs, _ -> StatementRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6)) },
			devContext.devWorkspaceId, variantId,
		)
	}

	private fun lexicalContentFor(rows: List<StatementRow>): JsonNode {
		val root = objectMapper.createObjectNode()
		val rootNode = root.putObject("root")
		val children = rootNode.putArray("children")
		rows.sortedBy { it.orderIndex }.forEach { row ->
			val paragraph = children.addObject()
			val paragraphChildren = paragraph.putArray("children")
			paragraphChildren.addObject().apply {
				put("detail", 0)
				put("format", 0)
				put("mode", "normal")
				put("style", "")
				put("text", row.body)
				put("type", "text")
				put("version", 1)
			}
			paragraph.putNull("direction")
			paragraph.put("format", "")
			paragraph.put("indent", 0)
			paragraph.put("type", "paragraph")
			paragraph.put("version", 1)
		}
		rootNode.putNull("direction")
		rootNode.put("format", "")
		rootNode.put("indent", 0)
		rootNode.put("type", "root")
		rootNode.put("version", 1)
		return root
	}

	private fun staleArtifactRevision(variantId: UUID): ApiException =
		ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT_REVISION", "Artifact revision is stale", variantId)

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

	private fun warningKey(revisionId: UUID, sentence: ExportSentence): String = sha256(
		listOf(revisionId, sentence.id, sentence.revisionId, sentence.orderIndex, sentence.body).joinToString("|"),
	)

	private fun notFound(): Nothing = throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Content pack not found")

	private fun sha256(value: String): String = HexFormat.of().formatHex(
		MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
	)

	private companion object {
		const val MARKDOWN_RENDERER_VERSION = "markdown-v2"
		const val MAX_WARNING_EXCERPT = 240
		val WHITESPACE = Regex("\\s+")
	}
}

private data class CurrentArtifactRevision(
	val id: UUID,
	val generationRunId: UUID,
	val revisionNumber: Int,
	val lexicalContent: JsonNode,
)

private data class StatementRow(
	val id: UUID,
	val revisionId: UUID,
	val revisionNumber: Int,
	val orderIndex: Int,
	val body: String,
	val origin: String,
)

private data class NormalizedStatement(val id: UUID, val orderIndex: Int, val body: String)

private data class PublicCitation(
	val sentenceId: UUID,
	val evidenceId: UUID,
	val provider: String,
	val sourceLabel: String,
	val originalUrl: String,
) {
	val response: ContentCitationResponse
		get() = ContentCitationResponse(evidenceId, provider, sourceLabel, originalUrl)
}

private sealed interface ExportAttempt {
	data class Completed(val response: ContentExportResponse) : ExportAttempt
	data class ConfirmationRequired(
		val warnings: List<ExportWarningResponse>,
		val sentenceIds: List<UUID>,
		val revisionIds: List<UUID>,
	) : ExportAttempt
}

class ExportConfirmationRequiredException(
	val warnings: List<ExportWarningResponse>,
	val sentenceIds: List<UUID>,
	val revisionIds: List<UUID>,
) : IllegalStateException("Export requires explicit confirmation")
