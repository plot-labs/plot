package com.plot.api.artifact.workflow

import com.plot.api.ai.provider.ArtifactWorkflowModelGateway
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.ai.provider.WriterModelRequest
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ArtifactLayoutNode
import com.plot.api.artifact.workflow.model.ReviewVerdict
import com.plot.api.artifact.workflow.model.SentenceArtifact
import com.plot.api.artifact.workflow.model.ValidatedSentenceReview
import com.plot.api.content.ContentTypeRegistry
import com.plot.api.content.FrozenPromptVersionLookup
import java.util.UUID

enum class ArtifactWorkflowRunStatus {
	QUEUED, WRITING, REVIEWING, REWRITING, READY, NEEDS_REVIEW, FAILED;

	companion object {
		val terminalOrPaused = setOf(READY, NEEDS_REVIEW, FAILED)
	}
}

enum class WorkflowArtifactKind { WRITER_OUTPUT, REVIEWER_OUTPUT, REWRITER_OUTPUT, CONFLICT }

data class WorkflowArtifact(
	val kind: WorkflowArtifactKind,
	val sequence: Int,
	val sentences: List<SentenceArtifact> = emptyList(),
	val reviews: List<ValidatedSentenceReview> = emptyList(),
	val detail: String? = null,
)

data class ArtifactWorkflowState(
	val runId: UUID,
	val evidence: List<EvidenceSnapshot>,
	val instruction: String?,
	val status: ArtifactWorkflowRunStatus,
	val sentences: List<SentenceArtifact> = emptyList(),
	val reviews: List<ValidatedSentenceReview> = emptyList(),
	val artifacts: List<WorkflowArtifact> = emptyList(),
	val semanticRewriteAttempt: Int = 0,
	val rewriteTargetSentenceIds: List<UUID> = emptyList(),
	val failureCode: String? = null,
	val workSessionId: UUID? = null,
	val agentRunId: UUID? = null,
	val documentVersion: Int = 1,
	val layout: List<ArtifactLayoutNode> = emptyList(),
)

class ArtifactWorkflowService(
	private val validator: ModelOutputValidator,
	private val idGenerator: () -> UUID,
	private val maxSemanticRewrites: Int = 3,
	private val frozenPromptVersionLookup: FrozenPromptVersionLookup? = null,
) {
	init {
		require(maxSemanticRewrites > 0)
	}

	fun start(
		runId: UUID,
		evidence: List<EvidenceSnapshot>,
		instruction: String?,
		documentVersion: Int = 1,
	): ArtifactWorkflowState {
		require(evidence.isNotEmpty()) { "ArtifactWorkflow requires evidence" }
		require(evidence.all { it.artifactWorkflowRunId == runId }) { "Evidence belongs to another run" }
		require(documentVersion in SUPPORTED_DOCUMENT_VERSIONS) { "Unsupported artifact document version" }
		return ArtifactWorkflowState(
			runId,
			evidence.sortedBy { it.orderIndex },
			instruction?.trim(),
			ArtifactWorkflowRunStatus.QUEUED,
			documentVersion = documentVersion,
		)
	}

	/** Advances one durable model-call checkpoint; after a crash the external call outcome may be unknown. */
	fun advance(state: ArtifactWorkflowState, gateway: ArtifactWorkflowModelGateway): ArtifactWorkflowState = when (state.status) {
		ArtifactWorkflowRunStatus.QUEUED, ArtifactWorkflowRunStatus.WRITING -> write(state, gateway)
		ArtifactWorkflowRunStatus.REVIEWING -> review(state, gateway)
		ArtifactWorkflowRunStatus.REWRITING -> rewrite(state, gateway)
		else -> state
	}

	fun fail(state: ArtifactWorkflowState, code: String): ArtifactWorkflowState = state.copy(
		status = if (state.reviews.isEmpty()) ArtifactWorkflowRunStatus.FAILED else ArtifactWorkflowRunStatus.NEEDS_REVIEW,
		failureCode = code,
	)

	private fun write(state: ArtifactWorkflowState, gateway: ArtifactWorkflowModelGateway): ArtifactWorkflowState {
		val output = gateway.write(
			WriterModelRequest(
				artifactWorkflowRunId = state.runId,
				instruction = state.instruction,
				evidence = state.evidence,
				documentVersion = state.documentVersion,
			),
		).value
		val promptVersion = frozenPromptVersionLookup?.promptVersionFor(state.runId)
			?: ContentTypeRegistry.CHANGELOG_PROMPT_VERSION
		val sentences = validator.assignSentenceIds(
			runId = state.runId,
			output = output,
			availableEvidenceIds = state.evidence.map { it.id }.toSet(),
			maxSentences = ContentTypeRegistry.maxWriterSentences(promptVersion),
			idGenerator = idGenerator,
		)
		val layout = validator.assignLayout(output.layout, output.sentences, sentences)
		return state.copy(
			status = ArtifactWorkflowRunStatus.REVIEWING,
			sentences = sentences,
			layout = layout,
			artifacts = state.artifacts + WorkflowArtifact(
				WorkflowArtifactKind.WRITER_OUTPUT,
				state.artifacts.size,
				sentences = sentences,
			),
		)
	}

	private fun review(state: ArtifactWorkflowState, gateway: ArtifactWorkflowModelGateway): ArtifactWorkflowState {
		val output = gateway.review(
			ReviewerModelRequest(state.runId, state.sentences, state.evidence),
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
		val reviewedLayout = validator.retainLayout(state.layout, reviewedSentences.map { it.id }.toSet())
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
			reviewedSentences.isEmpty() -> ArtifactWorkflowRunStatus.NEEDS_REVIEW
			targets.isEmpty() -> ArtifactWorkflowRunStatus.READY
			state.semanticRewriteAttempt >= maxSemanticRewrites -> ArtifactWorkflowRunStatus.NEEDS_REVIEW
			else -> ArtifactWorkflowRunStatus.REWRITING
		}
		return state.copy(
			status = status,
			sentences = reviewedSentences,
			layout = reviewedLayout,
			reviews = reviewedReviews,
			artifacts = reviewedArtifacts,
			rewriteTargetSentenceIds = targets,
			failureCode = if (reviewedSentences.isEmpty()) "NO_PUBLISHABLE_SENTENCES" else null,
		)
	}

	private fun rewrite(state: ArtifactWorkflowState, gateway: ArtifactWorkflowModelGateway): ArtifactWorkflowState {
		require(state.rewriteTargetSentenceIds.isNotEmpty()) { "Rewrite has no targets" }
		val output = gateway.rewrite(RewriteModelRequest(
			state.runId,
			state.sentences,
			state.rewriteTargetSentenceIds,
			state.evidence,
		)).value
		val sentences = validator.applyTargetedRewrite(
			state.runId,
			state.sentences,
			state.rewriteTargetSentenceIds,
			output,
			idGenerator,
		)
		val layout = validator.retainLayout(state.layout, sentences.map { it.id }.toSet())
		return state.copy(
			status = ArtifactWorkflowRunStatus.REVIEWING,
			sentences = sentences,
			layout = layout,
			semanticRewriteAttempt = state.semanticRewriteAttempt + 1,
			artifacts = state.artifacts + WorkflowArtifact(
				WorkflowArtifactKind.REWRITER_OUTPUT,
				state.artifacts.size,
				sentences = sentences,
			),
		)
	}

	private companion object {
		val SUPPORTED_DOCUMENT_VERSIONS = setOf(1, 2)
	}

}
