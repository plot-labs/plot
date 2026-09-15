package com.plot.api.persistence

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import com.plot.api.workspace.Workspace
import com.plot.api.workspace.WorkspaceRepository
import com.plot.api.worksession.WorkSession
import com.plot.api.worksession.WorkSessionPersistence
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Import(TestcontainersConfiguration::class, ExposedFoundationTestConfiguration::class)
@ActiveProfiles("test")
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class ExposedFoundationIntegrationTest {

	@Autowired
	private lateinit var applicationContext: ApplicationContext

	@Autowired
	private lateinit var dsl: DSLContext

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var sqlExecutor: SqlExecutor

	@Autowired
	private lateinit var workSessionPersistence: WorkSessionPersistence

	@Autowired
	private lateinit var rollbackFixture: MixedPersistenceRollbackFixture

	@Test
	fun bootProvidesPostgresDslAndOneExposedTransactionManager() {
		assertEquals(SQLDialect.POSTGRES, dsl.configuration().dialect())
		val transactionManagers = applicationContext.getBeansOfType(PlatformTransactionManager::class.java)
		assertEquals(1, transactionManagers.size)
		assertIs<SpringTransactionManager>(transactionManagers.values.single())
	}

	@Test
	fun exposedConstraintFailuresUseSpringDuplicateKeyTaxonomy() {
		val id = UUID.randomUUID()
		val now = Instant.parse("2026-08-14T00:00:00Z")
		val session = WorkSession(
			id = id,
			workspaceId = devContext.devWorkspaceId,
			title = "Constraint test",
			status = "OPEN",
			createdByUserId = devContext.devUserId,
			latestArtifactWorkflowRunId = null,
			lastActivityAt = now,
			createdAt = now,
			updatedAt = now,
		)

		workSessionPersistence.insert(session)
		try {
			assertFailsWith<DuplicateKeyException> {
				workSessionPersistence.insert(session)
			}
		} finally {
			jdbcTemplate.update("delete from work_sessions where id = ?", id)
		}
	}

	@Test
	@Transactional
	fun sqlExecutorPreservesRawSqlMappingAndSpringExceptionSemantics() {
		val id = UUID.randomUUID()
		val instant = Instant.parse("2026-08-14T12:34:56Z")
		val rows = sqlExecutor.query(
			"select ?::uuid as id, ?::text as value, ?::timestamptz as happened_at, true as enabled",
			{ row, index ->
				assertEquals(0, index)
				listOf(row.getObject(1, UUID::class.java), row.getString("value"), row.getTimestamp(3)?.toInstant(), row.getBoolean("enabled"))
			},
			id,
			"mapped",
			java.sql.Timestamp.from(instant),
		)
		assertEquals(listOf(listOf(id, "mapped", instant, true)), rows)
		val detachedRow = sqlExecutor.query("select ?::uuid as id, ?::text as value", id, "detached").single()
		assertEquals(id, detachedRow.getObject("id", UUID::class.java))
		assertEquals("detached", detachedRow.getString(2))
		assertEquals(null, sqlExecutor.queryForObject("select 1 where false", Int::class.javaObjectType))

		val map = sqlExecutor.queryForMap("select ?::uuid as id, ?::timestamptz as happened_at", id, java.sql.Timestamp.from(instant))
		assertEquals(id, map["id"])
		assertEquals(instant, (map["happened_at"] as java.sql.Timestamp).toInstant())

		sqlExecutor.update("create temporary table sql_executor_unique_test (id integer primary key)")
		sqlExecutor.update("insert into sql_executor_unique_test (id) values (?)", 1)
		assertFailsWith<DuplicateKeyException> {
			sqlExecutor.update("insert into sql_executor_unique_test (id) values (?)", 1)
		}
	}

	@Test
	fun jooqAndExposedWritesRollBackTogether() {
		val workspaceId = UUID.randomUUID()
		val sessionId = UUID.randomUUID()

		assertFailsWith<IllegalStateException> {
			rollbackFixture.writeThenFail(workspaceId, sessionId)
		}

		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from workspaces where id = ?",
				Long::class.java,
				workspaceId,
			),
		)
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from work_sessions where id = ?",
				Long::class.java,
				sessionId,
			),
		)
	}
}

@TestConfiguration(proxyBeanMethods = false)
class ExposedFoundationTestConfiguration {
	@Bean
	fun mixedPersistenceRollbackFixture(
		devContext: DevContext,
		workspaceRepository: WorkspaceRepository,
		workSessionPersistence: WorkSessionPersistence,
	): MixedPersistenceRollbackFixture {
		return MixedPersistenceRollbackFixture(devContext, workspaceRepository, workSessionPersistence)
	}
}

open class MixedPersistenceRollbackFixture(
	private val devContext: DevContext,
	private val workspaceRepository: WorkspaceRepository,
	private val workSessionPersistence: WorkSessionPersistence,
) {
	@Transactional
	open fun writeThenFail(workspaceId: UUID, sessionId: UUID) {
		val now = Instant.parse("2026-08-14T00:00:00Z")
		workspaceRepository.save(
			Workspace(
				id = workspaceId,
				name = "rollback-workspace",
				slug = "rollback-${workspaceId}",
				createdByUserId = devContext.devUserId,
				status = "ACTIVE",
				createdAt = now,
				updatedAt = now,
				trialStartedAt = now,
				trialEndsAt = now.plus(30, ChronoUnit.DAYS),
			)
		)
		workSessionPersistence.insert(
			WorkSession(
				id = sessionId,
				workspaceId = workspaceId,
				title = "rollback-session",
				status = "OPEN",
				createdByUserId = devContext.devUserId,
				latestArtifactWorkflowRunId = null,
				lastActivityAt = now,
				createdAt = now,
				updatedAt = now,
			)
		)
		throw IllegalStateException("rollback fixture")
	}
}
