package com.plot.api.persistence

import java.sql.Timestamp
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.exception.DataAccessException as JooqDataAccessException
import org.springframework.stereotype.Component
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Small bind-safe adapter for legacy SQL-shaped persistence code.
 *
 * It intentionally exposes rows rather than domain operations so each caller
 * keeps ownership of its SQL, projections, and transaction boundaries.
 */
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

	open fun update(sql: String, vararg bindings: Any?): Int = withExceptionTranslation {
		dsl.execute(sql, *bindings)
	}


	open fun <T> query(
		sql: String,
		mapper: (SqlRow, Int) -> T,
		vararg bindings: Any?,
	): List<T> = withExceptionTranslation { dsl.fetch(sql, *bindings).mapIndexed { index, record ->
			mapper(SqlRow(record), index)
		} }

	open fun query(sql: String, vararg bindings: Any?): List<SqlRow> = withExceptionTranslation {
		dsl.fetch(sql, *bindings).map(::SqlRow)
	}

	open fun <T> queryForObject(
		sql: String,
		type: Class<T>,
		vararg bindings: Any?,
	): T? = withExceptionTranslation {
		dsl.fetch(sql, *bindings).firstOrNull()?.get(0, type)
	}

	open fun <T> queryForObject(
		sql: String,
		mapper: (SqlRow, Int) -> T,
		vararg bindings: Any?,
	): T? = query(sql, mapper, *bindings).firstOrNull()

	open fun queryForMap(sql: String, vararg bindings: Any?): Map<String, Any?> = withExceptionTranslation {
		val row = dsl.fetch(sql, *bindings).firstOrNull()
			?: error("Expected one SQL row")
		row.fields().associate { field -> field.name to row.get(field)?.normalizeSqlValue() }
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

private fun Any.normalizeSqlValue(): Any = when (this) {
	is OffsetDateTime -> Timestamp.from(toInstant())
	is Instant -> Timestamp.from(this)
	else -> this
}

@Component
class JooqTransactionExecutor {
	@Transactional
	fun <T> execute(action: () -> T): T = action()

	@Transactional
	fun executeWithoutResult(action: () -> Unit) {
		action()
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun <T> executeRequiresNew(action: () -> T): T = action()
}

class SqlRow internal constructor(
	private val record: Record,
) {
	fun getObject(index: Int): Any? = record.get(index - 1)

	fun getObject(name: String): Any? = record.get(name)

	fun <T> getObject(index: Int, type: Class<T>): T? = record.get(index - 1, type)

	fun <T> getObject(name: String, type: Class<T>): T? = record.get(name, type)

	fun getString(index: Int): String? = getObject(index, String::class.java)

	fun getString(name: String): String? = getObject(name, String::class.java)

	fun getInt(index: Int): Int = requireNotNull(getObject(index, Int::class.javaObjectType))

	fun getInt(name: String): Int = requireNotNull(getObject(name, Int::class.javaObjectType))

	fun getLong(index: Int): Long = requireNotNull(getObject(index, Long::class.javaObjectType))

	fun getLong(name: String): Long = requireNotNull(getObject(name, Long::class.javaObjectType))

	fun getBoolean(index: Int): Boolean = requireNotNull(getObject(index, Boolean::class.javaObjectType))

	fun getBoolean(name: String): Boolean = requireNotNull(getObject(name, Boolean::class.javaObjectType))

	fun getTimestamp(index: Int): Timestamp? = getObject(index)?.toTimestamp()

	fun getTimestamp(name: String): Timestamp? = getObject(name)?.toTimestamp()
}

private fun Any.toTimestamp(): Timestamp = when (this) {
	is Timestamp -> this
	is OffsetDateTime -> Timestamp.from(toInstant())
	is Instant -> Timestamp.from(this)
	is java.util.Date -> Timestamp(time)
	else -> error("Unsupported SQL timestamp value: ${this::class.java.name}")
}
