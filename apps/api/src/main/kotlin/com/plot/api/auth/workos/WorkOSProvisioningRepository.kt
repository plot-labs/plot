package com.plot.api.auth.workos

import com.plot.api.persistence.JooqSqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository

enum class WorkOSProvisioningState {
	PENDING,
	ORGANIZATION_READY,
	COMPLETED,
	FAILED,
}

data class WorkOSProvisioningRecord(
	val workOSUserId: String,
	val plotUserId: UUID?,
	val workspaceId: UUID?,
	val workOSOrganizationId: String?,
	val idempotencyKey: String,
	val state: WorkOSProvisioningState,
)

@Repository
class WorkOSProvisioningRepository(
	private val sql: JooqSqlExecutor,
) {
	fun startOrResume(workOSUserId: String, idempotencyKey: String, now: Instant): WorkOSProvisioningRecord {
		sql.update(
			"""
			insert into workos_provisioning (
			  workos_user_id, idempotency_key, state, created_at, updated_at
			) values (?, ?, 'PENDING', ?, ?)
			on conflict (workos_user_id) do update set
			  idempotency_key = excluded.idempotency_key,
			  state = case
			    when workos_provisioning.state = 'COMPLETED' then workos_provisioning.state
			    else 'PENDING'
			  end,
			  last_error = case
			    when workos_provisioning.state = 'COMPLETED' then workos_provisioning.last_error
			    else null
			  end,
			  updated_at = excluded.updated_at
			""".trimIndent(),
			workOSUserId,
			idempotencyKey,
			Timestamp.from(now),
			Timestamp.from(now),
		)
		return findByWorkOSUserId(workOSUserId)
			?: error("WorkOS provisioning ledger row disappeared after insert")
	}

	fun findByWorkOSUserId(workOSUserId: String): WorkOSProvisioningRecord? = sql.queryForObject(
		SELECT,
		::toModel,
		workOSUserId,
	)

	fun markOrganizationReady(workOSUserId: String, organizationId: String, now: Instant) {
		sql.update(
			"""
			update workos_provisioning
			set workos_organization_id = ?,
			    state = case when state = 'COMPLETED' then state else 'ORGANIZATION_READY' end,
			    last_error = null,
			    updated_at = ?
			where workos_user_id = ?
			""".trimIndent(),
			organizationId,
			Timestamp.from(now),
			workOSUserId,
		)
	}

	fun markCompleted(workOSUserId: String, plotUserId: UUID, workspaceId: UUID, organizationId: String, now: Instant) {
		sql.update(
			"""
			update workos_provisioning
			set plot_user_id = ?,
			    workspace_id = ?,
			    workos_organization_id = ?,
			    state = 'COMPLETED',
			    last_error = null,
			    updated_at = ?
			where workos_user_id = ?
			""".trimIndent(),
			plotUserId,
			workspaceId,
			organizationId,
			Timestamp.from(now),
			workOSUserId,
		)
	}

	fun markFailed(workOSUserId: String, failure: Throwable, now: Instant) {
		val message = (failure.message ?: failure::class.java.simpleName).take(1000)
		sql.update(
			"""
			update workos_provisioning
			set state = 'FAILED', last_error = ?, updated_at = ?
			where workos_user_id = ?
			""".trimIndent(),
			message,
			Timestamp.from(now),
			workOSUserId,
		)
	}

	private fun toModel(row: com.plot.api.persistence.SqlRow, index: Int) = WorkOSProvisioningRecord(
		workOSUserId = requireNotNull(row.getString("workos_user_id")),
		plotUserId = row.getObject("plot_user_id", UUID::class.java),
		workspaceId = row.getObject("workspace_id", UUID::class.java),
		workOSOrganizationId = row.getString("workos_organization_id"),
		idempotencyKey = requireNotNull(row.getString("idempotency_key")),
		state = WorkOSProvisioningState.valueOf(requireNotNull(row.getString("state"))),
	)

	private companion object {
		const val SELECT = """
			select workos_user_id, plot_user_id, workspace_id,
			       workos_organization_id, idempotency_key, state
			from workos_provisioning
			where workos_user_id = ?
			"""
	}
}
