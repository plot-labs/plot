package com.plot.api.persistence

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class TransactionExecutor {
	@Transactional
	fun <T> execute(action: () -> T): T = action()

	@Transactional
	fun executeWithoutResult(action: () -> Unit) {
		action()
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun <T> executeRequiresNew(action: () -> T): T = action()
}
