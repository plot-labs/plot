package com.plot.api.common

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager

class AfterCommitTest {
	@AfterEach
	fun clearTransactionState() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization()
		}
		TransactionSynchronizationManager.setActualTransactionActive(false)
	}

	@Test
	fun runOrNowRunsImmediatelyWithoutTransaction() {
		var calls = 0
		AfterCommit.runOrNow { calls++ }
		assertEquals(1, calls)
	}

	@Test
	fun runOrNowDefersUntilCommitInsideTransaction() {
		beginTransaction()
		var calls = 0

		AfterCommit.runOrNow { calls++ }
		assertEquals(0, calls)

		commit()
		assertEquals(1, calls)
	}

	@Test
	fun runOrNowRunsImmediatelyWhenSynchronizationHasNoActualTransaction() {
		TransactionSynchronizationManager.initSynchronization()
		var calls = 0

		AfterCommit.runOrNow { calls++ }

		assertEquals(1, calls)
		assertEquals(0, TransactionSynchronizationManager.getSynchronizations().size)
	}

	@Test
	fun requireRejectsMissingTransactionWithCallerMessage() {
		val failure = assertThrows(IllegalStateException::class.java) {
			AfterCommit.require("needs a transaction") { error("must not run") }
		}
		assertEquals("needs a transaction", failure.message)
	}

	@Test
	fun requireDefersUntilCommitInsideTransaction() {
		beginTransaction()
		var calls = 0

		AfterCommit.require("needs a transaction") { calls++ }
		assertEquals(0, calls)

		commit()
		assertEquals(1, calls)
	}

	@Test
	fun registerFailsWhenSynchronizationIsInactive() {
		assertThrows(IllegalStateException::class.java) {
			AfterCommit.register { error("must not run") }
		}
	}

	private fun beginTransaction() {
		TransactionSynchronizationManager.initSynchronization()
		TransactionSynchronizationManager.setActualTransactionActive(true)
	}

	private fun commit() {
		TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
	}
}
