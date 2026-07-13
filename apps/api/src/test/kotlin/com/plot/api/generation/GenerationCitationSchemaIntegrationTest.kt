package com.plot.api.generation

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class GenerationCitationSchemaIntegrationTest {
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext

	@Test
	fun persistsCompleteGenerationCitationChain() {
		val blockId = insertWritingBlock("happy-chain")
		val runId = insertRun("happy-chain")
		val inputId = insertInput(runId, blockId, 0)
		val stepId = insertStep(runId, "WRITER", 0)
		insertInvocation(runId, stepId, "WRITER", 0)
		val artifactId = insertArtifact(runId, stepId, "WRITER_OUTPUT", 1)
		val packId = insertPack(runId)
		val variantId = insertVariant(runId, packId)
		val sentenceId = insertSentence(runId, variantId, 0)
		val revisionId = insertRevision(runId, variantId, sentenceId, 1, "GENERATED")
		insertEvaluation(runId, sentenceId, revisionId, 1, "SUPPORTED")
		insertCitation(runId, variantId, sentenceId, revisionId, inputId)

		assertEquals(1, jdbcTemplate.queryForObject("select count(*) from sentence_citations where generation_run_id = ?", Int::class.java, runId))
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update("update generation_artifacts set payload = '{}'::jsonb where id = ?", artifactId)
		}
	}

	@Test
	fun rejectsCrossRunAndCrossWorkspaceCitationLinks() {
		val blockId = insertWritingBlock("citation-boundary")
		val firstRun = insertRun("citation-boundary-1")
		val secondRun = insertRun("citation-boundary-2")
		val firstInput = insertInput(firstRun, blockId, 0)
		val secondPack = insertPack(secondRun)
		val secondVariant = insertVariant(secondRun, secondPack)
		val secondSentence = insertSentence(secondRun, secondVariant, 0)
		val secondRevision = insertRevision(secondRun, secondVariant, secondSentence, 1, "GENERATED")

		assertFailsWith<DataIntegrityViolationException> {
			insertCitation(secondRun, secondVariant, secondSentence, secondRevision, firstInput)
		}

		val foreignWorkspace = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into workspaces (id, name, slug, status, created_at, updated_at) values (?, 'Other', ?, 'ACTIVE', now(), now())",
			foreignWorkspace,
			"other-${UUID.randomUUID()}",
		)
		try {
			assertFailsWith<DataIntegrityViolationException> {
				insertInput(firstRun, blockId, 1, workspaceId = foreignWorkspace)
			}
		} finally {
			jdbcTemplate.update("delete from workspaces where id = ?", foreignWorkspace)
		}
	}

	@Test
	fun enforcesIdempotencySentenceOrderAndSinglePendingIntervention() {
		val runId = insertRun("unique-contract")
		assertFailsWith<DuplicateKeyException> { insertRun("unique-contract") }
		val packId = insertPack(runId)
		val variantId = insertVariant(runId, packId)
		val sentenceId = insertSentence(runId, variantId, 0)
		assertFailsWith<DuplicateKeyException> { insertSentence(runId, variantId, 0) }

		insertIntervention(runId, sentenceId, "PENDING")
		assertFailsWith<DuplicateKeyException> { insertIntervention(runId, sentenceId, "PENDING") }
		insertIntervention(runId, sentenceId, "RESOLVED", "OMIT_CLAIM")
	}

	@Test
	fun permitsRevisionAndReviewHistoryButKeepsHistoryImmutable() {
		val runId = insertRun("immutable-history")
		val packId = insertPack(runId)
		val variantId = insertVariant(runId, packId)
		val sentenceId = insertSentence(runId, variantId, 0)
		val firstRevision = insertRevision(runId, variantId, sentenceId, 1, "GENERATED", current = false)
		val secondRevision = insertRevision(runId, variantId, sentenceId, 2, "REWRITTEN")
		insertEvaluation(runId, sentenceId, firstRevision, 1, "NEEDS_SUPPORT")
		insertEvaluation(runId, sentenceId, secondRevision, 2, "SUPPORTED")
		val otherSentenceId = insertSentence(runId, variantId, 1)
		val otherRevisionId = insertRevision(runId, variantId, otherSentenceId, 1, "GENERATED")

		assertEquals(2, jdbcTemplate.queryForObject("select count(*) from content_variant_sentence_revisions where sentence_id = ?", Int::class.java, sentenceId))
		assertEquals(2, jdbcTemplate.queryForObject("select count(*) from sentence_evaluations where sentence_id = ?", Int::class.java, sentenceId))
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update("update content_variant_sentence_revisions set body = 'identity drift' where id = ?", firstRevision)
		}
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update("delete from sentence_evaluations where sentence_revision_id = ?", firstRevision)
		}
		assertFailsWith<DuplicateKeyException> {
			insertRevision(runId, variantId, sentenceId, 3, "REWRITTEN")
		}
		assertFailsWith<DataIntegrityViolationException> {
			insertRevision(runId, variantId, sentenceId, 3, "USER_MODIFIED", current = false)
		}
		assertFailsWith<DataIntegrityViolationException> {
			insertEvaluation(runId, sentenceId, otherRevisionId, 3, "SUPPORTED")
		}
		insertRevision(runId, variantId, sentenceId, 3, "USER_MODIFIED", current = false, createdByUserId = devContext.devUserId)
	}

	@Test
	fun recordsVersionedConflictDecisionsAndExplicitExportAcknowledgments() {
		val blockId = insertWritingBlock("decision-audit")
		val runId = insertRun("decision-audit")
		val inputId = insertInput(runId, blockId, 0)
		val packId = insertPack(runId)
		val variantId = insertVariant(runId, packId)
		val sentenceId = insertSentence(runId, variantId, 0)
		val interventionId = insertIntervention(runId, sentenceId, "PENDING")
		val resolutionId = insertResolution(runId, interventionId, 1, "PREFER_SOURCE", preferredInputId = inputId)

		assertFailsWith<DuplicateKeyException> {
			insertResolution(runId, interventionId, 1, "PREFER_SOURCE", preferredInputId = inputId)
		}
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update("update generation_intervention_resolutions set action = 'OMIT_CLAIM' where id = ?", resolutionId)
		}
		assertFailsWith<DataIntegrityViolationException> {
			insertExport(runId, variantId, "SUCCEEDED", unresolvedCount = 1, acknowledged = false)
		}
		val exportId = insertExport(runId, variantId, "SUCCEEDED", unresolvedCount = 1, acknowledged = true)
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update("delete from generation_export_events where id = ?", exportId)
		}
	}

	@Test
	fun enforcesWorkflowAndArtifactStateVocabularies() {
		assertFailsWith<DataIntegrityViolationException> { insertRun("bad-run-state", status = "UNKNOWN") }
		val runId = insertRun("state-vocabularies")
		assertFailsWith<DataIntegrityViolationException> { insertStep(runId, "WRITER", 0, status = "UNKNOWN") }
		val packId = insertPack(runId)
		val variantId = insertVariant(runId, packId)
		val sentenceId = insertSentence(runId, variantId, 0)
		assertFailsWith<DataIntegrityViolationException> { insertRevision(runId, variantId, sentenceId, 1, "UNKNOWN") }
		val revisionId = insertRevision(runId, variantId, sentenceId, 1, "GENERATED")
		assertFailsWith<DataIntegrityViolationException> { insertEvaluation(runId, sentenceId, revisionId, 1, "UNKNOWN") }
		assertFailsWith<DataIntegrityViolationException> { insertIntervention(runId, sentenceId, "UNKNOWN") }
		assertFailsWith<DataIntegrityViolationException> { insertPack(insertRun("bad-pack"), status = "UNKNOWN") }
		assertFailsWith<DataIntegrityViolationException> { insertVariant(runId, packId, status = "UNKNOWN", variantIndex = 1) }
		assertFailsWith<DataIntegrityViolationException> { insertExport(runId, variantId, status = "UNKNOWN") }
	}

	@Test
	fun evidenceSnapshotSurvivesWritingBlockArchivalAndIsAppendOnly() {
		val blockId = insertWritingBlock("archive-lineage")
		val runId = insertRun("archive-lineage")
		val inputId = insertInput(runId, blockId, 0)
		jdbcTemplate.update("update writing_blocks set status = 'ARCHIVED', body = 'changed after capture', updated_at = now() where id = ?", blockId)

		assertEquals("captured evidence", jdbcTemplate.queryForObject("select snapshot_body from generation_inputs where id = ?", String::class.java, inputId))
		assertEquals(blockId, jdbcTemplate.queryForObject("select writing_block_id from generation_inputs where id = ?", UUID::class.java, inputId))
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update("update generation_inputs set snapshot_body = 'mutated' where id = ?", inputId)
		}
	}

	private fun insertWritingBlock(key: String): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into writing_blocks (id, workspace_id, source_origin, source_kind, title, body, url,
			 content_hash, source_created_at, source_updated_at, ingested_at, status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'github', 'pull_request', ?, 'captured evidence', ?, ?, now(), now(), now(), 'ACTIVE', ?, now(), now())
		""".trimIndent(), id, devContext.devWorkspaceId, "PR $key", "https://github.test/acme/repo/pull/$key", "hash-$key", devContext.devUserId)
	}

	private fun insertRun(key: String, status: String = "QUEUED"): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into generation_runs (id, workspace_id, source_scope_id, created_by_user_id, idempotency_key,
			 request_fingerprint, status, workflow_version, prompt_version, output_schema_version, budget_version, provider,
			 model_name, budget_snapshot, created_at, updated_at)
			values (?, ?, null, ?, ?, ?, ?, 'fixed-v1', 'changelog-v1', 'generation-v1', 'budget-v1', 'OPENAI',
			 'configured-model', '{"maxModelCalls":12}'::jsonb, now(), now())
		""".trimIndent(), id, devContext.devWorkspaceId, devContext.devUserId, key, "fingerprint-$key", status)
	}

	private fun insertInput(runId: UUID, blockId: UUID, orderIndex: Int, workspaceId: UUID = devContext.devWorkspaceId): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into generation_inputs (id, workspace_id, generation_run_id, writing_block_id, order_index,
			 source_provider, source_kind, source_label, snapshot_title, snapshot_body, original_url,
			 source_created_at, source_updated_at, content_hash, captured_at)
			values (?, ?, ?, ?, ?, 'GITHUB', 'PULL_REQUEST', 'acme/repo#1', 'PR title', 'captured evidence',
			 'https://github.test/acme/repo/pull/1', now(), now(), 'snapshot-hash', now())
		""".trimIndent(), id, workspaceId, runId, blockId, orderIndex)
	}

	private fun insertStep(runId: UUID, kind: String, sequence: Int, status: String = "SUCCEEDED"): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into generation_workflow_steps (id, workspace_id, generation_run_id, step_kind, sequence_no,
			 semantic_attempt, status, started_at, finished_at, created_at)
			values (?, ?, ?, ?, ?, 0, ?, now(), now(), now())
		""".trimIndent(), id, devContext.devWorkspaceId, runId, kind, sequence, status)
	}

	private fun insertInvocation(runId: UUID, stepId: UUID, role: String, logicalIndex: Int): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into model_invocations (id, workspace_id, generation_run_id, workflow_step_id, role,
			 logical_call_index, status, provider, model_name, request_metadata, result_metadata,
			 prompt_token_count, completion_token_count, total_token_count, latency_ms, started_at, finished_at, created_at)
			values (?, ?, ?, ?, ?, ?, 'SUCCEEDED', 'OPENAI', 'configured-model', '{}'::jsonb, '{}'::jsonb,
			 10, 20, 30, 100, now(), now(), now())
		""".trimIndent(), id, devContext.devWorkspaceId, runId, stepId, role, logicalIndex)
	}

	private fun insertArtifact(runId: UUID, stepId: UUID, type: String, version: Int): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into generation_artifacts (id, workspace_id, generation_run_id, workflow_step_id,
			 artifact_type, artifact_version, payload, created_at)
			values (?, ?, ?, ?, ?, ?, '{"sentences":[]}'::jsonb, now())
		""".trimIndent(), id, devContext.devWorkspaceId, runId, stepId, type, version)
	}

	private fun insertPack(runId: UUID, status: String = "DRAFT"): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("insert into content_packs (id, workspace_id, generation_run_id, status, created_at, updated_at) values (?, ?, ?, ?, now(), now())", id, devContext.devWorkspaceId, runId, status)
	}

	private fun insertVariant(runId: UUID, packId: UUID, status: String = "DRAFT", variantIndex: Int = 0): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into content_variants (id, workspace_id, generation_run_id, content_pack_id, variant_index,
			 status, created_at, updated_at) values (?, ?, ?, ?, ?, ?, now(), now())
		""".trimIndent(), id, devContext.devWorkspaceId, runId, packId, variantIndex, status)
	}

	private fun insertSentence(runId: UUID, variantId: UUID, orderIndex: Int): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into content_variant_sentences (id, workspace_id, generation_run_id, content_variant_id,
			 stable_key, order_index, created_at) values (?, ?, ?, ?, ?, ?, now())
		""".trimIndent(), id, devContext.devWorkspaceId, runId, variantId, "sentence-$id", orderIndex)
	}

	private fun insertRevision(
		runId: UUID,
		variantId: UUID,
		sentenceId: UUID,
		revision: Int,
		origin: String,
		current: Boolean = true,
		createdByUserId: UUID? = null,
	): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into content_variant_sentence_revisions (id, workspace_id, generation_run_id, content_variant_id,
			 sentence_id, revision_no, origin, body, is_current, created_by_user_id, created_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
		""".trimIndent(), id, devContext.devWorkspaceId, runId, variantId, sentenceId, revision, origin, "sentence revision $revision", current, createdByUserId)
	}

	private fun insertEvaluation(runId: UUID, sentenceId: UUID, revisionId: UUID, attempt: Int, verdict: String): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into sentence_evaluations (id, workspace_id, generation_run_id, sentence_id,
			 sentence_revision_id, review_attempt, verdict, reason, created_at)
			values (?, ?, ?, ?, ?, ?, ?, 'review reason', now())
		""".trimIndent(), id, devContext.devWorkspaceId, runId, sentenceId, revisionId, attempt, verdict)
	}

	private fun insertCitation(runId: UUID, variantId: UUID, sentenceId: UUID, revisionId: UUID, inputId: UUID): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into sentence_citations (id, workspace_id, generation_run_id, content_variant_id,
			 sentence_id, sentence_revision_id, generation_input_id, citation_order, status, created_at)
			values (?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE', now())
		""".trimIndent(), id, devContext.devWorkspaceId, runId, variantId, sentenceId, revisionId, inputId)
	}

	private fun insertIntervention(runId: UUID, sentenceId: UUID, status: String, resolutionAction: String? = null): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into generation_interventions (id, workspace_id, generation_run_id, sentence_id, kind,
			 status, version, resolution_action, resolved_at, created_at, updated_at)
			values (?, ?, ?, ?, 'SOURCE_CONFLICT', ?, 1, ?, case when ? = 'PENDING' then null else now() end, now(), now())
		""".trimIndent(), id, devContext.devWorkspaceId, runId, sentenceId, status, resolutionAction, status)
	}

	private fun insertResolution(
		runId: UUID,
		interventionId: UUID,
		version: Int,
		action: String,
		preferredInputId: UUID? = null,
		providedWording: String? = null,
	): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into generation_intervention_resolutions (id, workspace_id, generation_run_id,
			 intervention_id, version, action, preferred_generation_input_id, provided_wording,
			 decided_by_user_id, created_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
		""".trimIndent(), id, devContext.devWorkspaceId, runId, interventionId, version, action,
			preferredInputId, providedWording, devContext.devUserId)
	}

	private fun insertExport(
		runId: UUID,
		variantId: UUID,
		status: String,
		unresolvedCount: Int = 0,
		acknowledged: Boolean = false,
	): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into generation_export_events (id, workspace_id, generation_run_id, content_variant_id,
			 format, status, unresolved_count, warning_acknowledged, created_by_user_id, created_at)
			values (?, ?, ?, ?, 'MARKDOWN', ?, ?, ?, ?, now())
		""".trimIndent(), id, devContext.devWorkspaceId, runId, variantId, status, unresolvedCount, acknowledged, devContext.devUserId)
	}
}
