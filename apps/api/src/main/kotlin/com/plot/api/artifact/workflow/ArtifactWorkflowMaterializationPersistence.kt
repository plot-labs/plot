package com.plot.api.artifact.workflow

import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.common.UuidGenerator
import com.plot.api.routine.AgentRunInputRecord
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ReviewVerdict
import com.plot.api.artifact.workflow.model.SentenceArtifact
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import tools.jackson.databind.ObjectMapper

class ArtifactWorkflowMaterializationPersistence(
	private val sqlExecutor: JooqSqlExecutor,
	private val objectMapper: ObjectMapper,
	private val uuidGenerator: UuidGenerator,
) {
	fun insertEvidence(workspaceId: UUID, evidence: EvidenceSnapshot) {
		sqlExecutor.update(
			"""
			insert into generation_inputs (id, workspace_id, generation_run_id, writing_block_id, order_index,
			 source_scope_id, agent_run_id, agent_run_input_id,
			 source_provider, source_kind, source_label, snapshot_title, snapshot_body, snapshot_excerpt,
			 original_url, source_created_at, source_updated_at, content_hash, captured_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			evidence.id, workspaceId, evidence.artifactWorkflowRunId, evidence.writingBlockId, evidence.orderIndex,
			evidence.sourceScopeId, evidence.agentRunId, evidence.agentRunInputId,
			evidence.sourceProvider.name, evidence.sourceKind, evidence.sourceLabel, evidence.snapshotTitle,
			evidence.snapshotBody, evidence.snapshotExcerpt, evidence.originalUrl,
			evidence.sourceCreatedAt?.let(Timestamp::from), evidence.sourceUpdatedAt?.let(Timestamp::from),
			evidence.contentHash, Timestamp.from(evidence.capturedAt),
		)
	}

	fun insertCheckpoint(workspaceId: UUID, state: ArtifactWorkflowState, type: String, now: Instant, stepId: UUID? = null) {
		val version = sqlExecutor.queryForObject(
			"select coalesce(max(artifact_version), 0) + 1 from generation_artifacts where workspace_id = ? and generation_run_id = ? and artifact_type = ?",
			Int::class.java, workspaceId, state.runId, type,
		) ?: 1
		val sequence = sqlExecutor.queryForObject(
			"select coalesce(max(sequence_no), -1) + 1 from generation_artifacts where workspace_id = ? and generation_run_id = ?",
			Int::class.java, workspaceId, state.runId,
		) ?: 0
		sqlExecutor.update(
			"insert into generation_artifacts (id, workspace_id, generation_run_id, workflow_step_id, artifact_type, artifact_version, sequence_no, payload, created_at) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
			uuidGenerator.next(), workspaceId, state.runId, stepId, type, version, sequence,
			objectMapper.writeValueAsString(state), Timestamp.from(now),
		)
	}

	fun materializeTerminal(workspaceId: UUID, state: ArtifactWorkflowState, now: Instant, userModifiedBy: UUID? = null) {
		if (sqlExecutor.queryForObject("select count(*) from content_packs where workspace_id = ? and generation_run_id = ?", Int::class.java, workspaceId, state.runId)!! > 0) return
		val packId = uuidGenerator.next()
		val variantId = uuidGenerator.next()
		val status = if (state.status == ArtifactWorkflowRunStatus.READY) "READY" else "NEEDS_REVIEW"
		val releaseRequestId = sqlExecutor.query(
			"""
			select request_id
			from github_release_generation_attempts
			where workspace_id = ? and generation_run_id = ?
			""".trimIndent(),
			{ rs, _ -> rs.getObject("request_id", UUID::class.java) },
			workspaceId,
			state.runId,
		).firstOrNull()
		sqlExecutor.update(
			"""
			insert into content_packs (
			 id, workspace_id, generation_run_id, release_request_id, title, status, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			packId, workspaceId, state.runId, releaseRequestId,
			state.sentences.firstOrNull()?.body?.take(120), status, Timestamp.from(now), Timestamp.from(now),
		)
		sqlExecutor.update(
			"insert into content_variants (id, workspace_id, generation_run_id, content_pack_id, variant_index, status, created_at, updated_at) values (?, ?, ?, ?, 0, ?, ?, ?)",
			variantId, workspaceId, state.runId, packId, status, Timestamp.from(now), Timestamp.from(now),
		)
		val revisions = state.artifacts.flatMap { it.sentences }.plus(state.sentences)
			.distinctBy { it.revisionId }.groupBy { it.id }
		state.sentences.sortedBy { it.orderIndex }.forEach { current ->
			sqlExecutor.update(
				"insert into content_variant_sentences (id, workspace_id, generation_run_id, content_variant_id, stable_key, order_index, created_at) values (?, ?, ?, ?, ?, ?, ?)",
				current.id, workspaceId, state.runId, variantId, current.id.toString(), current.orderIndex, Timestamp.from(now),
			)
			revisions.getValue(current.id).sortedBy { it.revisionNumber }.forEach { revision ->
				sqlExecutor.update(
					"""
					insert into content_variant_sentence_revisions (id, workspace_id, generation_run_id, content_variant_id,
					 sentence_id, revision_no, origin, body, is_current, created_by_user_id, created_at)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""".trimIndent(),
					revision.revisionId, workspaceId, state.runId, variantId, current.id, revision.revisionNumber,
					revision.origin.name, revision.body, revision.revisionId == current.revisionId,
					userModifiedBy.takeIf { revision.origin.name == "USER_MODIFIED" }, Timestamp.from(now),
				)
			}
		}
		val reviewArtifacts = state.artifacts.filter { it.kind == WorkflowArtifactKind.REVIEWER_OUTPUT }
		val materializedSentenceIds = state.sentences.mapTo(mutableSetOf()) { it.id }
		reviewArtifacts.forEachIndexed { reviewIndex, artifact ->
			artifact.reviews.filter { it.sentenceId in materializedSentenceIds }.forEach { review ->
				val sentence = artifact.sentences.single { it.id == review.sentenceId }
				sqlExecutor.update(
					"insert into sentence_evaluations (id, workspace_id, generation_run_id, sentence_id, sentence_revision_id, review_attempt, verdict, reason, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
					uuidGenerator.next(), workspaceId, state.runId, sentence.id, sentence.revisionId, reviewIndex + 1,
					review.verdict.name, review.reason, Timestamp.from(now),
				)
			}
		}
		state.reviews.filter { it.verdict == ReviewVerdict.SUPPORTED }.forEach { review ->
			val sentence = state.sentences.single { it.id == review.sentenceId }
			review.evidenceIds.forEachIndexed { citationIndex, evidenceId ->
				sqlExecutor.update(
					"insert into sentence_citations (id, workspace_id, generation_run_id, content_variant_id, sentence_id, sentence_revision_id, generation_input_id, citation_order, status, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
					uuidGenerator.next(), workspaceId, state.runId, variantId, sentence.id, sentence.revisionId,
					evidenceId, citationIndex, Timestamp.from(now),
				)
			}
		}
		val artifactRevisionId = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into content_variant_revisions (
			 id, workspace_id, generation_run_id, content_variant_id, revision_no,
			 lexical_content, is_current, created_at
			) values (?, ?, ?, ?, 1, ?::jsonb, true, ?)
			""".trimIndent(),
			artifactRevisionId, workspaceId, state.runId, variantId,
			lexicalContentFor(state.sentences).toString(), Timestamp.from(now),
		)
		state.sentences.sortedBy { it.orderIndex }.forEach { sentence ->
			sqlExecutor.update(
				"""
				insert into content_variant_revision_sentences (
				 id, workspace_id, content_variant_revision_id, generation_run_id,
				 content_variant_id, sentence_id, sentence_revision_id, order_index
				) values (?, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent(),
				uuidGenerator.next(), workspaceId, artifactRevisionId, state.runId, variantId,
				sentence.id, sentence.revisionId, sentence.orderIndex,
			)
		}
	}

	private fun lexicalContentFor(sentences: List<SentenceArtifact>): tools.jackson.databind.JsonNode {
		val document = objectMapper.createObjectNode()
		val root = document.putObject("root")
		val children = root.putArray("children")
		sentences.sortedBy { it.orderIndex }.forEach { sentence ->
			val paragraph = children.addObject()
			val paragraphChildren = paragraph.putArray("children")
			paragraphChildren.addObject().apply {
				put("detail", 0)
				put("format", 0)
				put("mode", "normal")
				put("style", "")
				put("text", sentence.body)
				put("type", "text")
				put("version", 1)
			}
			paragraph.putNull("direction")
			paragraph.put("format", "")
			paragraph.put("indent", 0)
			paragraph.put("type", "paragraph")
			paragraph.put("version", 1)
		}
		root.putNull("direction")
		root.put("format", "")
		root.put("indent", 0)
		root.put("type", "root")
		root.put("version", 1)
		return document
}

}
