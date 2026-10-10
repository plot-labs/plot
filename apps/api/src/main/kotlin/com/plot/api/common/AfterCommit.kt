package com.plot.api.common

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Helpers for work that must not start until the surrounding transaction is
 * durable, typically waking a worker for a row that was just queued.
 */
internal object AfterCommit {
	/** Runs [action] after the active transaction commits, or immediately when there is none. */
	fun runOrNow(action: () -> Unit) {
		if (isTransactionActive()) register(action) else action()
	}

	/** Runs [action] after commit and fails with [message] when no transaction is active. */
	fun require(message: String, action: () -> Unit) {
		check(isTransactionActive()) { message }
		register(action)
	}

	/**
	 * Runs [action] after commit. The caller guarantees a transaction; Spring
	 * rejects the registration when synchronization is not active.
	 */
	fun register(action: () -> Unit) {
		TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
			override fun afterCommit() = action()
		})
	}

	private fun isTransactionActive(): Boolean =
		TransactionSynchronizationManager.isSynchronizationActive() &&
			TransactionSynchronizationManager.isActualTransactionActive()
}
