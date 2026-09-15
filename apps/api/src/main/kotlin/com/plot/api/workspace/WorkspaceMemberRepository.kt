package com.plot.api.workspace

import com.plot.api.persistence.ExposedSqlExecutor
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class WorkspaceMemberRepository(
	private val sql: ExposedSqlExecutor,
) {
	@Transactional(readOnly = true)
	fun findById(id: UUID): Optional<WorkspaceMember> = Optional.ofNullable(sql.execute {
		WorkspaceMemberTable.selectAll().where { WorkspaceMemberTable.id eq id }.singleOrNull()?.toModel()
	})

	@Transactional(readOnly = true)
	fun findByWorkspaceIdAndUserId(workspaceId: UUID, userId: UUID): WorkspaceMember? = sql.execute {
		findByWorkspaceAndUser(workspaceId, userId)
	}

	@Transactional(readOnly = true)
	fun findByWorkspaceIdAndUserIdAndStatus(
		workspaceId: UUID,
		userId: UUID,
		status: String,
	): WorkspaceMember? = sql.execute {
		WorkspaceMemberTable.selectAll().where {
			(WorkspaceMemberTable.workspaceId eq workspaceId) and
				(WorkspaceMemberTable.userId eq userId) and
				(WorkspaceMemberTable.status eq status)
		}.singleOrNull()?.toModel()
	}

	@Transactional(readOnly = true)
	fun findAllByUserIdAndStatusOrderByCreatedAtAsc(userId: UUID, status: String): List<WorkspaceMember> = sql.execute {
		WorkspaceMemberTable.selectAll().where {
			(WorkspaceMemberTable.userId eq userId) and (WorkspaceMemberTable.status eq status)
		}.orderBy(WorkspaceMemberTable.createdAt to SortOrder.ASC).map { it.toModel() }
	}

	@Transactional(readOnly = true)
	fun countByUserIdAndStatus(userId: UUID, status: String): Int = sql.execute {
		WorkspaceMemberTable.selectAll().where {
			(WorkspaceMemberTable.userId eq userId) and (WorkspaceMemberTable.status eq status)
		}.count().toInt()
	}

	@Transactional
	fun save(member: WorkspaceMember): WorkspaceMember = sql.execute {
		val updated = WorkspaceMemberTable.update({ WorkspaceMemberTable.id eq member.id }) {
			it.copyFrom(member)
		}
		if (updated == 0) {
			WorkspaceMemberTable.insert {
				it[id] = member.id
				it.copyFrom(member)
			}
		}
		member
	}

	@Transactional
	fun upsertWorkOSProjection(
		workspaceId: UUID,
		userId: UUID,
		role: String,
		status: String,
		workOSMembershipId: String?,
		now: Instant,
	): WorkspaceMember = sql.execute {
		val existing = findByWorkspaceAndUser(workspaceId, userId)
		val membershipId = workOSMembershipId ?: existing?.workOSMembershipId
		WorkspaceMemberTable.upsert(
			WorkspaceMemberTable.workspaceId,
			WorkspaceMemberTable.userId,
			onUpdate = {
				it[WorkspaceMemberTable.role] = role
				it[WorkspaceMemberTable.status] = status
				it[WorkspaceMemberTable.updatedAt] = now.atOffset(ZoneOffset.UTC)
				it[WorkspaceMemberTable.workOSMembershipId] = membershipId
			},
		) {
			it[id] = existing?.id ?: UUID.randomUUID()
			it[WorkspaceMemberTable.workspaceId] = workspaceId
			it[WorkspaceMemberTable.userId] = userId
			it[WorkspaceMemberTable.role] = role
			it[WorkspaceMemberTable.status] = status
			it[joinedAt] = (existing?.joinedAt ?: now).atOffset(ZoneOffset.UTC)
			it[createdAt] = (existing?.createdAt ?: now).atOffset(ZoneOffset.UTC)
			it[updatedAt] = now.atOffset(ZoneOffset.UTC)
			it[WorkspaceMemberTable.workOSMembershipId] = membershipId
		}
		findByWorkspaceAndUser(workspaceId, userId)
			?: error("WorkOS membership projection could not be read after upsert")
	}

	@Transactional
	fun delete(member: WorkspaceMember) {
		sql.execute {
			WorkspaceMemberTable.deleteWhere { WorkspaceMemberTable.id eq member.id }
		}
	}

	private fun findByWorkspaceAndUser(workspaceId: UUID, userId: UUID): WorkspaceMember? =
		WorkspaceMemberTable.selectAll().where {
			(WorkspaceMemberTable.workspaceId eq workspaceId) and (WorkspaceMemberTable.userId eq userId)
		}.singleOrNull()?.toModel()

	private fun UpdateBuilder<*>.copyFrom(member: WorkspaceMember) {
		this[WorkspaceMemberTable.workspaceId] = member.workspaceId
		this[WorkspaceMemberTable.userId] = member.userId
		this[WorkspaceMemberTable.role] = member.role
		this[WorkspaceMemberTable.status] = member.status
		this[WorkspaceMemberTable.joinedAt] = member.joinedAt.atOffset(ZoneOffset.UTC)
		this[WorkspaceMemberTable.createdAt] = member.createdAt.atOffset(ZoneOffset.UTC)
		this[WorkspaceMemberTable.updatedAt] = member.updatedAt.atOffset(ZoneOffset.UTC)
		this[WorkspaceMemberTable.workOSMembershipId] = member.workOSMembershipId
	}

	private fun ResultRow.toModel() = WorkspaceMember(
		id = this[WorkspaceMemberTable.id],
		workspaceId = this[WorkspaceMemberTable.workspaceId],
		userId = this[WorkspaceMemberTable.userId],
		role = this[WorkspaceMemberTable.role],
		status = this[WorkspaceMemberTable.status],
		joinedAt = this[WorkspaceMemberTable.joinedAt].toInstant(),
		createdAt = this[WorkspaceMemberTable.createdAt].toInstant(),
		updatedAt = this[WorkspaceMemberTable.updatedAt].toInstant(),
		workOSMembershipId = this[WorkspaceMemberTable.workOSMembershipId],
	)
}
