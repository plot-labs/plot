package com.plot.api

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ApiApplicationTests {

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	fun contextStartsAndFlywayCreatesCoreTables() {
		val expectedTables = setOf(
			"users",
			"workspaces",
			"workspace_members",
			"work_sessions",
			"tasks",
			"writing_blocks",
			"github_installation_states",
			"connections",
			"source_namespaces",
			"connection_namespace_bindings",
			"source_scopes",
			"source_observations",
			"source_imports",
			"writing_block_scopes",
			"writing_block_fragments",
			"writing_block_relations",
			"writing_block_relation_observations",
			"generation_runs",
			"generation_inputs",
			"generation_workflow_steps",
			"model_invocations",
			"generation_artifacts",
			"content_packs",
			"content_variants",
			"content_variant_sentences",
			"content_variant_sentence_revisions",
			"sentence_evaluations",
			"sentence_citations",
			"generation_interventions",
			"generation_intervention_resolutions",
			"generation_export_events",
		)
		val actualTables = jdbcTemplate.queryForList(
			"""
			select table_name
			from information_schema.tables
			where table_schema = 'public'
			  and table_type = 'BASE TABLE'
			  and table_name <> 'flyway_schema_history'
			order by table_name
			""".trimIndent(),
			String::class.java,
		).toSet()

		assertEquals(expectedTables, actualTables)

		listOf(
			"workspace_members",
			"work_sessions",
			"tasks",
			"writing_blocks",
			"github_installation_states",
			"connections",
			"source_namespaces",
			"connection_namespace_bindings",
			"source_scopes",
			"source_observations",
			"source_imports",
			"writing_block_scopes",
			"writing_block_fragments",
			"writing_block_relations",
			"writing_block_relation_observations",
		).forEach { tableName ->
			assertTrue(
				hasIndex(tableName, listOf("workspace_id", "id"), uniqueOnly = true),
				"$tableName must have a unique index on (workspace_id, id)",
			)
		}
		assertTrue(hasTasksWorkSessionForeignKey())
		assertTrue(hasIndex("tasks", listOf("workspace_id", "work_session_id")))
		assertTrue(
			hasIndex(
				"writing_blocks",
				listOf("workspace_id", "source_namespace_id", "source_kind", "external_object_key"),
				uniqueOnly = true,
			),
		)
		assertTrue(
			hasIndex("source_scopes", listOf("workspace_id", "source_namespace_id", "scope_kind", "external_scope_key"), uniqueOnly = true),
		)
		assertTrue(
			hasIndex("source_imports", listOf("workspace_id", "source_scope_id"), uniqueOnly = true),
		)
	}

	private fun hasTasksWorkSessionForeignKey(): Boolean {
		return jdbcTemplate.queryForObject(
			"""
			select exists (
			  select 1
			  from information_schema.table_constraints tc
			  join information_schema.referential_constraints rc
			    on rc.constraint_schema = tc.constraint_schema
			   and rc.constraint_name = tc.constraint_name
			  join information_schema.table_constraints referenced_tc
			    on referenced_tc.constraint_schema = rc.unique_constraint_schema
			   and referenced_tc.constraint_name = rc.unique_constraint_name
			  where tc.table_schema = 'public'
			    and tc.table_name = 'tasks'
			    and tc.constraint_type = 'FOREIGN KEY'
			    and referenced_tc.table_schema = 'public'
			    and referenced_tc.table_name = 'work_sessions'
			    and (
			      select array_agg(kcu.column_name::text order by kcu.ordinal_position)
			      from information_schema.key_column_usage kcu
			      where kcu.constraint_schema = tc.constraint_schema
			        and kcu.constraint_name = tc.constraint_name
			        and kcu.table_schema = tc.table_schema
			        and kcu.table_name = tc.table_name
			    ) = array['workspace_id', 'work_session_id']
			)
			""".trimIndent(),
			Boolean::class.java,
		) ?: false
	}

	private fun hasIndex(tableName: String, columnNames: List<String>, uniqueOnly: Boolean = false): Boolean {
		val uniqueFilter = if (uniqueOnly) "and i.indisunique" else ""

		return jdbcTemplate.queryForObject(
			"""
			select exists (
			  select 1
			  from pg_index i
			  join pg_class t on t.oid = i.indrelid
			  join pg_namespace n on n.oid = t.relnamespace
			  where n.nspname = 'public'
			    and t.relname = ?
			    $uniqueFilter
			    and (
			      select array_agg(a.attname::text order by indexed_column.ordinality)
			      from unnest(i.indkey) with ordinality as indexed_column(attnum, ordinality)
			      join pg_attribute a
			        on a.attrelid = t.oid
			       and a.attnum = indexed_column.attnum
			    ) = ${columnArray(columnNames)}
			)
			""".trimIndent(),
			Boolean::class.java,
			tableName,
		) ?: false
	}

	private fun columnArray(columnNames: List<String>): String {
		return columnNames.joinToString(prefix = "array[", postfix = "]") { "'$it'" }
	}
}
