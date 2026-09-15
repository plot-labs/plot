package com.plot.api.persistence

import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator
import org.springframework.stereotype.Component

@Component
class ExposedSqlExecutor {
	private val sqlExceptionTranslator = SQLStateSQLExceptionTranslator()

	fun <T> execute(action: () -> T): T = try {
		action()
	} catch (exception: ExposedSQLException) {
		throw sqlExceptionTranslator.translate("Exposed SQL", null, exception) ?: exception
	}
}
