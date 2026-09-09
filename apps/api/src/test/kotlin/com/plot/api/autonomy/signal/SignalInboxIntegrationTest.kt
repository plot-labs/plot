package com.plot.api.autonomy.signal

import com.plot.api.TestcontainersConfiguration
import com.plot.api.persistence.JooqTransactionExecutor
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SignalInboxIntegrationTest {
	@Autowired private lateinit var inbox: SignalInbox
	@Autowired private lateinit var jdbc: JdbcTemplate
	@Autowired private lateinit var transactions: JooqTransactionExecutor
	private val workspaces = mutableListOf<UUID>()
	private val now = Instant.parse("2026-09-09T00:00:00Z")
	private val lease = Duration.ofSeconds(30)

	@AfterEach
	fun cleanup() {
		workspaces.forEach { id ->
			jdbc.update("delete from autonomy_signal_heads where workspace_id = ?", id)
			jdbc.update("delete from autonomy_signals where workspace_id = ?", id)
			jdbc.update("delete from source_scopes where workspace_id = ?", id)
			jdbc.update("delete from source_namespaces where workspace_id = ?", id)
			jdbc.update("delete from workspaces where id = ?", id)
		}
	}

	@Test
	fun `concurrent duplicate deliveries create one durable input`() {
		val input = fixture()
		val pool = Executors.newFixedThreadPool(4)
		try {
			val receipts = pool.invokeAll((1..8).map { Callable { inbox.accept(input, now) } }).map { it.get() }
			assertEquals(1, receipts.count { it.inserted })
			assertEquals(1, receipts.map { it.id }.distinct().size)
		} finally { pool.shutdownNow() }
	}

	@Test
	fun `workspace scope and provider references cannot cross tenant boundaries`() {
		val first = fixture()
		val second = fixture()
		assertFailsWith<DataIntegrityViolationException> {
			inbox.accept(first.copy(sourceScopeId = second.sourceScopeId), now)
		}
		assertFailsWith<DataIntegrityViolationException> {
			inbox.accept(first.copy(provider = "WRONG"), now)
		}
		assertTrue(inbox.accept(first, now).inserted)
		assertTrue(inbox.accept(second.copy(deliveryKey = first.deliveryKey), now).inserted)
	}

	@Test
	fun `expired lease is reclaimed and old worker cannot finish`() {
		val input = fixture()
		inbox.accept(input, now)
		val first = assertNotNull(inbox.claim(input.provider, now, lease))
		assertNull(inbox.claim(input.provider, now, lease))
		val later = now.plusSeconds(31)
		val second = assertNotNull(inbox.claim(input.provider, later, lease))
		assertEquals(first.id, second.id)
		assertEquals(2, second.attempt)
		assertFalse(inbox.finish(first, later))
		transactions.execute {
			assertTrue(inbox.isCurrent(second, later))
			assertTrue(inbox.finish(second, later))
		}
		assertNull(inbox.claim(input.provider, later, lease))
	}

	@Test
	fun `old edits and equal version edits cannot resurrect tombstone`() {
		val input = fixture()
		inbox.accept(input, now)
		val oldClaim = assertNotNull(inbox.claim(input.provider, now, lease))
		val deletion = input.copy(deliveryKey = "deleted", sourceVersion = now.plusSeconds(1), tombstone = true)
		inbox.accept(deletion, now)
		inbox.accept(input.copy(deliveryKey = "late-edit"), now)
		inbox.accept(deletion.copy(deliveryKey = "same-version-edit", tombstone = false), now)
		assertFalse(inbox.isCurrent(oldClaim, now))
		assertTrue(inbox.finish(oldClaim, now, superseded = true))
		val current = assertNotNull(inbox.claim(input.provider, now, lease))
		assertTrue(current.envelope.tombstone)
		transactions.execute {
			assertTrue(inbox.isCurrent(current, now))
			assertTrue(inbox.finish(current, now))
		}
		assertNull(inbox.claim(input.provider, now, lease))
	}

	@Test
	fun `retry waits until due and crash on final attempt becomes terminal`() {
		val input = fixture()
		inbox.accept(input, now)
		val first = assertNotNull(inbox.claim(input.provider, now, lease, 2))
		assertTrue(inbox.retry(first, now, now.plusSeconds(10), "PROVIDER_TIMEOUT", 2))
		assertNull(inbox.claim(input.provider, now, lease, 2))
		assertNotNull(inbox.claim(input.provider, now.plusSeconds(10), lease, 2))
		assertEquals(1, inbox.failExhausted(input.provider, now.plusSeconds(41), 2))
		assertNull(inbox.claim(input.provider, now.plusSeconds(41), lease, 2))
	}

	@Test
	fun `consumer rollback preserves claimed input for retry`() {
		val input = fixture()
		inbox.accept(input, now)
		val claim = assertNotNull(inbox.claim(input.provider, now, lease))
		assertFailsWith<IllegalStateException> {
			transactions.execute {
				assertTrue(inbox.isCurrent(claim, now))
				assertTrue(inbox.finish(claim, now))
				error("simulate consumer write failure")
			}
		}
		assertTrue(inbox.isCurrent(claim, now))
	}

	@Test
	fun `unordered observations preserve independent evidence without replacing versioned head`() {
		val input = fixture()
		inbox.accept(input, now)
		val authoritative = assertNotNull(inbox.claim(input.provider, now, lease))
		inbox.accept(input.copy(deliveryKey = "unordered", sourceVersion = null), now)
		assertTrue(inbox.isCurrent(authoritative, now))
		assertTrue(inbox.finish(authoritative, now))
		val observation = assertNotNull(inbox.claim(input.provider, now, lease))
		assertNull(observation.envelope.sourceVersion)
		assertTrue(inbox.isCurrent(observation, now))
		assertTrue(inbox.finish(observation, now))
		assertEquals(authoritative.id, jdbc.queryForObject(
			"select signal_id from autonomy_signal_heads where workspace_id = ?", UUID::class.java, input.workspaceId,
		))
		assertFailsWith<IllegalArgumentException> { input.copy(tombstone = true, sourceVersion = null) }
	}

	@Test
	fun `delivery collision rejects changed input while equivalent JSON is idempotent`() {
		val input = fixture().copy(payload = "{\"a\":1,\"b\":2}")
		val accepted = inbox.accept(input, now)
		val replay = inbox.accept(input.copy(payload = "{ \"b\": 2, \"a\": 1 }"), now)
		assertEquals(accepted.id, replay.id)
		assertFalse(replay.inserted)
		assertFailsWith<IllegalArgumentException> { inbox.accept(input.copy(payload = "{}"), now) }
		assertFailsWith<IllegalArgumentException> { inbox.accept(input.copy(objectKey = "other"), now) }
		assertFailsWith<IllegalArgumentException> { input.copy(payload = "x".repeat(262145)) }
	}

	private fun fixture(): SignalEnvelope {
		val workspace = UUID.randomUUID()
		val namespace = UUID.randomUUID()
		val scope = UUID.randomUUID()
		val provider = "TEST_$workspace"
		workspaces.add(workspace)
		jdbc.update("insert into workspaces (id, name, slug, status, created_at, updated_at) values (?, 'Signal test', ?, 'ACTIVE', now(), now())", workspace, "$workspace")
		jdbc.update("""insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
		values (?, ?, ?, 'TENANT', 'external-tenant', 'ACTIVE', now(), now())""", namespace, workspace, provider)
		jdbc.update("""insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
		external_scope_key, display_name, status, created_at, updated_at)
		values (?, ?, ?, ?, 'CONTAINER', 'PROJECT', 'external-project', 'Test', 'ACTIVE', now(), now())""", scope, workspace, namespace, provider)
		return SignalEnvelope(workspace, namespace, scope, provider, "delivery-1", "issue:1", "issue.updated", now, "{}")
	}
}
