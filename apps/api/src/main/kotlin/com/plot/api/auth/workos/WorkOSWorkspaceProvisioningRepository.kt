package com.plot.api.auth.workos

import com.plot.api.persistence.SqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository

data class WorkOSWorkspaceProvisioningRecord(
	val workOSOrganizationId: String,
	val workOSUserId: String,
	val workspaceId: UUID?,
	val idempotencyKey: String,
	val state: String,
)

@Repository
class WorkOSWorkspaceProvisioningRepository(
	private val sql: SqlExecutor,
) {
	fun startOrResume(
		workOSOrganizationId: String,
		workOSUserId: String,
		workspaceId: UUID,
		idempotencyKey: String,
		now: Instant,
	): WorkOSWorkspaceProvisioningRecord {
		sql.update(
			"""
			insert into workos_workspace_provisioning (
			  workos_organization_id, workos_user_id, workspace_id, idempotency_key,
			  state, created_at, updated_at
			) values (?, ?, ?, ?, 'PENDING', ?, ?)
			on conflict (idempotency_key) do nothing
			""".trimIndent(),
			workOSOrganizationId,
			workOSUserId,
			workspaceId,
			idempotencyKey,
			Timestamp.from(now),
			Timestamp.from(now),
		)
		return findByIdempotencyKey(idempotencyKey)
			?: throw IllegalStateException("WorkOS workspace provisioning ledger could not be read")
	}

	fun findByIdempotencyKey(idempotencyKey: String): WorkOSWorkspaceProvisioningRecord? = sql.queryForObject(
		BY_IDEMPOTENCY_KEY,
		::toModel,
		idempotencyKey,
	)

	fun findByOrganizationId(workOSOrganizationId: String): WorkOSWorkspaceProvisioningRecord? = sql.queryForObject(
		BY_ORGANIZATION_ID,
		::toModel,
		workOSOrganizationId,
	)

	fun markOrganizationReady(workOSOrganizationId: String, now: Instant) {
		sql.update(
			"""
			update workos_workspace_provisioning
			set state = 'ORGANIZATION_READY', updated_at = ?, last_error = null
			where workos_organization_id = ?
			""".trimIndent(),
			Timestamp.from(now), workOSOrganizationId,
		)
	}

	fun markCompleted(workOSOrganizationId: String, workspaceId: UUID, now: Instant) {
		sql.update(
			"""
			update workos_workspace_provisioning
			set state = 'COMPLETED', workspace_id = ?, updated_at = ?, last_error = null
			where workos_organization_id = ?
			""".trimIndent(),
			workspaceId, Timestamp.from(now), workOSOrganizationId,
		)
	}

	fun markFailed(workOSOrganizationId: String, failure: Throwable, now: Instant) {
		sql.update(
			"""
			update workos_workspace_provisioning
			set state = 'FAILED', updated_at = ?, last_error = ?
			where workos_organization_id = ?
			""".trimIndent(),
			Timestamp.from(now),
			failure.safeMessage(),
			workOSOrganizationId,
		)
	}

	private fun toModel(row: com.plot.api.persistence.SqlRow, index: Int) = WorkOSWorkspaceProvisioningRecord(
		workOSOrganizationId = requireNotNull(row.getString("workos_organization_id")),
		workOSUserId = requireNotNull(row.getString("workos_user_id")),
		workspaceId = row.getObject("workspace_id", UUID::class.java),
		idempotencyKey = requireNotNull(row.getString("idempotency_key")),
		state = requireNotNull(row.getString("state")),
	)

	private companion object {
		const val BY_IDEMPOTENCY_KEY = """
			select workos_organization_id, workos_user_id, workspace_id, idempotency_key, state
			from workos_workspace_provisioning where idempotency_key = ?
			"""
		const val BY_ORGANIZATION_ID = """
			select workos_organization_id, workos_user_id, workspace_id, idempotency_key, state
			from workos_workspace_provisioning where workos_organization_id = ?
			"""
	}
}

private fun Throwable.safeMessage(): String = when (this) {
	is WorkOSProviderException -> "WORKOS_PROVIDER_UNAVAILABLE"
	else -> "WORKSPACE_PROVISIONING_FAILED"
}
