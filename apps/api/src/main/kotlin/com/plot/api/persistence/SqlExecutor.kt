package com.plot.api.persistence

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component

/** Bind-safe access to handwritten SQL without exposing a persistence framework. */
@Component
open class SqlExecutor(
	private val jdbc: JdbcOperations,
) {
	open fun update(sql: String, vararg bindings: Any?): Int = jdbc.update(sql, *bindings)

	open fun <T> query(
		sql: String,
		mapper: (SqlRow, Int) -> T,
		vararg bindings: Any?,
	): List<T> = jdbc.query(sql, RowMapper { resultSet, rowNumber ->
		mapper(SqlRow(resultSet), rowNumber)
	}, *bindings)

	open fun query(sql: String, vararg bindings: Any?): List<SqlRow> =
		query(sql, { row, _ -> row }, *bindings)

	open fun <T> queryForObject(sql: String, type: Class<T>, vararg bindings: Any?): T? =
		query(sql, { row, _ -> row.getObject(1, type) }, *bindings).firstOrNull()

	open fun <T> queryForObject(
		sql: String,
		mapper: (SqlRow, Int) -> T,
		vararg bindings: Any?,
	): T? = query(sql, mapper, *bindings).firstOrNull()

	open fun queryForMap(sql: String, vararg bindings: Any?): Map<String, Any?> =
		query(sql, { row, _ -> row.toMap() }, *bindings).firstOrNull()
			?: error("Expected one SQL row")
}

class SqlRow internal constructor(
	resultSet: ResultSet,
) {
	private val values: List<Any?>
	private val indexesByName: Map<String, Int>

	init {
		val metadata = resultSet.metaData
		values = (1..metadata.columnCount).map(resultSet::getObject)
		indexesByName = (1..metadata.columnCount).associate { index ->
			metadata.getColumnLabel(index) to index
		}
	}

	fun getObject(index: Int): Any? = values[index - 1]

	fun getObject(name: String): Any? = getObject(indexOf(name))

	fun <T> getObject(index: Int, type: Class<T>): T? = getObject(index).convertTo(type)

	fun <T> getObject(name: String, type: Class<T>): T? = getObject(name).convertTo(type)

	fun getString(index: Int): String? = getObject(index)?.toString()

	fun getString(name: String): String? = getObject(name)?.toString()

	fun getInt(index: Int): Int = requireNotNull(getObject(index, Int::class.javaObjectType))

	fun getInt(name: String): Int = requireNotNull(getObject(name, Int::class.javaObjectType))

	fun getLong(index: Int): Long = requireNotNull(getObject(index, Long::class.javaObjectType))

	fun getLong(name: String): Long = requireNotNull(getObject(name, Long::class.javaObjectType))

	fun getBoolean(index: Int): Boolean = requireNotNull(getObject(index, Boolean::class.javaObjectType))

	fun getBoolean(name: String): Boolean = requireNotNull(getObject(name, Boolean::class.javaObjectType))

	fun getTimestamp(index: Int): Timestamp? = getObject(index)?.toTimestamp()

	fun getTimestamp(name: String): Timestamp? = getObject(name)?.toTimestamp()

	@Suppress("UNCHECKED_CAST")
	private fun <T> Any?.convertTo(type: Class<T>): T? {
		if (this == null) return null
		val converted = when (type) {
			String::class.java -> toString()
			Int::class.javaObjectType, Int::class.javaPrimitiveType -> (this as Number).toInt()
			Long::class.javaObjectType, Long::class.javaPrimitiveType -> (this as Number).toLong()
			Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType -> this as Boolean
			UUID::class.java -> if (this is UUID) this else UUID.fromString(toString())
			Instant::class.java -> toInstant()
			OffsetDateTime::class.java -> when (this) {
				is OffsetDateTime -> this
				else -> toInstant().atOffset(java.time.ZoneOffset.UTC)
			}
			else -> type.cast(this)
		}
		return converted as T
	}

	internal fun toMap(): Map<String, Any?> {
		return indexesByName.mapValues { (_, index) ->
			getObject(index)?.normalizeSqlValue()
		}
	}

	private fun indexOf(name: String): Int = indexesByName[name]
		?: indexesByName.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
		?: error("Unknown SQL column: $name")
}

private fun Any.toInstant(): Instant = when (this) {
	is Instant -> this
	is Timestamp -> toInstant()
	is OffsetDateTime -> toInstant()
	is java.util.Date -> toInstant()
	else -> error("Unsupported SQL instant value: ${this::class.java.name}")
}

private fun Any.normalizeSqlValue(): Any = when (this) {
	is OffsetDateTime -> Timestamp.from(toInstant())
	is Instant -> Timestamp.from(this)
	else -> this
}

private fun Any.toTimestamp(): Timestamp = when (this) {
	is Timestamp -> this
	is OffsetDateTime -> Timestamp.from(toInstant())
	is Instant -> Timestamp.from(this)
	is java.util.Date -> Timestamp(time)
	else -> error("Unsupported SQL timestamp value: ${this::class.java.name}")
}
