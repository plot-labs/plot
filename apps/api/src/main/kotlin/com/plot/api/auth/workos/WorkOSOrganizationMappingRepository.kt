package com.plot.api.auth.workos

import com.plot.api.persistence.SqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository

data class WorkOSOrganizationMapping(
	val workOSOrganizationId: String,
	val workOSUserId: String,
	val workspaceId: UUID,
)

@Repository
class WorkOSOrganizationMappingRepository(
	private val sql: SqlExecutor,
) {
	fun findByOrganizationId(workOSOrganizationId: String): WorkOSOrganizationMapping? = sql.queryForObject(
		BY_ORGANIZATION,
		::toModel,
		workOSOrganizationId,
	)

	fun findPersonalByWorkOSUserId(workOSUserId: String): WorkOSOrganizationMapping? = sql.queryForObject(
		BY_USER,
		::toModel,
		workOSUserId,
	)

	fun findByWorkspaceId(workspaceId: UUID): WorkOSOrganizationMapping? = sql.queryForObject(
		BY_WORKSPACE,
		::toModel,
		workspaceId,
	)

	fun save(mapping: WorkOSOrganizationMapping, now: Instant) {
		sql.update(
			"""
			insert into workos_organization_mappings (
			  workos_organization_id, workos_user_id, workspace_id, created_at, updated_at
			) values (?, ?, ?, ?, ?)
			on conflict (workos_organization_id) do update set
			  workos_user_id = excluded.workos_user_id,
			  workspace_id = excluded.workspace_id,
			  updated_at = excluded.updated_at
			where workos_organization_mappings.workspace_id = excluded.workspace_id
			""".trimIndent(),
			mapping.workOSOrganizationId,
			mapping.workOSUserId,
			mapping.workspaceId,
			Timestamp.from(now),
			Timestamp.from(now),
		)
	}

	private fun toModel(row: com.plot.api.persistence.SqlRow, index: Int) = WorkOSOrganizationMapping(
		workOSOrganizationId = requireNotNull(row.getString("workos_organization_id")),
		workOSUserId = requireNotNull(row.getString("workos_user_id")),
		workspaceId = requireNotNull(row.getObject("workspace_id", UUID::class.java)),
	)

	private companion object {
		const val BY_ORGANIZATION = """
			select workos_organization_id, workos_user_id, workspace_id
			from workos_organization_mappings
			where workos_organization_id = ?
			"""
		const val BY_USER = """
			select workos_organization_id, workos_user_id, workspace_id
			from workos_organization_mappings
			where workos_user_id = ?
			order by created_at asc
			limit 1
			"""
		const val BY_WORKSPACE = """
			select workos_organization_id, workos_user_id, workspace_id
			from workos_organization_mappings
			where workspace_id = ?
			"""
	}
}
