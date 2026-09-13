package com.plot.api.workspace

import com.plot.api.persistence.generated.tables.WorkspaceMembers.Companion.WORKSPACE_MEMBERS
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository

@Repository
class WorkspaceMemberRepository(
	private val dsl: DSLContext,
) {
	fun findById(id: UUID): Optional<WorkspaceMember> = Optional.ofNullable(select()
		.where(WORKSPACE_MEMBERS.ID.eq(id))
		.fetchOne()
		?.toModel())

	fun findByWorkspaceIdAndUserId(workspaceId: UUID, userId: UUID): WorkspaceMember? = select()
		.where(
			WORKSPACE_MEMBERS.WORKSPACE_ID.eq(workspaceId),
			WORKSPACE_MEMBERS.USER_ID.eq(userId),
		)
		.fetchOne()
		?.toModel()

	fun findByWorkspaceIdAndUserIdAndStatus(workspaceId: UUID, userId: UUID, status: String): WorkspaceMember? = select()
		.where(
			WORKSPACE_MEMBERS.WORKSPACE_ID.eq(workspaceId),
			WORKSPACE_MEMBERS.USER_ID.eq(userId),
			WORKSPACE_MEMBERS.STATUS.eq(status),
		)
		.fetchOne()
		?.toModel()

	fun findAllByUserIdAndStatusOrderByCreatedAtAsc(userId: UUID, status: String): List<WorkspaceMember> = select()
		.where(
			WORKSPACE_MEMBERS.USER_ID.eq(userId),
			WORKSPACE_MEMBERS.STATUS.eq(status),
		)
		.orderBy(WORKSPACE_MEMBERS.CREATED_AT.asc())
		.fetch()
		.map { it.toModel() }

	fun countByUserIdAndStatus(userId: UUID, status: String): Int = dsl.fetchCount(
		select().where(
			WORKSPACE_MEMBERS.USER_ID.eq(userId),
			WORKSPACE_MEMBERS.STATUS.eq(status),
		),
	)

	fun save(member: WorkspaceMember): WorkspaceMember {
		val updated = dsl.update(WORKSPACE_MEMBERS)
			.set(WORKSPACE_MEMBERS.WORKSPACE_ID, member.workspaceId)
			.set(WORKSPACE_MEMBERS.USER_ID, member.userId)
			.set(WORKSPACE_MEMBERS.ROLE, member.role)
			.set(WORKSPACE_MEMBERS.STATUS, member.status)
			.set(WORKSPACE_MEMBERS.JOINED_AT, member.joinedAt.toOffsetDateTime())
			.set(WORKSPACE_MEMBERS.CREATED_AT, member.createdAt.toOffsetDateTime())
			.set(WORKSPACE_MEMBERS.UPDATED_AT, member.updatedAt.toOffsetDateTime())
			.set(WORKSPACE_MEMBERS.WORKOS_MEMBERSHIP_ID, member.workOSMembershipId)
			.where(WORKSPACE_MEMBERS.ID.eq(member.id))
			.execute()
		if (updated == 0) {
			dsl.insertInto(WORKSPACE_MEMBERS)
				.set(WORKSPACE_MEMBERS.ID, member.id)
				.set(WORKSPACE_MEMBERS.WORKSPACE_ID, member.workspaceId)
				.set(WORKSPACE_MEMBERS.USER_ID, member.userId)
				.set(WORKSPACE_MEMBERS.ROLE, member.role)
				.set(WORKSPACE_MEMBERS.STATUS, member.status)
				.set(WORKSPACE_MEMBERS.JOINED_AT, member.joinedAt.toOffsetDateTime())
				.set(WORKSPACE_MEMBERS.CREATED_AT, member.createdAt.toOffsetDateTime())
				.set(WORKSPACE_MEMBERS.UPDATED_AT, member.updatedAt.toOffsetDateTime())
				.set(WORKSPACE_MEMBERS.WORKOS_MEMBERSHIP_ID, member.workOSMembershipId)
				.execute()
		}
		return member
	}

	fun upsertWorkOSProjection(
		workspaceId: UUID,
		userId: UUID,
		role: String,
		status: String,
		workOSMembershipId: String?,
		now: Instant,
	): WorkspaceMember {
		val existing = findByWorkspaceIdAndUserId(workspaceId, userId)
		val id = existing?.id ?: UUID.randomUUID()
		val joinedAt = existing?.joinedAt ?: now
		val createdAt = existing?.createdAt ?: now
		val membershipId = workOSMembershipId ?: existing?.workOSMembershipId
		dsl.insertInto(WORKSPACE_MEMBERS)
			.set(WORKSPACE_MEMBERS.ID, id)
			.set(WORKSPACE_MEMBERS.WORKSPACE_ID, workspaceId)
			.set(WORKSPACE_MEMBERS.USER_ID, userId)
			.set(WORKSPACE_MEMBERS.ROLE, role)
			.set(WORKSPACE_MEMBERS.STATUS, status)
			.set(WORKSPACE_MEMBERS.JOINED_AT, joinedAt.toOffsetDateTime())
			.set(WORKSPACE_MEMBERS.CREATED_AT, createdAt.toOffsetDateTime())
			.set(WORKSPACE_MEMBERS.UPDATED_AT, now.toOffsetDateTime())
			.set(WORKSPACE_MEMBERS.WORKOS_MEMBERSHIP_ID, membershipId)
			.onConflict(WORKSPACE_MEMBERS.WORKSPACE_ID, WORKSPACE_MEMBERS.USER_ID)
			.doUpdate()
			.set(WORKSPACE_MEMBERS.ROLE, role)
			.set(WORKSPACE_MEMBERS.STATUS, status)
			.set(WORKSPACE_MEMBERS.UPDATED_AT, now.toOffsetDateTime())
			.set(WORKSPACE_MEMBERS.WORKOS_MEMBERSHIP_ID, membershipId)
			.execute()
		return findByWorkspaceIdAndUserId(workspaceId, userId)
			?: error("WorkOS membership projection could not be read after upsert")
	}

	fun delete(member: WorkspaceMember) {
		dsl.deleteFrom(WORKSPACE_MEMBERS).where(WORKSPACE_MEMBERS.ID.eq(member.id)).execute()
	}

	private fun select() = dsl.select(
		WORKSPACE_MEMBERS.ID,
		WORKSPACE_MEMBERS.WORKSPACE_ID,
		WORKSPACE_MEMBERS.USER_ID,
		WORKSPACE_MEMBERS.ROLE,
		WORKSPACE_MEMBERS.STATUS,
		WORKSPACE_MEMBERS.JOINED_AT,
		WORKSPACE_MEMBERS.CREATED_AT,
		WORKSPACE_MEMBERS.UPDATED_AT,
		WORKSPACE_MEMBERS.WORKOS_MEMBERSHIP_ID,
	).from(WORKSPACE_MEMBERS)

	private fun Record.toModel() = WorkspaceMember(
		id = requireNotNull(get(WORKSPACE_MEMBERS.ID)),
		workspaceId = requireNotNull(get(WORKSPACE_MEMBERS.WORKSPACE_ID)),
		userId = requireNotNull(get(WORKSPACE_MEMBERS.USER_ID)),
		role = requireNotNull(get(WORKSPACE_MEMBERS.ROLE)),
		status = requireNotNull(get(WORKSPACE_MEMBERS.STATUS)),
		joinedAt = requireNotNull(get(WORKSPACE_MEMBERS.JOINED_AT)).toInstant(),
		createdAt = requireNotNull(get(WORKSPACE_MEMBERS.CREATED_AT)).toInstant(),
		updatedAt = requireNotNull(get(WORKSPACE_MEMBERS.UPDATED_AT)).toInstant(),
		workOSMembershipId = get(WORKSPACE_MEMBERS.WORKOS_MEMBERSHIP_ID),
	)
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(java.time.ZoneOffset.UTC)
