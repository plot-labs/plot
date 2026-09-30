package com.plot.api.auth

import com.plot.api.TestcontainersConfiguration
import com.plot.api.auth.workos.WorkOSOrganizationDeletionGateway
import com.plot.api.auth.workos.WorkOSUserDeletionGateway
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AccountCleanupIntegrationTest {
	@Autowired private lateinit var persistence: AccountCleanupPersistence
	@Autowired private lateinit var jdbc: JdbcTemplate
	private lateinit var timer: ScheduledThreadPoolExecutor
	private lateinit var cleanup: AccountDeletionProviderCleanup
	private val users = mutableListOf<String>()
	private val deletedUsers = mutableListOf<String>()
	private val deletedOrganizations = mutableListOf<String>()
	private var failOrganization = false

	@BeforeEach
	fun setup() {
		timer = ScheduledThreadPoolExecutor(1)
		cleanup = AccountDeletionProviderCleanup(persistence,
			object : WorkOSUserDeletionGateway {
				override fun delete(userId: String) { deletedUsers += userId }
			},
			object : WorkOSOrganizationDeletionGateway {
                override fun hasOtherActiveMembers(organizationId: String, userId: String) = false
				override fun delete(organizationId: String) {
					if (failOrganization) error("provider unavailable")
					deletedOrganizations += organizationId
				}
			}, SyncTaskExecutor(), timer, true)
	}

	@AfterEach
	fun teardown() {
		timer.shutdownNow()
		users.forEach { jdbc.update("delete from account_deletion_provider_cleanup where workos_user_id = ?", it) }
	}

	@Test
	fun `empty startup leaves no scheduled timer`() {
		cleanup.recoverAtStartup()
		assertNull(persistence.nextWakeupAt())
		assertTrue(timer.queue.isEmpty())
	}

	@Test
	fun `partial provider failure persists future retry and survives a fresh worker`() {
		val user = fixture()
		jdbc.update("insert into account_deletion_provider_organizations values (?, ?)", user, "org-$user")
		failOrganization = true
		cleanup.cleanup(user)
		assertEquals(listOf(user), deletedUsers)
		assertTrue(deletedOrganizations.isEmpty())
		val retryAt = assertNotNull(persistence.nextWakeupAt())
		assertTrue(retryAt.isAfter(Instant.now().plusSeconds(590)))
		assertEquals(1, timer.queue.size)
		cleanup.retryPending()
		assertEquals(1, deletedUsers.size) // A sweep must not run future work early.
		failOrganization = false
		jdbc.update("update account_deletion_provider_cleanup set next_attempt_at = now() - interval '1 second' where workos_user_id = ?", user)
		timer.shutdownNow() // Simulate loss of the original in-memory wakeup.
		timer = ScheduledThreadPoolExecutor(1)
		val restarted = AccountDeletionProviderCleanup(persistence,
			object : WorkOSUserDeletionGateway { override fun delete(userId: String) { deletedUsers += userId } },
			object : WorkOSOrganizationDeletionGateway {
                override fun hasOtherActiveMembers(organizationId: String, userId: String) = false
                override fun delete(organizationId: String) { deletedOrganizations += organizationId } },
			SyncTaskExecutor(), timer, true)
		restarted.recoverAtStartup()
		assertEquals(2, deletedUsers.size)
		assertEquals(listOf("org-$user"), deletedOrganizations)
		assertNull(persistence.nextWakeupAt())
		assertEquals(0, jdbc.queryForObject("select count(*) from account_deletion_provider_organizations where workos_user_id = ?", Int::class.java, user))
	}

	@Test
	fun `concurrent claims have one owner and stale lease owner cannot mutate retry or completion`() {
		val user = fixture()
		val pool = Executors.newFixedThreadPool(4)
		val first = try {
			pool.invokeAll((1..8).map { Callable { persistence.claim(user) } }).mapNotNull { it.get() }.single()
		} finally { pool.shutdownNow() }
		assertNull(persistence.claim(user))
		jdbc.update("update account_deletion_provider_cleanup set lease_until = now() - interval '1 second' where workos_user_id = ?", user)
		val current = assertNotNull(persistence.claim(user))
		assertFalse(persistence.renew(first))
		assertFalse(persistence.complete(first))
		assertFalse(persistence.retry(first, "StaleWorker"))
		assertTrue(persistence.renew(current))
		assertTrue(persistence.complete(current))
	}

	@Test
	fun `startup reclaims a crashed lease and safety sweep drains more than twenty missed deletions`() {
		val orphan = fixture()
		assertNotNull(persistence.claim(orphan))
		jdbc.update("update account_deletion_provider_cleanup set lease_until = now() - interval '1 second' where workos_user_id = ?", orphan)
		cleanup.recoverAtStartup()
		assertEquals(listOf(orphan), deletedUsers)
		repeat(25) { fixture() } // No controller call: initial wakeups were lost.
		cleanup.retryPending()
		assertEquals(26, deletedUsers.size)
		assertNull(persistence.nextWakeupAt())
	}

	@Test
	fun `retry backoff grows to an hour while retaining the deletion obligation`() {
		val user = fixture()
		listOf(10, 20, 40, 60, 60).forEach { minutes ->
			val claim = assertNotNull(persistence.claim(user))
			assertTrue(persistence.retry(claim, "ProviderUnavailable"))
			val seconds = requireNotNull(jdbc.queryForObject(
				"select extract(epoch from (next_attempt_at - now()))::int from account_deletion_provider_cleanup where workos_user_id = ?", Int::class.java, user))
			assertTrue(seconds in (minutes * 60 - 10)..(minutes * 60))
			assertNull(persistence.claim(user))
			jdbc.update("update account_deletion_provider_cleanup set next_attempt_at = now() - interval '1 second' where workos_user_id = ?", user)
		}
		assertEquals(1, jdbc.queryForObject("select count(*) from account_deletion_provider_cleanup where workos_user_id = ?", Int::class.java, user))
	}

	private fun fixture(): String {
		val id = "cleanup-${UUID.randomUUID()}"
		users += id
		// Original insert contract remains valid after migration.
		jdbc.update("insert into account_deletion_provider_cleanup (workos_user_id) values (?)", id)
		return id
	}
}
