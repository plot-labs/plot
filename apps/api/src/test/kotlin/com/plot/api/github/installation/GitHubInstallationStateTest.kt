package com.plot.api.github.installation

import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import com.plot.api.github.GitHubInstallationStateService
import com.plot.api.github.GitHubProperties
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.sql.Timestamp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class GitHubInstallationStateTest {
	@Test
	fun stateIsBoundToContextAndCanBeConsumedOnlyOnce() {
		val jdbc = InMemoryInstallationStateJdbcTemplate()
		val service = GitHubInstallationStateService(
			properties = GitHubProperties(stateSecret = "test-state-secret"),
			devContext = DevContext(),
			jdbcTemplate = jdbc,
			clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC),
		)

		val state = service.create()
		assertNotNull(jdbc.nonceHash)
		assertEquals(false, jdbc.nonceHash!!.contains(state.value))

		service.consume(state.value)
		assertFailsWith<ApiException> { service.consume(state.value) }

		val tampered = state.value.dropLast(1) + if (state.value.last() == 'a') 'b' else 'a'
		assertFailsWith<ApiException> { service.consume(tampered) }
	}

	@Test
	fun expiredStateIsRejected() {
		val jdbc = InMemoryInstallationStateJdbcTemplate()
		val service = GitHubInstallationStateService(
			properties = GitHubProperties(stateSecret = "test-state-secret", stateTtlSeconds = 1),
			devContext = DevContext(),
			jdbcTemplate = jdbc,
			clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC),
		)
		val state = service.create()
		jdbc.expired = true

		assertFailsWith<ApiException> { service.consume(state.value) }
	}
}

private class InMemoryInstallationStateJdbcTemplate : JdbcTemplate() {
	var nonceHash: String? = null
	var workspaceId: UUID? = null
	var userId: UUID? = null
	var expiresAt: Instant? = null
	var consumed = false
	var expired = false

	override fun update(sql: String, vararg args: Any?): Int {
		if (sql.trimStart().startsWith("insert into github_installation_states")) {
			workspaceId = args[1] as UUID
			userId = args[2] as UUID
			nonceHash = args[3] as String
			expiresAt = asInstant(args[4])
			return 1
		}
		if (sql.trimStart().startsWith("update github_installation_states")) {
			val workspace = args[1] as UUID
			val user = args[2] as UUID
			val hash = args[3] as String
			val now = asInstant(args[4])
			if (!consumed && !expired && workspace == workspaceId && user == userId && hash == nonceHash && expiresAt!!.isAfter(now)) {
				consumed = true
				return 1
			}
			return 0
		}
		return 0
	}

	private fun asInstant(value: Any?): Instant = when (value) {
		is Timestamp -> value.toInstant()
		is Instant -> value
		else -> error("Expected JDBC timestamp, got ${value?.javaClass}")
	}
}
