package com.plot.api.persistence

import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Component

/**
 * Small bind-safe adapter for legacy SQL-shaped persistence code.
 *
 * It intentionally exposes rows rather than domain operations so each caller
 * keeps ownership of its SQL, projections, and transaction boundaries.
 */
@Component
class JooqSqlExecutor(
	private val dsl: DSLContext,
) {
	fun update(sql: String, vararg bindings: Any?): Int = dsl.execute(sql, *bindings)

	fun <T> query(
		sql: String,
		mapper: (SqlRow, Int) -> T,
		vararg bindings: Any?,
	): List<T> = dsl.fetch(sql, *bindings).mapIndexed { index, record ->
		mapper(SqlRow(record), index)
	}

	fun query(sql: String, vararg bindings: Any?): List<SqlRow> =
		dsl.fetch(sql, *bindings).map(::SqlRow)

	fun <T> queryForObject(
		sql: String,
		type: Class<T>,
		vararg bindings: Any?,
	): T? = dsl.fetch(sql, *bindings).firstOrNull()?.get(0, type)
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
