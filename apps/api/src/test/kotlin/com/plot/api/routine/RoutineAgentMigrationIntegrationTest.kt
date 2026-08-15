package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RoutineAgentMigrationIntegrationTest {
	@Autowired private lateinit var dataSource: javax.sql.DataSource
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var uuidGenerator: UuidGenerator

	private lateinit var schema: String
	private lateinit var schemaJdbcTemplate: JdbcTemplate
	private lateinit var persistence: RoutineAgentPersistence

	@BeforeEach
	fun createIsolatedSchema() {
		schema = "routine_agent_${UUID.randomUUID().toString().replace("-", "")}"
		jdbcTemplate.execute("create schema $schema")
		migrateToLatest()
		val schemaDataSource = SearchPathDataSource(dataSource, schema)
		schemaJdbcTemplate = JdbcTemplate(schemaDataSource)
		val schemaSqlExecutor = JooqSqlExecutor(DSL.using(schemaDataSource, SQLDialect.POSTGRES))
		val schemaTransactionExecutor = JooqTransactionExecutor()
		persistence = RoutineAgentPersistence(
			schemaSqlExecutor,
			schemaTransactionExecutor,
			uuidGenerator,
			AgentRunPersistence(
				schemaSqlExecutor,
				schemaTransactionExecutor,
				uuidGenerator,
			),
		)
	}

	@AfterEach
	fun dropIsolatedSchema() {
		jdbcTemplate.execute("drop schema $schema cascade")
	}

	@Test
	fun `duplicate trigger key is idempotent and fingerprint changes conflict`() {
		val fixture = insertFixture()
		val request = executionRequest(fixture)

		val first = withSchema { persistence.createExecution(request) }
		val replay = withSchema {
			persistence.createExecution(
				request.copy(
					triggerKey = "  ${request.triggerKey}  ",
					requestFingerprint = "  ${request.requestFingerprint}  ",
				),
			)
		}

		assertEquals(first.id, replay.id)
		assertEquals(1, count("routine_executions"))
		assertFailsWith<RoutineExecutionIdempotencyConflictException> {
			withSchema { persistence.createExecution(request.copy(requestFingerprint = "fingerprint-b")) }
		}
		assertEquals(1, count("routine_executions"))
	}

	@Test
	fun `canonical execution claim can be recovered and stale owner cannot finish it`() {
		val fixture = insertFixture()
		val execution = withSchema { persistence.createExecution(executionRequest(fixture)) }
		val firstClaim = withSchema {
			persistence.claimById(
				"worker-a",
				fixture.workspaceId,
				execution.id,
				fixture.createdAt,
				fixture.createdAt.minusSeconds(120),
			)
		}
		assertNotNull(firstClaim)
		val recovered = withSchema {
			persistence.claimById(
				"worker-b",
				fixture.workspaceId,
				execution.id,
				fixture.createdAt.plusSeconds(301),
				fixture.createdAt.plusSeconds(181),
			)
		}
		assertEquals("worker-b", recovered?.claimedBy)
		assertFailsWith<RoutineExecutionStateException> {
			withSchema {
				persistence.markNoActivity(
					fixture.workspaceId,
					execution.id,
					fixture.createdAt.plusSeconds(302),
					workerId = "worker-a",
				)
			}
		}
		withSchema {
			persistence.markNoActivity(
				fixture.workspaceId,
				execution.id,
				fixture.createdAt.plusSeconds(302),
				workerId = "worker-b",
			)
		}
		assertEquals(RoutineExecutionStatus.NO_ACTIVITY, withSchema {
			persistence.findExecution(fixture.workspaceId, execution.id)?.status
		})
	}

	@Test
	fun `execution trigger source must match routine source`() {
		val fixture = insertFixture()
		val otherSourceScopeId = insertSourceScope(fixture.workspaceId, fixture.namespaceId, "other-trigger")

		assertFailsWith<DataIntegrityViolationException> {
			withSchema {
				persistence.createExecution(
					executionRequest(fixture).copy(triggerSourceScopeId = otherSourceScopeId),
				)
			}
		}
		assertEquals(0, count("routine_executions"))
	}

	@Test
	fun `NO_ACTIVITY rejects child dispatch`() {
		val fixture = insertFixture()
		val execution = withSchema { persistence.createExecution(executionRequest(fixture)) }
		withSchema { persistence.markNoActivity(fixture.workspaceId, execution.id) }

		assertFailsWith<RoutineExecutionStateException> {
			withSchema { persistence.dispatch(fixture.workspaceId, execution.id, dispatchRequest(fixture)) }
		}
		assertEquals(0, count("work_sessions"))
		assertEquals(0, count("agent_runs"))
		assertEquals(RoutineExecutionStatus.NO_ACTIVITY, withSchema {
			persistence.findExecution(fixture.workspaceId, execution.id)?.status
		})
	}

	@Test
	fun `dispatch is atomic when a later source row violates workspace alignment`() {
		val fixture = insertFixture()
		val other = insertFixture()
		val execution = withSchema { persistence.createExecution(executionRequest(fixture)) }
		val request = dispatchRequest(fixture).copy(
			sourceScopes = listOf(
				AgentRunSourceRequest(
					fixture.sourceScopeId,
					AgentRunSourceRole.TRIGGER,
					capturedStatusChangedAt = fixture.statusChangedAt,
				),
				AgentRunSourceRequest(
					other.sourceScopeId,
					AgentRunSourceRole.CONTEXT,
					capturedStatusChangedAt = other.statusChangedAt,
				),
			),
		)

		assertFailsWith<RoutineExecutionStateException> {
			withSchema { persistence.dispatch(fixture.workspaceId, execution.id, request) }
		}
		assertEquals(0, count("work_sessions"))
		assertEquals(0, count("agent_runs"))
		assertEquals(0, count("agent_run_sources"))
		assertEquals(0, count("agent_run_inputs"))
		assertEquals(RoutineExecutionStatus.PROBING, withSchema {
			persistence.findExecution(fixture.workspaceId, execution.id)?.status
		})
		assertNull(withSchema { persistence.findExecution(fixture.workspaceId, execution.id)?.activityCursorAfter })
	}

	@Test
	fun `dispatch links one AgentRun frozen sources and seed inputs to one Chat`() {
		val fixture = insertFixture()
		val context = insertSourceScope(fixture.workspaceId, fixture.namespaceId, "context")
		val execution = withSchema { persistence.createExecution(executionRequest(fixture)) }
		withSchema { persistence.addContextSource(fixture.workspaceId, fixture.routineId, context, 0, fixture.createdAt) }
		val agentRun = withSchema {
			persistence.dispatch(
				fixture.workspaceId,
				execution.id,
				dispatchRequest(fixture).copy(
					sourceScopes = listOf(
						AgentRunSourceRequest(
							fixture.sourceScopeId,
							AgentRunSourceRole.TRIGGER,
							capturedStatusChangedAt = fixture.statusChangedAt,
						),
						AgentRunSourceRequest(
							context,
							AgentRunSourceRole.CONTEXT,
							capturedStatusChangedAt = fixture.statusChangedAt,
						),
					),
					inputs = listOf(seedInput(fixture, fixture.sourceScopeId)),
				),
			)
		}

		assertEquals(execution.id, agentRun.routineExecutionId)
		val chatId = requireNotNull(agentRun.workSessionId)
		assertEquals(1, count("work_sessions"))
		assertEquals(1, count("agent_runs"))
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from work_sessions where routine_execution_id = ? and id = ?",
			Int::class.java,
			execution.id,
			chatId,
		))
		assertEquals(2, withSchema { persistence.listAgentRunSources(fixture.workspaceId, agentRun.id).size })
		assertEquals(1, withSchema { persistence.listAgentRunInputs(fixture.workspaceId, agentRun.id).size })
		assertEquals(RoutineExecutionStatus.DISPATCHED, withSchema {
			persistence.findExecution(fixture.workspaceId, execution.id)?.status
		})
		assertEquals(10L, jdbcTemplate.queryForObject(
			"select activity_cursor_sequence from $schema.routines where id = ?",
			Long::class.java,
			fixture.routineId,
		))
	}

	@Test
	fun `routine agent schema has Chat linkage`() {
		assertEquals(2, schemaJdbcTemplate.queryForObject(
			"""
			select count(*)
			from information_schema.columns
			where table_schema = ?
			  and ((table_name = 'agent_runs' and column_name = 'work_session_id')
			    or (table_name = 'work_sessions' and column_name = 'routine_execution_id'))
			""".trimIndent(),
			Int::class.java,
			schema,
		))
	}

	@Test
	fun `Chat AgentRuns can share one Chat and keep request identity`() {
		val fixture = insertFixture()
		val chatId = UUID.randomUUID()
		val now = Timestamp.from(fixture.createdAt)
		schemaJdbcTemplate.update(
			"insert into $schema.work_sessions (id, workspace_id, title, status, created_by_user_id, last_activity_at, created_at, updated_at) values (?, ?, 'Interactive chat', 'OPEN', ?, ?, ?, ?)",
			chatId,
			fixture.workspaceId,
			fixture.userId,
			now,
			now,
			now,
		)

		val agentIds = listOf(UUID.randomUUID(), UUID.randomUUID())
		agentIds.forEachIndexed { index, agentId ->
			schemaJdbcTemplate.update(
				"""
				insert into $schema.agent_runs (
				  id, workspace_id, work_session_id, created_by_user_id, origin,
				  idempotency_key, request_fingerprint, instruction_snapshot,
				  prompt_version, tool_policy_version, budget_snapshot, status,
				  current_step, attempt_count, max_attempts, created_at, updated_at
				) values (?, ?, ?, ?, 'CHAT', ?, ?, 'Find a concise update', 'prompt-v1', 'read-only-v1', '{}'::jsonb, 'QUEUED', 0, 0, 3, ?, ?)
				""".trimIndent(),
				agentId,
				fixture.workspaceId,
				chatId,
				fixture.userId,
				"chat-request-$index",
				"chat-fingerprint-$index",
				now,
				now,
			)
		}

		assertEquals(2, schemaJdbcTemplate.queryForObject(
			"select count(*) from $schema.agent_runs where workspace_id = ? and work_session_id = ? and origin = 'CHAT'",
			Int::class.java,
			fixture.workspaceId,
			chatId,
		))
		assertEquals(AgentRunOrigin.CHAT, withSchema { persistence.findAgentRun(fixture.workspaceId, agentIds.first())?.origin })
		assertEquals("chat-request-1", withSchema { persistence.findAgentRun(fixture.workspaceId, agentIds.last())?.idempotencyKey })
	}

	@Test
	fun `stale execution cannot dispatch after another execution advances cursor`() {
		val fixture = insertFixture()
		val firstExecution = withSchema { persistence.createExecution(executionRequest(fixture, "manual:first")) }
		val staleExecution = withSchema { persistence.createExecution(executionRequest(fixture, "manual:stale")) }
		withSchema { persistence.dispatch(fixture.workspaceId, firstExecution.id, dispatchRequest(fixture)) }

		assertFailsWith<RoutineExecutionStateException> {
			withSchema {
				persistence.dispatch(
					fixture.workspaceId,
					staleExecution.id,
					dispatchRequest(fixture).copy(
						inputs = listOf(seedInput(fixture, fixture.sourceScopeId).copy(activitySequence = 11)),
						activityCursorAfter = 11,
					),
				)
			}
		}
		assertEquals(1, count("work_sessions"))
		assertEquals(1, count("agent_runs"))
		assertEquals(10L, jdbcTemplate.queryForObject(
			"select activity_cursor_sequence from $schema.routines where id = ?",
			Long::class.java,
			fixture.routineId,
		))
		assertEquals(RoutineExecutionStatus.PROBING, withSchema {
			persistence.findExecution(fixture.workspaceId, staleExecution.id)?.status
		})
	}

	@Test
	fun `seed identity conflicts across triggers while context input can reuse evidence`() {
		val fixture = insertFixture()
		val firstExecution = withSchema { persistence.createExecution(executionRequest(fixture, "manual:one")) }
		val secondExecution = withSchema {
			persistence.createExecution(executionRequest(fixture, "manual:two", activityCursorBefore = 10))
		}
		val firstRun = withSchema { persistence.dispatch(fixture.workspaceId, firstExecution.id, dispatchRequest(fixture)) }
		val secondRun = withSchema {
			persistence.dispatch(
				fixture.workspaceId,
				secondExecution.id,
				dispatchRequest(fixture).copy(
					inputs = listOf(seedInput(fixture, fixture.sourceScopeId).copy(activitySequence = 11)),
					activityCursorAfter = 11,
				),
			)
		}

		assertFailsWith<DataIntegrityViolationException> {
			insertSeedInput(fixture, firstRun.id, orderIndex = 1, activitySequence = 11)
		}
		assertEquals(2, count("agent_run_inputs"))

		val contextRun = withSchema { persistence.findAgentRun(fixture.workspaceId, firstRun.id) }
		assertNotNull(contextRun)
		assertEquals(fixture.routineId, secondRun.routineId)
		val contextStepInput = AgentRunInputRequest(
			routineId = null,
			sourceScopeId = fixture.sourceScopeId,
			writingBlockId = fixture.blockId,
			sourceProvider = "GITHUB",
			sourceKind = "COMMIT",
			sourceLabel = "Adopted",
			inputKind = AgentRunInputKind.TOOL_RESULT,
			orderIndex = 1,
			activitySequence = null,
			snapshotTitle = "Adopted",
			snapshotBody = "Adopted context",
			snapshotExcerpt = "Adopted context",
			originalUrl = "https://github.com/acme/plot/commit/context",
			sourceCreatedAt = fixture.createdAt,
			sourceUpdatedAt = fixture.createdAt,
			contentHash = "context-hash",
			capturedAt = fixture.createdAt,
		)
		assertFailsWith<DataIntegrityViolationException> {
			withSchema {
				persistence.appendInput(
					fixture.workspaceId,
					firstRun.id,
					contextStepInput.copy(activitySequence = 11),
				)
			}
		}
		val appended = withSchema {
			persistence.appendInput(fixture.workspaceId, firstRun.id, contextStepInput)
		}
		assertEquals(AgentRunInputKind.TOOL_RESULT, appended.inputKind)
		assertEquals(2, withSchema { persistence.listAgentRunInputs(fixture.workspaceId, firstRun.id).size })
	}

	@Test
	fun `agent step sequence is unique per run`() {
		val fixture = insertFixture()
		val execution = withSchema { persistence.createExecution(executionRequest(fixture)) }
		val agentRun = withSchema { persistence.dispatch(fixture.workspaceId, execution.id, dispatchRequest(fixture)) }
		val step = AgentStepRequest(
			agentRunId = agentRun.id,
			sequence = 0,
			kind = AgentStepKind.READ_TOOL,
			status = AgentStepStatus.SUCCEEDED,
			idempotencyKey = "step-0",
			toolName = "READ_WRITING_BLOCKS",
			argumentsJson = "{}",
		)
		withSchema { persistence.appendStep(fixture.workspaceId, step) }
		assertFailsWith<DataIntegrityViolationException> {
			withSchema {
				persistence.appendStep(
					fixture.workspaceId,
					step.copy(sequence = 1, idempotencyKey = "array-result", resultJson = "[]"),
				)
			}
		}
		val seedInputId = withSchema {
			persistence.listAgentRunInputs(fixture.workspaceId, agentRun.id).single().id
		}
		assertFailsWith<DataIntegrityViolationException> {
			withSchema {
				persistence.appendStep(
					fixture.workspaceId,
					AgentStepRequest(
						agentRunId = agentRun.id,
						sequence = 1,
						kind = AgentStepKind.ARTIFACT_HANDOFF,
						status = AgentStepStatus.PENDING,
						idempotencyKey = "handoff-with-input",
						adoptedInputId = seedInputId,
					),
				)
			}
		}
		assertFailsWith<DataIntegrityViolationException> {
			withSchema { persistence.appendStep(fixture.workspaceId, step.copy(idempotencyKey = "step-0-retry")) }
		}
		assertEquals(1, withSchema { persistence.listSteps(fixture.workspaceId, agentRun.id).size })
	}

	@Test
	fun `generation attempts are linked to one agent run and handoff cannot cross runs`() {
		val fixture = insertFixture()
		val firstExecution = withSchema { persistence.createExecution(executionRequest(fixture, "manual:first")) }
		val firstRun = withSchema { persistence.dispatch(fixture.workspaceId, firstExecution.id, dispatchRequest(fixture)) }
		val secondExecution = withSchema {
			persistence.createExecution(executionRequest(fixture, "manual:second", activityCursorBefore = 10))
		}
		val secondRun = withSchema {
			persistence.dispatch(
				fixture.workspaceId,
				secondExecution.id,
				dispatchRequest(fixture).copy(
					inputs = listOf(seedInput(fixture, fixture.sourceScopeId).copy(activitySequence = 11)),
					activityCursorAfter = 11,
				),
			)
		}

		assertEquals("YES", jdbcTemplate.queryForObject(
			"""
			select is_nullable
			from information_schema.columns
			where table_schema = ? and table_name = 'generation_runs' and column_name = 'agent_run_id'
			""".trimIndent(),
			String::class.java,
			schema,
		))
		insertArtifactWorkflowRun(fixture, firstRun.id, "QUEUED", "active-first")
		assertFailsWith<DataIntegrityViolationException> {
			insertArtifactWorkflowRun(fixture, firstRun.id, "WRITING", "active-second")
		}
		val materializedRunId = insertArtifactWorkflowRun(fixture, secondRun.id, "READY", "materialized-first")
		assertFailsWith<DataIntegrityViolationException> {
			insertArtifactWorkflowRun(fixture, secondRun.id, "NEEDS_REVIEW", "materialized-second")
		}
		val otherFixture = insertFixture()
		val otherExecution = withSchema { persistence.createExecution(executionRequest(otherFixture)) }
		val otherRun = withSchema { persistence.dispatch(otherFixture.workspaceId, otherExecution.id, dispatchRequest(otherFixture)) }
		assertFailsWith<DataIntegrityViolationException> {
			insertArtifactWorkflowRun(fixture, otherRun.id, "QUEUED", "cross-workspace")
		}
		assertFailsWith<DataIntegrityViolationException> {
			withSchema {
				persistence.appendStep(
					fixture.workspaceId,
					AgentStepRequest(
						agentRunId = firstRun.id,
						sequence = 0,
						kind = AgentStepKind.ARTIFACT_HANDOFF,
						status = AgentStepStatus.PENDING,
						idempotencyKey = "cross-run-handoff",
						artifactWorkflowRunId = materializedRunId,
					),
				)
			}
		}
		assertEquals(2, count("generation_runs"))
	}

	private fun executionRequest(
		fixture: Fixture,
		triggerKey: String = "manual:${fixture.routineId}",
		activityCursorBefore: Long? = null,
	) =
		RoutineExecutionRequest(
			workspaceId = fixture.workspaceId,
			routineId = fixture.routineId,
			createdByUserId = fixture.userId,
			triggerSourceScopeId = fixture.sourceScopeId,
			triggerKind = RoutineExecutionTriggerKind.MANUAL,
			triggerKey = triggerKey,
			requestFingerprint = "fingerprint-a",
			activityCursorBefore = activityCursorBefore,
		)

	private fun dispatchRequest(fixture: Fixture) = AgentRunDispatchRequest(
		instructionSnapshot = "Draft a concise update",
		promptVersion = "prompt-v1",
		toolPolicyVersion = "tools-v1",
		sourceScopes = listOf(
			AgentRunSourceRequest(
				fixture.sourceScopeId,
				AgentRunSourceRole.TRIGGER,
				capturedStatusChangedAt = fixture.statusChangedAt,
			),
		),
		inputs = listOf(seedInput(fixture, fixture.sourceScopeId)),
		activityCursorAfter = 10,
	)

	private fun seedInput(fixture: Fixture, sourceScopeId: UUID) = AgentRunInputRequest(
		routineId = fixture.routineId,
		sourceScopeId = sourceScopeId,
		writingBlockId = fixture.blockId,
		sourceProvider = "GITHUB",
		sourceKind = "COMMIT",
		sourceLabel = "Activity",
		inputKind = AgentRunInputKind.SEED,
		orderIndex = 0,
		activitySequence = 10,
		snapshotTitle = "Activity",
		snapshotBody = "A bounded activity snapshot",
		snapshotExcerpt = "A bounded activity snapshot",
		originalUrl = "https://github.com/acme/plot/commit/activity",
		sourceCreatedAt = fixture.createdAt,
		sourceUpdatedAt = fixture.createdAt,
		contentHash = "activity-hash",
		capturedAt = fixture.createdAt,
	)

	private fun insertSeedInput(fixture: Fixture, agentRunId: UUID, orderIndex: Int, activitySequence: Long) {
		schemaJdbcTemplate.update(
			"""
			insert into $schema.agent_run_inputs (
			  id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
			  source_provider, source_kind, source_label,
			  input_kind, order_index, activity_sequence, snapshot_title, snapshot_body,
			  snapshot_excerpt, original_url, source_created_at, source_updated_at,
			  content_hash, captured_at
			) values (?, ?, ?, ?, ?, ?, 'GITHUB', 'COMMIT', 'Activity', 'SEED', ?, ?, 'Activity', 'A bounded activity snapshot',
			          'A bounded activity snapshot', 'https://github.com/acme/plot/commit/activity', ?, ?,
			          'activity-hash', ?)
			""".trimIndent(),
			UUID.randomUUID(),
			fixture.workspaceId,
			agentRunId,
			fixture.routineId,
			fixture.sourceScopeId,
			fixture.blockId,
			orderIndex,
			activitySequence,
			Timestamp.from(fixture.createdAt),
			Timestamp.from(fixture.createdAt),
			Timestamp.from(fixture.createdAt),
		)
	}

	private fun insertArtifactWorkflowRun(fixture: Fixture, agentRunId: UUID, status: String, idempotencyKey: String): UUID {
		val id = UUID.randomUUID()
		val now = Timestamp.from(fixture.createdAt)
		schemaJdbcTemplate.update(
			"""
			insert into $schema.generation_runs (
			  id, workspace_id, agent_run_id, source_scope_id, created_by_user_id,
			  idempotency_key, request_fingerprint, status, workflow_version, prompt_version,
			  output_schema_version, budget_version, provider, model_name, budget_snapshot,
			  created_at, updated_at, finished_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, 'fixed-v1', 'prompt-v1', 'schema-v1', 'budget-v1',
			          'test', 'model', '{}'::jsonb, ?, ?, ?)
			""".trimIndent(),
			id,
			fixture.workspaceId,
			agentRunId,
			fixture.sourceScopeId,
			fixture.userId,
			idempotencyKey,
			"fingerprint-$idempotencyKey",
			status,
			now,
			now,
			if (status == "READY" || status == "NEEDS_REVIEW") now else null,
		)
		return id
	}

	private fun insertFixture(): Fixture {
		val userId = UUID.randomUUID()
		val workspaceId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val routineId = UUID.randomUUID()
		val blockId = UUID.randomUUID()
		val now = Instant.parse("2026-08-09T00:00:00Z")
		schemaJdbcTemplate.update(
			"insert into $schema.users (id, email, display_name, status, created_at, updated_at) values (?, ?, 'Agent Test', 'ACTIVE', ?, ?)",
			userId, "${UUID.randomUUID()}@example.com", Timestamp.from(now), Timestamp.from(now),
		)
		schemaJdbcTemplate.update(
			"insert into $schema.workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at) values (?, 'Agent Workspace', ?, ?, 'ACTIVE', ?, ?)",
			workspaceId, "agent-${UUID.randomUUID()}", userId, Timestamp.from(now), Timestamp.from(now),
		)
		insertSourceNamespace(workspaceId, namespaceId, "root")
		insertConnectionBinding(workspaceId, namespaceId, userId, now)
		insertSourceScope(workspaceId, namespaceId, "trigger", scopeId)
		schemaJdbcTemplate.update(
			"""
			insert into $schema.writing_blocks (
			 id, workspace_id, source_namespace_id, external_object_key, source_origin, source_kind,
			 title, body, url, canonical_url, platform, content_hash, ingested_at, status,
			 created_by_user_id, created_at, updated_at
			) values (?, ?, ?, ?, 'integration', 'commit', 'Activity', 'A bounded activity', ?, ?, 'github', ?, ?, 'ACTIVE', ?, ?, ?)
			""".trimIndent(),
			blockId, workspaceId, namespaceId, "commit:$blockId",
			"https://github.com/acme/plot/commit/$blockId", "https://github.com/acme/plot/commit/$blockId",
			"block-hash", Timestamp.from(now), userId, Timestamp.from(now), Timestamp.from(now),
		)
		schemaJdbcTemplate.update(
			"""
			insert into $schema.writing_block_scopes (
			 id, workspace_id, source_namespace_id, writing_block_id, source_scope_id,
			 membership_kind, status, first_seen_at, last_seen_at
			) values (?, ?, ?, ?, ?, 'CONTAINED_IN', 'ACTIVE', ?, ?)
			""".trimIndent(),
			UUID.randomUUID(), workspaceId, namespaceId, blockId, scopeId, Timestamp.from(now), Timestamp.from(now),
		)
		schemaJdbcTemplate.update(
			"""
			insert into $schema.routines (
			 id, workspace_id, created_by_user_id, source_scope_id, name, instruction, cadence,
			 enabled, next_run_at, created_at, updated_at
			) values (?, ?, ?, ?, 'Agent routine', 'Draft a concise update', 'DAILY', true, ?, ?, ?)
			""".trimIndent(),
			routineId, workspaceId, userId, scopeId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
		)
		return Fixture(userId, workspaceId, namespaceId, scopeId, routineId, blockId, "Agent routine", now, now)
	}

	private fun insertSourceNamespace(workspaceId: UUID, namespaceId: UUID, suffix: String) {
		schemaJdbcTemplate.update(
			"""
			insert into $schema.source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'REPOSITORY', ?, ?, 'ACTIVE', ?, ?)
			""".trimIndent(),
			namespaceId, workspaceId, "namespace:$suffix:$namespaceId", suffix, Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW),
		)
	}

	private fun insertConnectionBinding(workspaceId: UUID, namespaceId: UUID, userId: UUID, now: Instant) {
		val connectionId = UUID.randomUUID()
		schemaJdbcTemplate.update(
			"insert into $schema.connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at) values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, ?, ?)",
			connectionId,
			workspaceId,
			(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).toString(),
			userId,
			Timestamp.from(now),
			Timestamp.from(now),
		)
		schemaJdbcTemplate.update(
			"insert into $schema.connection_namespace_bindings (id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at) values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', ?, ?, ?)",
			UUID.randomUUID(),
			workspaceId,
			connectionId,
			namespaceId,
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
		)
	}

	private fun insertSourceScope(workspaceId: UUID, namespaceId: UUID, suffix: String, scopeId: UUID = UUID.randomUUID()): UUID {
		schemaJdbcTemplate.update(
			"""
			insert into $schema.source_scopes (
			 id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, display_name, status, status_changed_at, created_at, updated_at
			) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, 'ACTIVE', ?, ?, ?)
			""".trimIndent(),
			scopeId, workspaceId, namespaceId, "scope:$suffix:$scopeId", suffix,
			Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW),
		)
		return scopeId
	}

	private fun migrateToLatest() {
		Flyway.configure()
			.dataSource(dataSource)
			.schemas(schema)
			.defaultSchema(schema)
			.locations("classpath:db/migration")
			.group(true)
			.load()
			.migrate()
	}

	private fun count(table: String): Int = jdbcTemplate.queryForObject(
		"select count(*) from $schema.$table",
		Int::class.java,
	) ?: 0

	private fun <T> withSchema(block: () -> T): T = block()

	private class SearchPathDataSource(
		private val delegate: javax.sql.DataSource,
		private val schema: String,
	) : AbstractDataSource() {
		override fun getConnection(): Connection = delegate.connection.withSchema()

		override fun getConnection(username: String?, password: String?): Connection =
			delegate.getConnection(username, password).withSchema()

		private fun Connection.withSchema(): Connection = apply {
			setSchema(this@SearchPathDataSource.schema)
			createStatement().use { statement ->
				statement.execute("set search_path to '${this@SearchPathDataSource.schema}'")
			}
		}
	}

	private data class Fixture(
		val userId: UUID,
		val workspaceId: UUID,
		val namespaceId: UUID,
		val sourceScopeId: UUID,
		val routineId: UUID,
		val blockId: UUID,
		val routineName: String,
		val createdAt: Instant,
		val statusChangedAt: Instant,
	)

	private companion object {
		val TEST_NOW = Instant.parse("2026-08-09T00:00:00Z")
	}
}
