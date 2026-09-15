package com.plot.api.artifact.workflow

import com.plot.api.artifact.workflow.model.ArtifactLayoutNode
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ReviewVerdict
import com.plot.api.artifact.workflow.model.SentenceArtifact
import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.JooqSqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

@Repository
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
				lexicalContentFor(state).toString(), Timestamp.from(now),
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

	private fun lexicalContentFor(state: ArtifactWorkflowState): tools.jackson.databind.JsonNode {
		if (state.documentVersion == 2) return lexicalContentV2(state)
		return lexicalContentV1(state.sentences)
	}

	private fun lexicalContentV2(state: ArtifactWorkflowState): tools.jackson.databind.JsonNode {
		val document = objectMapper.createObjectNode()
		document.put("documentVersion", 2)
		val root = document.putObject("root")
		val children = root.putArray("children")
		val sentences = state.sentences.associateBy { it.id }
		val layout = state.layout.ifEmpty {
			state.sentences.sortedBy { it.orderIndex }.map { sentence ->
				ArtifactLayoutNode(type = "paragraph", statementId = sentence.id)
			}
		}
		layout.forEachIndexed { index, node -> children.add(layoutNode(node, "layout.$index", sentences, state.runId)) }
		root.putNull("direction")
		root.put("format", "")
		root.put("indent", 0)
		root.put("type", "root")
		root.put("version", 1)
		return document
	}

	private fun layoutNode(
		node: ArtifactLayoutNode,
		path: String,
		sentences: Map<UUID, SentenceArtifact>,
		runId: UUID,
	): tools.jackson.databind.node.ObjectNode {
		val result = objectMapper.createObjectNode()
		when (node.type) {
			"list" -> {
				result.put("nodeId", deterministicNodeId(runId, path))
				result.put("listType", node.listType ?: "bullet")
				result.put("start", node.start ?: 1)
				result.put("type", "list")
				result.put("version", 1)
				val children = result.putArray("children")
				node.children.forEachIndexed { index, child ->
					children.add(layoutNode(child, "$path.$index", sentences, runId))
				}
			}
			"heading", "paragraph", "listItem" -> {
				val statement = node.statementId?.let(sentences::get)
					?: throw IllegalStateException("$path references an unknown statement")
				// A sentence revision is immutable and already unique within the
				// materialized document, so it is a stable leaf identity across
				// duplicate materialization attempts.
				result.put("nodeId", statement.revisionId.toString())
				result.put("statementId", statement.id.toString())
				result.putNull("direction")
				result.put("format", "")
				result.put("indent", 0)
				result.put("type", node.type)
				result.put("version", 1)
				if (node.type == "heading") result.put("tag", node.tag ?: "h2")
				val paragraphChildren = result.putArray("children")
				paragraphChildren.addObject().apply {
					put("detail", 0)
					put("format", 0)
					put("mode", "normal")
					put("style", "")
					put("text", statement.body)
					put("type", "text")
					put("version", 1)
				}
			}
			else -> throw IllegalStateException("$path has unsupported layout type '${node.type}'")
		}
		return result
	}

	private fun deterministicNodeId(runId: UUID, path: String): String =
		UUID.nameUUIDFromBytes("$runId:$path".toByteArray(Charsets.UTF_8)).toString()

	private fun lexicalContentV1(sentences: List<SentenceArtifact>): tools.jackson.databind.JsonNode {
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
