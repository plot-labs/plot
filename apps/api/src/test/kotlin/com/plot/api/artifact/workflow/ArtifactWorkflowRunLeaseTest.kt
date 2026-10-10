package com.plot.api.artifact.workflow

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ArtifactWorkflowRunLeaseTest {
	private val claim = ClaimedArtifactWorkflowRun(
		workspaceId = UUID.randomUUID(),
		runId = UUID.randomUUID(),
		transitionVersion = 7,
		workerId = "worker-a",
	)
	private val clock = Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC)

	@Test
	fun falseHeartbeatImmediatelyRevokesCommitAuthority() {
		val lease = ArtifactWorkflowRunLease(claim, renewClaim = { _, _ -> false }, clock)

		lease.renew()

		assertFailsWith<ArtifactWorkflowRunLeaseLostException> { lease.checkpoint() }
		assertFailsWith<ArtifactWorkflowRunLeaseLostException> { lease.commit {} }
	}

	@Test
	fun heartbeatExceptionImmediatelyRevokesCommitAuthority() {
		val lease = ArtifactWorkflowRunLease(claim, renewClaim = { _, _ -> error("database unavailable") }, clock)

		lease.renew()

		assertFailsWith<ArtifactWorkflowRunLeaseLostException> { lease.checkpoint() }
	}

	@Test
	fun heartbeatCannotInterleaveWithAFencedCommit() {
		val commitEntered = CountDownLatch(1)
		val releaseCommit = CountDownLatch(1)
		val heartbeatEntered = CountDownLatch(1)
		val lease = ArtifactWorkflowRunLease(
			claim,
			renewClaim = { _, _ ->
				heartbeatEntered.countDown()
				false
			},
			clock,
		)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val commit = executor.submit {
				lease.commit {
					commitEntered.countDown()
					releaseCommit.await()
				}
			}
			assertTrue(commitEntered.await(1, TimeUnit.SECONDS))
			val heartbeat = executor.submit { lease.renew() }

			assertFalse(heartbeatEntered.await(100, TimeUnit.MILLISECONDS))
			releaseCommit.countDown()
			commit.get(1, TimeUnit.SECONDS)
			assertTrue(heartbeatEntered.await(1, TimeUnit.SECONDS))
			heartbeat.get(1, TimeUnit.SECONDS)
			assertFailsWith<ArtifactWorkflowRunLeaseLostException> { lease.checkpoint() }
		} finally {
			releaseCommit.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun successfulHeartbeatKeepsTheClaimAndUsesItsFullIdentity() {
		var renewedClaim: ClaimedArtifactWorkflowRun? = null
		var renewedAt: Instant? = null
		val lease = ArtifactWorkflowRunLease(
			claim,
			renewClaim = { actualClaim, actualNow ->
				renewedClaim = actualClaim
				renewedAt = actualNow
				true
			},
			clock,
		)

		lease.renew()
		var committed = false
		lease.commit { committed = true }

		assertEquals(claim, renewedClaim)
		assertEquals(clock.instant(), renewedAt)
		assertTrue(committed)
	}

	@Test
	fun scheduledFactoryRenewsUntilTheHandleCloses() {
		val renewed = CountDownLatch(1)
		val renewals = AtomicInteger()
		val executor = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
		try {
			val factory = ScheduledArtifactWorkflowRunLeaseFactory(
				executor = executor,
				heartbeatInterval = java.time.Duration.ofMillis(10),
				clock = clock,
				renewClaim = { actualClaim, _ ->
					assertEquals(claim, actualClaim)
					renewals.incrementAndGet()
					renewed.countDown()
					true
				},
			)

			val handle = factory.open(claim)

			assertTrue(renewed.await(1, TimeUnit.SECONDS))
			handle.close()

			// A heartbeat that was mid-run when the handle closed can be re-queued once after the
			// cancel has already swept the queue. It is cancelled, so it drains at its next tick
			// without renewing. Wait for that instead of asserting the queue is empty immediately.
			assertTrue(awaitUntil { executor.queue.isEmpty() }, "cancelled heartbeat did not drain")
			Thread.sleep(SETTLE_MILLIS)
			val settled = renewals.get()
			Thread.sleep(SETTLE_MILLIS)
			assertEquals(settled, renewals.get(), "heartbeat kept renewing after the handle closed")
			assertTrue(executor.queue.isEmpty())
		} finally {
			executor.shutdownNow()
		}
	}

	private fun awaitUntil(condition: () -> Boolean): Boolean {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
		while (System.nanoTime() < deadline) {
			if (condition()) return true
			Thread.sleep(1)
		}
		return condition()
	}

	private companion object {
		// Several heartbeat intervals, so a heartbeat that was still scheduled would have fired.
		const val SETTLE_MILLIS = 40L
	}
}
