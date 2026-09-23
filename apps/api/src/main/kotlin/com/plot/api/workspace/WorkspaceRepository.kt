package com.plot.api.workspace

import com.plot.api.persistence.ExposedSqlExecutor
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class WorkspaceRepository(
	private val sql: ExposedSqlExecutor,
) {
	@Transactional(readOnly = true)
	fun findById(id: UUID): Optional<Workspace> = Optional.ofNullable(sql.execute {
		WorkspaceTable.selectAll().where { WorkspaceTable.id eq id }.singleOrNull()?.toModel()
	})

	@Transactional(readOnly = true)
	fun findByIdAndStatus(id: UUID, status: String): Workspace? = sql.execute {
		WorkspaceTable.selectAll().where {
			(WorkspaceTable.id eq id) and (WorkspaceTable.status eq status)
		}.singleOrNull()?.toModel()
	}

	@Transactional(readOnly = true)
	fun findAllByIdInAndStatus(ids: Collection<UUID>, status: String): List<Workspace> {
		if (ids.isEmpty()) return emptyList()
		return sql.execute {
			WorkspaceTable.selectAll().where {
				(WorkspaceTable.id inList ids) and (WorkspaceTable.status eq status)
			}.map { it.toModel() }
		}
	}

	@Transactional(readOnly = true)
	fun findBySlug(slug: String): Workspace? = sql.execute {
		WorkspaceTable.selectAll().where { WorkspaceTable.slug eq slug }.singleOrNull()?.toModel()
	}

	@Transactional(readOnly = true)
	fun findByPolarSubscriptionId(polarSubscriptionId: String): Workspace? = sql.execute {
		WorkspaceTable.selectAll().where {
			WorkspaceTable.polarSubscriptionId eq polarSubscriptionId
		}.singleOrNull()?.toModel()
	}

	@Transactional(readOnly = true)
	fun findByPolarCustomerId(polarCustomerId: String): Workspace? = sql.execute {
		WorkspaceTable.selectAll().where {
			(WorkspaceTable.polarCustomerId eq polarCustomerId) and (WorkspaceTable.status eq "ACTIVE")
		}.limit(2).map { it.toModel() }.singleOrNull()
	}

	@Transactional
	fun save(workspace: Workspace): Workspace = sql.execute {
		val updated = WorkspaceTable.update({ WorkspaceTable.id eq workspace.id }) {
			it.copyFrom(workspace)
		}
		if (updated == 0) {
			WorkspaceTable.insert {
				it[id] = workspace.id
				it.copyFrom(workspace)
			}
		}
		workspace
	}

	private fun UpdateBuilder<*>.copyFrom(workspace: Workspace) {
		this[WorkspaceTable.name] = workspace.name
		this[WorkspaceTable.slug] = workspace.slug
		this[WorkspaceTable.createdByUserId] = workspace.createdByUserId
		this[WorkspaceTable.status] = workspace.status
		this[WorkspaceTable.createdAt] = workspace.createdAt.atOffset(ZoneOffset.UTC)
		this[WorkspaceTable.updatedAt] = workspace.updatedAt.atOffset(ZoneOffset.UTC)
		this[WorkspaceTable.plan] = workspace.plan
		this[WorkspaceTable.polarSubscriptionId] = workspace.polarSubscriptionId
		this[WorkspaceTable.polarCustomerId] = workspace.polarCustomerId
		this[WorkspaceTable.polarSubscriptionStatus] = workspace.polarSubscriptionStatus
		this[WorkspaceTable.polarSubscriptionCancelAtPeriodEnd] = workspace.polarSubscriptionCancelAtPeriodEnd
		this[WorkspaceTable.polarSubscriptionCurrentPeriodEnd] = workspace.polarSubscriptionCurrentPeriodEnd?.atOffset(ZoneOffset.UTC)
		this[WorkspaceTable.polarSubscriptionEventAt] = workspace.polarSubscriptionEventAt?.atOffset(ZoneOffset.UTC)
		this[WorkspaceTable.planUpdatedAt] = workspace.planUpdatedAt?.atOffset(ZoneOffset.UTC)
		this[WorkspaceTable.entitlementStatus] = workspace.entitlementStatus
		this[WorkspaceTable.accessMode] = workspace.accessMode
		this[WorkspaceTable.trialStartedAt] = workspace.trialStartedAt.atOffset(ZoneOffset.UTC)
		this[WorkspaceTable.trialEndsAt] = workspace.trialEndsAt.atOffset(ZoneOffset.UTC)
		this[WorkspaceTable.logoUrl] = workspace.logoUrl
		this[WorkspaceTable.publicCitationsEnabled] = workspace.publicCitationsEnabled
	}

	private fun ResultRow.toModel() = Workspace(
		id = this[WorkspaceTable.id],
		name = this[WorkspaceTable.name],
		slug = this[WorkspaceTable.slug],
		createdByUserId = this[WorkspaceTable.createdByUserId],
		status = this[WorkspaceTable.status],
		createdAt = this[WorkspaceTable.createdAt].toInstant(),
		updatedAt = this[WorkspaceTable.updatedAt].toInstant(),
		logoUrl = this[WorkspaceTable.logoUrl],
		plan = this[WorkspaceTable.plan],
		polarSubscriptionId = this[WorkspaceTable.polarSubscriptionId],
		polarCustomerId = this[WorkspaceTable.polarCustomerId],
		polarSubscriptionStatus = this[WorkspaceTable.polarSubscriptionStatus],
		polarSubscriptionCancelAtPeriodEnd = this[WorkspaceTable.polarSubscriptionCancelAtPeriodEnd],
		polarSubscriptionCurrentPeriodEnd = this[WorkspaceTable.polarSubscriptionCurrentPeriodEnd]?.toInstant(),
		polarSubscriptionEventAt = this[WorkspaceTable.polarSubscriptionEventAt]?.toInstant(),
		planUpdatedAt = this[WorkspaceTable.planUpdatedAt]?.toInstant(),
		entitlementStatus = this[WorkspaceTable.entitlementStatus],
		accessMode = this[WorkspaceTable.accessMode],
		trialStartedAt = this[WorkspaceTable.trialStartedAt].toInstant(),
		trialEndsAt = this[WorkspaceTable.trialEndsAt].toInstant(),
		publicCitationsEnabled = this[WorkspaceTable.publicCitationsEnabled],
	)
}
