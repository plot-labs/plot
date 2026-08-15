package com.plot.api.source

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class SourceScopePersistenceIntegrationTest {

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var sourceScopeRepository: SourceScopeRepository

	@Test
	fun `maps sql null json null nested json and postgres timestamp precision`() {
		val namespaceId = UUID.randomUUID()
		val sqlNullScopeId = UUID.randomUUID()
		val jsonScopeId = UUID.randomUUID()
		val createdAt = Instant.parse("2026-08-14T12:34:56.123456Z")
		val updatedAt = Instant.parse("2026-08-14T12:34:57.654321Z")
		try {
			jdbcTemplate.update(
				"""
				insert into source_namespaces (
					id, workspace_id, provider, namespace_kind, external_namespace_key,
					display_name, status, created_at, updated_at
				) values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'JSON fixture', 'ACTIVE', ?, ?)
				""".trimIndent(),
				namespaceId,
				devContext.devWorkspaceId,
				"json-fixture-$namespaceId",
				Timestamp.from(createdAt),
				Timestamp.from(updatedAt),
			)
			insertScope(sqlNullScopeId, namespaceId, null, createdAt, updatedAt)
			insertScope(
				jsonScopeId,
				namespaceId,
				"""{"nested":{"items":[1,true,null],"name":"fixture"},"nullValue":null}""",
				createdAt,
				updatedAt,
			)

			val sqlNullScope = sourceScopeRepository.findByWorkspaceIdAndId(
				devContext.devWorkspaceId,
				sqlNullScopeId,
			)
			assertNotNull(sqlNullScope)
			assertNull(sqlNullScope.metadata)

			val jsonScope = sourceScopeRepository.findByWorkspaceIdAndId(
				devContext.devWorkspaceId,
				jsonScopeId,
			)
			assertNotNull(jsonScope)
			assertEquals(createdAt, jsonScope.createdAt)
			assertEquals(updatedAt, jsonScope.updatedAt)
			val metadata = assertNotNull(jsonScope.metadata)
			assertNull(metadata["nullValue"])
			val nested = metadata["nested"] as Map<*, *>
			assertEquals("fixture", nested["name"])
			assertEquals(listOf(1, true, null), nested["items"])
		} finally {
			jdbcTemplate.update("delete from source_scopes where id in (?, ?)", sqlNullScopeId, jsonScopeId)
			jdbcTemplate.update("delete from source_namespaces where id = ?", namespaceId)
		}
	}

	private fun insertScope(
		id: UUID,
		namespaceId: UUID,
		metadata: String?,
		createdAt: Instant,
		updatedAt: Instant,
	) {
		jdbcTemplate.update(
			"""
			insert into source_scopes (
				id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
				external_scope_key, external_key, display_name, metadata, status, created_at, updated_at
			) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, 'JSON fixture', ?::jsonb, 'ACTIVE', ?, ?)
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			namespaceId,
			"scope-$id",
			"external-$id",
			metadata,
			Timestamp.from(createdAt),
			Timestamp.from(updatedAt),
		)
	}
}
