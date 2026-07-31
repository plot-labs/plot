package com.plot.api.generation

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class GenerationRunLeaseTest {
	private val claim = ClaimedGenerationRun(
		workspaceId = UUID.randomUUID(),
		runId = UUID.randomUUID(),
		transitionVersion = 7,
		workerId = "worker-a",
	)
	private val clock = Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC)

	@Test
	fun falseHeartbeatImmediatelyRevokesCommitAuthority() {
		val lease = GenerationRunLease(claim, renewClaim = { _, _ -> false }, clock)

		lease.renew()

		assertFailsWith<GenerationRunLeaseLostException> { lease.checkpoint() }
		assertFailsWith<GenerationRunLeaseLostException> { lease.commit {} }
	}

	@Test
	fun heartbeatExceptionImmediatelyRevokesCommitAuthority() {
		val lease = GenerationRunLease(claim, renewClaim = { _, _ -> error("database unavailable") }, clock)

		lease.renew()

		assertFailsWith<GenerationRunLeaseLostException> { lease.checkpoint() }
	}

	@Test
	fun heartbeatCannotInterleaveWithAFencedCommit() {
		val commitEntered = CountDownLatch(1)
		val releaseCommit = CountDownLatch(1)
		val heartbeatEntered = CountDownLatch(1)
		val lease = GenerationRunLease(
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
			assertFailsWith<GenerationRunLeaseLostException> { lease.checkpoint() }
		} finally {
			releaseCommit.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun successfulHeartbeatKeepsTheClaimAndUsesItsFullIdentity() {
		var renewedClaim: ClaimedGenerationRun? = null
		var renewedAt: Instant? = null
		val lease = GenerationRunLease(
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
		val executor = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
		try {
			val factory = ScheduledGenerationRunLeaseFactory(
				executor = executor,
				heartbeatInterval = java.time.Duration.ofMillis(10),
				clock = clock,
				renewClaim = { actualClaim, _ ->
					assertEquals(claim, actualClaim)
					renewed.countDown()
					true
				},
			)

			val handle = factory.open(claim)

			assertTrue(renewed.await(1, TimeUnit.SECONDS))
			handle.close()
			assertTrue(executor.queue.isEmpty())
		} finally {
			executor.shutdownNow()
		}
	}
}
