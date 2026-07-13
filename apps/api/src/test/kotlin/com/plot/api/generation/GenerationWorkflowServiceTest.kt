package com.plot.api.generation

import com.plot.api.ai.provider.GenerationModelGateway
import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelCallResult
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.ai.provider.WriterModelRequest
import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.ReviewerOutput
import com.plot.api.generation.model.SentenceOrigin
import com.plot.api.generation.model.SentenceReview
import com.plot.api.generation.model.SourceProvider
import com.plot.api.generation.model.TargetedRewrite
import com.plot.api.generation.model.TargetedRewriteOutput
import com.plot.api.generation.model.WriterOutput
import com.plot.api.generation.model.WriterSentence
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GenerationWorkflowServiceTest {
	private val ids = ArrayDeque((1..200).map(::uuid))
	private val workflow = GenerationWorkflowService(ModelOutputValidator(), idGenerator = { ids.removeFirst() })
	private val runId = uuid(500)
	private val evidence = listOf(evidence(1), evidence(2))

	@Test
	fun `supported and not-required sentences create a ready terminal result`() {
		val gateway = ScriptedGateway(
			writes = ArrayDeque(listOf(WriterOutput(listOf(WriterSentence("Shipped search."), WriterSentence("Thanks for reading."))))),
			reviews = ArrayDeque(listOf { request -> ReviewerOutput(listOf(
				SentenceReview(request.sentences[0].id, ReviewVerdict.SUPPORTED, listOf(evidence[0].id)),
				SentenceReview(request.sentences[1].id, ReviewVerdict.NOT_REQUIRED),
			)) }),
		)

		val terminal = run(workflow.start(runId, evidence, null), gateway)

		assertEquals(GenerationRunStatus.READY, terminal.status)
		assertEquals(listOf(ReviewVerdict.SUPPORTED, ReviewVerdict.NOT_REQUIRED), terminal.reviews.map { it.verdict })
		assertEquals(1, gateway.writeCalls)
		assertEquals(1, gateway.reviewCalls)
	}

	@Test
	fun `targeted rewrite preserves non-target sentence identity text and artifacts`() {
		val gateway = ScriptedGateway(
			writes = ArrayDeque(listOf(WriterOutput(listOf(WriterSentence("Unsupported launch."), WriterSentence("Stable sentence."))))),
			reviews = ArrayDeque(listOf(
				{ request -> ReviewerOutput(listOf(
					SentenceReview(request.sentences[0].id, ReviewVerdict.NEEDS_SUPPORT, reason = "No launch evidence"),
					SentenceReview(request.sentences[1].id, ReviewVerdict.SUPPORTED, listOf(evidence[0].id)),
				)) },
				{ request -> ReviewerOutput(listOf(
					SentenceReview(request.sentences[0].id, ReviewVerdict.SUPPORTED, listOf(evidence[1].id)),
					SentenceReview(request.sentences[1].id, ReviewVerdict.SUPPORTED, listOf(evidence[0].id)),
				)) },
			)),
			rewrites = ArrayDeque(listOf { request ->
				TargetedRewriteOutput(listOf(TargetedRewrite(request.targetSentenceIds.single(), "Documented launch.")))
			}),
		)

		val terminal = run(workflow.start(runId, evidence, null), gateway)
		val writerArtifact = terminal.artifacts.first { it.kind == WorkflowArtifactKind.WRITER_OUTPUT }
		val rewriteArtifact = terminal.artifacts.first { it.kind == WorkflowArtifactKind.REWRITER_OUTPUT }

		assertEquals(GenerationRunStatus.READY, terminal.status)
		assertEquals(writerArtifact.sentences[1], rewriteArtifact.sentences[1])
		assertEquals(writerArtifact.sentences[0].id, rewriteArtifact.sentences[0].id)
		assertNotEquals(writerArtifact.sentences[0].revisionId, rewriteArtifact.sentences[0].revisionId)
		assertEquals("Documented launch.", rewriteArtifact.sentences[0].body)
		assertEquals(1, gateway.rewriteCalls)
	}

	@Test
	fun `three unsuccessful semantic rewrites preserve failed review as needs-review`() {
		val gateway = alwaysUnsupportedGateway()

		val terminal = run(workflow.start(runId, evidence, null), gateway)

		assertEquals(GenerationRunStatus.NEEDS_REVIEW, terminal.status)
		assertEquals(3, terminal.semanticRewriteAttempt)
		assertEquals("Still unsupported", terminal.reviews.single().reason)
		assertEquals(3, gateway.rewriteCalls)
		assertEquals(4, terminal.artifacts.count { it.kind == WorkflowArtifactKind.REVIEWER_OUTPUT })
	}

	@Test
	fun `conflict pauses once and prefer-source resumes only affected sentence`() {
		val gateway = conflictGateway()
		val paused = run(workflow.start(runId, evidence, null), gateway)

		assertEquals(GenerationRunStatus.NEEDS_YOUR_CALL, paused.status)
		assertEquals(1, paused.artifacts.count { it.kind == WorkflowArtifactKind.CONFLICT })
		val conflict = requireNotNull(paused.pendingIntervention)
		val resumed = workflow.resolve(
			paused,
			ConflictResolution(conflict.id, conflict.version, ConflictResolutionAction.PREFER_SOURCE, preferredEvidenceId = evidence[1].id),
		)
		val terminal = run(resumed, gateway)

		assertEquals(GenerationRunStatus.READY, terminal.status)
		assertEquals(listOf(conflict.sentenceId), gateway.rewriteRequests.single().targetSentenceIds)
		assertTrue(gateway.rewriteRequests.single().resolutionInstruction!!.contains(evidence[1].id.toString()))
		assertEquals(1, terminal.artifacts.count { it.kind == WorkflowArtifactKind.CONFLICT })
	}

	@Test
	fun `provided wording becomes user-modified partial without model rewrite`() {
		val gateway = conflictGateway()
		val paused = run(workflow.start(runId, evidence, null), gateway)
		val conflict = requireNotNull(paused.pendingIntervention)

		val terminal = workflow.resolve(
			paused,
			ConflictResolution(conflict.id, conflict.version, ConflictResolutionAction.PROVIDE_WORDING, providedWording = "Release timing remains undecided."),
		)

		assertEquals(GenerationRunStatus.NEEDS_REVIEW, terminal.status)
		assertEquals(SentenceOrigin.USER_MODIFIED, terminal.sentences.single().origin)
		assertEquals("Release timing remains undecided.", terminal.sentences.single().body)
		assertEquals(0, gateway.rewriteCalls)
	}

	@Test
	fun `omit-claim resolution targets the conflict and removes the claim`() {
		val gateway = conflictGateway(omitResult = "Release timing omitted.")
		val paused = run(workflow.start(runId, evidence, null), gateway)
		val conflict = requireNotNull(paused.pendingIntervention)

		val terminal = run(workflow.resolve(
			paused,
			ConflictResolution(conflict.id, conflict.version, ConflictResolutionAction.OMIT_CLAIM),
		), gateway)

		assertEquals(GenerationRunStatus.READY, terminal.status)
		assertEquals("Release timing omitted.", terminal.sentences.single().body)
		assertTrue(gateway.rewriteRequests.single().resolutionInstruction!!.contains("OMIT_CLAIM"))
	}

	@Test
	fun `failure without reviewed draft is failed while reviewed partial needs review`() {
		val initial = workflow.start(runId, evidence, null)
		assertEquals(GenerationRunStatus.FAILED, workflow.fail(initial, "MODEL_BUDGET_EXHAUSTED").status)

		val gateway = ScriptedGateway(
			writes = ArrayDeque(listOf(WriterOutput(listOf(WriterSentence("Unsupported."))))),
			reviews = ArrayDeque(listOf { request -> ReviewerOutput(listOf(
				SentenceReview(request.sentences.single().id, ReviewVerdict.NEEDS_SUPPORT, reason = "Missing evidence"),
			)) }),
		)
		val afterWrite = workflow.advance(initial, gateway)
		val reviewed = workflow.advance(afterWrite, gateway)
		val partial = workflow.fail(reviewed, "MODEL_BUDGET_EXHAUSTED")

		assertEquals(GenerationRunStatus.NEEDS_REVIEW, partial.status)
		assertEquals("Missing evidence", partial.reviews.single().reason)
	}

	private fun alwaysUnsupportedGateway() = ScriptedGateway(
		writes = ArrayDeque(listOf(WriterOutput(listOf(WriterSentence("Unsupported."))))),
		reviews = ArrayDeque((0..3).map { { request: ReviewerModelRequest ->
			ReviewerOutput(listOf(SentenceReview(request.sentences.single().id, ReviewVerdict.NEEDS_SUPPORT, reason = "Still unsupported")))
		} }),
		rewrites = ArrayDeque((0..2).map { index -> { request: RewriteModelRequest ->
			TargetedRewriteOutput(listOf(TargetedRewrite(request.targetSentenceIds.single(), "Attempt ${index + 1}.")))
		} }),
	)

	private fun conflictGateway(omitResult: String = "Grounded release timing.") = ScriptedGateway(
		writes = ArrayDeque(listOf(WriterOutput(listOf(WriterSentence("Release shipped."))))),
		reviews = ArrayDeque(listOf(
			{ request -> ReviewerOutput(listOf(SentenceReview(request.sentences.single().id, ReviewVerdict.CONFLICT, evidence.map { it.id }, "Sources disagree"))) },
			{ request -> ReviewerOutput(listOf(SentenceReview(request.sentences.single().id, ReviewVerdict.SUPPORTED, listOf(evidence[1].id)))) },
		)),
		rewrites = ArrayDeque(listOf { request ->
			TargetedRewriteOutput(listOf(TargetedRewrite(request.targetSentenceIds.single(), omitResult)))
		}),
	)

	private fun run(initial: GenerationWorkflowState, gateway: GenerationModelGateway): GenerationWorkflowState {
		var state = initial
		repeat(20) {
			if (state.status in GenerationRunStatus.terminalOrPaused) return state
			state = workflow.advance(state, gateway)
		}
		error("Workflow did not settle")
	}

	private fun evidence(index: Int) = EvidenceSnapshot(
		id = uuid(600 + index), generationRunId = runId, writingBlockId = uuid(700 + index), orderIndex = index - 1,
		sourceProvider = SourceProvider.GITHUB, sourceKind = "pull_request", sourceLabel = "PR $index",
		snapshotTitle = "PR $index", snapshotBody = if (index == 1) "Shipped." else "Release delayed.",
		snapshotExcerpt = "Evidence $index", originalUrl = "https://github.test/acme/repo/pull/$index",
		sourceCreatedAt = null, sourceUpdatedAt = null, contentHash = "hash-$index", capturedAt = Instant.EPOCH,
	)

	private fun uuid(value: Int): UUID = UUID(0, value.toLong())
}

private class ScriptedGateway(
	private val writes: ArrayDeque<WriterOutput> = ArrayDeque(),
	private val reviews: ArrayDeque<(ReviewerModelRequest) -> ReviewerOutput> = ArrayDeque(),
	private val rewrites: ArrayDeque<(RewriteModelRequest) -> TargetedRewriteOutput> = ArrayDeque(),
) : GenerationModelGateway {
	var writeCalls = 0
	var reviewCalls = 0
	var rewriteCalls = 0
	val rewriteRequests = mutableListOf<RewriteModelRequest>()

	override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> {
		writeCalls++
		return result(writes.removeFirst())
	}

	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> {
		reviewCalls++
		return result(reviews.removeFirst()(request))
	}

	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> {
		rewriteCalls++
		rewriteRequests += request
		return result(rewrites.removeFirst()(request))
	}

	private fun <T : Any> result(value: T) = ModelCallResult(
		value,
		ModelCallMetadata(null, "scripted", "stop", 1, 1, 2, Duration.ofMillis(1), emptyMap()),
	)
}
