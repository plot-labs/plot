package com.plot.api.generation

import com.plot.api.ai.provider.GenerationModelGateway
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.ai.provider.WriterModelRequest
import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.SentenceArtifact
import com.plot.api.generation.model.ValidatedSentenceReview
import java.util.UUID

enum class GenerationRunStatus {
	QUEUED, WRITING, REVIEWING, REWRITING, READY, NEEDS_YOUR_CALL, NEEDS_REVIEW, FAILED;

	companion object {
		val terminalOrPaused = setOf(READY, NEEDS_YOUR_CALL, NEEDS_REVIEW, FAILED)
	}
}

enum class WorkflowArtifactKind { WRITER_OUTPUT, REVIEWER_OUTPUT, REWRITER_OUTPUT, CONFLICT, CONFLICT_DECISION }

data class WorkflowArtifact(
	val kind: WorkflowArtifactKind,
	val sequence: Int,
	val sentences: List<SentenceArtifact> = emptyList(),
	val reviews: List<ValidatedSentenceReview> = emptyList(),
	val detail: String? = null,
)

data class ConflictIntervention(
	val id: UUID,
	val sentenceId: UUID,
	val version: Long,
	val reason: String,
	val evidenceIds: List<UUID>,
)

data class GenerationWorkflowState(
	val runId: UUID,
	val evidence: List<EvidenceSnapshot>,
	val instruction: String?,
	val status: GenerationRunStatus,
	val sentences: List<SentenceArtifact> = emptyList(),
	val reviews: List<ValidatedSentenceReview> = emptyList(),
	val artifacts: List<WorkflowArtifact> = emptyList(),
	val semanticRewriteAttempt: Int = 0,
	val rewriteTargetSentenceIds: List<UUID> = emptyList(),
	val resolutionInstruction: String? = null,
	val pendingIntervention: ConflictIntervention? = null,
	val failureCode: String? = null,
)

class GenerationWorkflowService(
	private val validator: ModelOutputValidator,
	private val idGenerator: () -> UUID,
	private val maxSemanticRewrites: Int = 3,
) {
	init {
		require(maxSemanticRewrites > 0)
	}

	fun start(runId: UUID, evidence: List<EvidenceSnapshot>, instruction: String?): GenerationWorkflowState {
		require(evidence.isNotEmpty()) { "Generation requires evidence" }
		require(evidence.all { it.generationRunId == runId }) { "Evidence belongs to another run" }
		return GenerationWorkflowState(runId, evidence.sortedBy { it.orderIndex }, instruction?.trim(), GenerationRunStatus.QUEUED)
	}

	/** Advances exactly one durable model-call checkpoint. External model calls are at-least-once across a crash window. */
	fun advance(state: GenerationWorkflowState, gateway: GenerationModelGateway): GenerationWorkflowState = when (state.status) {
		GenerationRunStatus.QUEUED, GenerationRunStatus.WRITING -> write(state, gateway)
		GenerationRunStatus.REVIEWING -> review(state, gateway)
		GenerationRunStatus.REWRITING -> rewrite(state, gateway)
		else -> state
	}

	fun fail(state: GenerationWorkflowState, code: String): GenerationWorkflowState = state.copy(
		status = if (state.reviews.isEmpty()) GenerationRunStatus.FAILED else GenerationRunStatus.NEEDS_REVIEW,
		failureCode = code,
	)

	private fun write(state: GenerationWorkflowState, gateway: GenerationModelGateway): GenerationWorkflowState {
		val output = gateway.write(WriterModelRequest(state.runId, state.instruction, state.evidence)).value
		val sentences = validator.assignSentenceIds(state.runId, output, state.evidence.map { it.id }.toSet(), idGenerator)
		return state.copy(
			status = GenerationRunStatus.REVIEWING,
			sentences = sentences,
			artifacts = state.artifacts + WorkflowArtifact(
				WorkflowArtifactKind.WRITER_OUTPUT,
				state.artifacts.size,
				sentences = sentences,
			),
		)
	}

	private fun review(state: GenerationWorkflowState, gateway: GenerationModelGateway): GenerationWorkflowState {
		val output = gateway.review(
			ReviewerModelRequest(state.runId, state.sentences, state.evidence, state.resolutionInstruction),
		).value
		val reviews = validator.validateReview(state.runId, state.sentences, state.evidence, output)
		val artifacts = state.artifacts + WorkflowArtifact(
			WorkflowArtifactKind.REVIEWER_OUTPUT,
			state.artifacts.size,
			sentences = state.sentences,
			reviews = reviews,
		)
		val conflicts = reviews.filter { it.verdict == ReviewVerdict.CONFLICT }
		val conflictSentenceIds = conflicts.map { it.sentenceId }.toSet()
		val reviewedSentences = state.sentences.filterNot { it.id in conflictSentenceIds }
		val reviewedReviews = reviews.filterNot { it.sentenceId in conflictSentenceIds }
		val reviewedArtifacts = if (conflicts.isEmpty()) {
			artifacts
		} else {
			artifacts + WorkflowArtifact(
				WorkflowArtifactKind.CONFLICT,
				artifacts.size,
				reviews = conflicts,
				detail = "Automatically omitted ${conflicts.size} conflicting sentence(s).",
			)
		}
		val targets = reviewedReviews.filter { it.verdict == ReviewVerdict.NEEDS_SUPPORT }.map { it.sentenceId }
		val status = when {
			reviewedSentences.isEmpty() -> GenerationRunStatus.NEEDS_REVIEW
			targets.isEmpty() -> GenerationRunStatus.READY
			state.semanticRewriteAttempt >= maxSemanticRewrites -> GenerationRunStatus.NEEDS_REVIEW
			else -> GenerationRunStatus.REWRITING
		}
		return state.copy(
			status = status,
			sentences = reviewedSentences,
			reviews = reviewedReviews,
			artifacts = reviewedArtifacts,
			rewriteTargetSentenceIds = targets,
			resolutionInstruction = if (status == GenerationRunStatus.REWRITING) state.resolutionInstruction else null,
			pendingIntervention = null,
			failureCode = if (reviewedSentences.isEmpty()) "NO_PUBLISHABLE_SENTENCES" else null,
		)
	}

	private fun rewrite(state: GenerationWorkflowState, gateway: GenerationModelGateway): GenerationWorkflowState {
		require(state.rewriteTargetSentenceIds.isNotEmpty()) { "Rewrite has no targets" }
		val output = gateway.rewrite(RewriteModelRequest(
			state.runId,
			state.sentences,
			state.rewriteTargetSentenceIds,
			state.evidence,
			state.resolutionInstruction,
		)).value
		val sentences = validator.applyTargetedRewrite(
			state.runId,
			state.sentences,
			state.rewriteTargetSentenceIds,
			output,
			idGenerator,
		)
		return state.copy(
			status = GenerationRunStatus.REVIEWING,
			sentences = sentences,
			semanticRewriteAttempt = state.semanticRewriteAttempt + 1,
			artifacts = state.artifacts + WorkflowArtifact(
				WorkflowArtifactKind.REWRITER_OUTPUT,
				state.artifacts.size,
				sentences = sentences,
			),
		)
	}

}
