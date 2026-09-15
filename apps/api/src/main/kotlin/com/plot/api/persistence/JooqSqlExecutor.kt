package com.plot.api.persistence

import java.sql.SQLException
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException as JooqDataAccessException
import org.springframework.stereotype.Component
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator

/** Temporary exception-translating adapter for callers that still use typed jOOQ DSL. */
@Component
open class JooqSqlExecutor(
	private val dsl: DSLContext,
) {
	private val exceptionTranslator = SQLStateSQLExceptionTranslator()

	open fun <T> executeTyped(action: (DSLContext) -> T): T = withExceptionTranslation {
		action(dsl)
	}

	open fun <T> executeTyped(context: DSLContext, action: (DSLContext) -> T): T = withExceptionTranslation {
		action(context)
	}

	private inline fun <T> withExceptionTranslation(action: () -> T): T = try {
		action()
	} catch (exception: JooqDataAccessException) {
		throw translate(exception)
	}

	private fun translate(exception: JooqDataAccessException): RuntimeException {
		val sqlException = generateSequence(exception as Throwable?) { it.cause }
			.filterIsInstance<SQLException>()
			.firstOrNull()
		val translated: DataAccessException? = sqlException?.let {
			exceptionTranslator.translate("jOOQ SQL", null, it)
		}
		return translated ?: exception
	}
}
