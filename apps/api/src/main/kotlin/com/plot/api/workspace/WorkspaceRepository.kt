package com.plot.api.workspace

import com.plot.api.persistence.generated.tables.Workspaces.Companion.WORKSPACES
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository

@Repository
class WorkspaceRepository(
	private val dsl: DSLContext,
) {
	fun findById(id: UUID): Optional<Workspace> = Optional.ofNullable(select()
		.where(WORKSPACES.ID.eq(id))
		.fetchOne()
		?.toModel())

	fun findByIdAndStatus(id: UUID, status: String): Workspace? = select()
		.where(
			WORKSPACES.ID.eq(id),
			WORKSPACES.STATUS.eq(status),
		)
		.fetchOne()
		?.toModel()

	fun findAllByIdInAndStatus(ids: Collection<UUID>, status: String): List<Workspace> {
		if (ids.isEmpty()) return emptyList()
		return select()
			.where(
				WORKSPACES.ID.`in`(ids),
				WORKSPACES.STATUS.eq(status),
			)
			.fetch()
			.map { it.toModel() }
	}

	fun findBySlug(slug: String): Workspace? = select()
		.where(WORKSPACES.SLUG.eq(slug))
		.fetchOne()
		?.toModel()

	fun findByPolarSubscriptionId(polarSubscriptionId: String): Workspace? = select()
		.where(WORKSPACES.POLAR_SUBSCRIPTION_ID.eq(polarSubscriptionId))
		.fetchOne()
		?.toModel()

	fun existsBySlugAndIdNot(slug: String, id: UUID): Boolean = dsl.fetchExists(
		dsl.selectOne()
			.from(WORKSPACES)
			.where(
				WORKSPACES.SLUG.eq(slug),
				WORKSPACES.ID.ne(id),
			),
	)

	fun save(workspace: Workspace): Workspace {
		val updated = dsl.update(WORKSPACES)
			.set(WORKSPACES.NAME, workspace.name)
			.set(WORKSPACES.SLUG, workspace.slug)
			.set(WORKSPACES.CREATED_BY_USER_ID, workspace.createdByUserId)
			.set(WORKSPACES.STATUS, workspace.status)
			.set(WORKSPACES.CREATED_AT, workspace.createdAt.toOffsetDateTime())
			.set(WORKSPACES.UPDATED_AT, workspace.updatedAt.toOffsetDateTime())
			.set(WORKSPACES.PLAN, workspace.plan)
			.set(WORKSPACES.POLAR_SUBSCRIPTION_ID, workspace.polarSubscriptionId)
			.set(WORKSPACES.POLAR_CUSTOMER_ID, workspace.polarCustomerId)
			.set(WORKSPACES.PLAN_UPDATED_AT, workspace.planUpdatedAt?.toOffsetDateTime())
			.set(WORKSPACES.ENTITLEMENT_STATUS, workspace.entitlementStatus)
			.set(WORKSPACES.ACCESS_MODE, workspace.accessMode)
			.set(WORKSPACES.TRIAL_STARTED_AT, workspace.trialStartedAt.toOffsetDateTime())
			.set(WORKSPACES.TRIAL_ENDS_AT, workspace.trialEndsAt.toOffsetDateTime())
			.set(WORKSPACES.LOGO_URL, workspace.logoUrl)
			.where(WORKSPACES.ID.eq(workspace.id))
			.execute()
		if (updated == 0) {
			dsl.insertInto(WORKSPACES)
				.set(WORKSPACES.ID, workspace.id)
				.set(WORKSPACES.NAME, workspace.name)
				.set(WORKSPACES.SLUG, workspace.slug)
				.set(WORKSPACES.CREATED_BY_USER_ID, workspace.createdByUserId)
				.set(WORKSPACES.STATUS, workspace.status)
				.set(WORKSPACES.CREATED_AT, workspace.createdAt.toOffsetDateTime())
				.set(WORKSPACES.UPDATED_AT, workspace.updatedAt.toOffsetDateTime())
				.set(WORKSPACES.PLAN, workspace.plan)
				.set(WORKSPACES.POLAR_SUBSCRIPTION_ID, workspace.polarSubscriptionId)
				.set(WORKSPACES.POLAR_CUSTOMER_ID, workspace.polarCustomerId)
				.set(WORKSPACES.PLAN_UPDATED_AT, workspace.planUpdatedAt?.toOffsetDateTime())
				.set(WORKSPACES.ENTITLEMENT_STATUS, workspace.entitlementStatus)
				.set(WORKSPACES.ACCESS_MODE, workspace.accessMode)
				.set(WORKSPACES.TRIAL_STARTED_AT, workspace.trialStartedAt.toOffsetDateTime())
				.set(WORKSPACES.TRIAL_ENDS_AT, workspace.trialEndsAt.toOffsetDateTime())
				.set(WORKSPACES.LOGO_URL, workspace.logoUrl)
				.execute()
		}
		return workspace
	}

	private fun select() = dsl.select(
		WORKSPACES.ID,
		WORKSPACES.NAME,
		WORKSPACES.SLUG,
		WORKSPACES.CREATED_BY_USER_ID,
		WORKSPACES.STATUS,
		WORKSPACES.CREATED_AT,
		WORKSPACES.UPDATED_AT,
		WORKSPACES.PLAN,
		WORKSPACES.POLAR_SUBSCRIPTION_ID,
		WORKSPACES.POLAR_CUSTOMER_ID,
		WORKSPACES.PLAN_UPDATED_AT,
		WORKSPACES.ENTITLEMENT_STATUS,
		WORKSPACES.ACCESS_MODE,
		WORKSPACES.TRIAL_STARTED_AT,
		WORKSPACES.TRIAL_ENDS_AT,
		WORKSPACES.LOGO_URL,
	).from(WORKSPACES)

	private fun Record.toModel() = Workspace(
		id = requireNotNull(get(WORKSPACES.ID)),
		name = requireNotNull(get(WORKSPACES.NAME)),
		slug = requireNotNull(get(WORKSPACES.SLUG)),
		createdByUserId = get(WORKSPACES.CREATED_BY_USER_ID),
		status = requireNotNull(get(WORKSPACES.STATUS)),
		createdAt = requireNotNull(get(WORKSPACES.CREATED_AT)).toInstant(),
		updatedAt = requireNotNull(get(WORKSPACES.UPDATED_AT)).toInstant(),
		logoUrl = get(WORKSPACES.LOGO_URL),
		plan = requireNotNull(get(WORKSPACES.PLAN)),
		polarSubscriptionId = get(WORKSPACES.POLAR_SUBSCRIPTION_ID),
		polarCustomerId = get(WORKSPACES.POLAR_CUSTOMER_ID),
		planUpdatedAt = get(WORKSPACES.PLAN_UPDATED_AT)?.toInstant(),
		entitlementStatus = requireNotNull(get(WORKSPACES.ENTITLEMENT_STATUS)),
		accessMode = requireNotNull(get(WORKSPACES.ACCESS_MODE)),
		trialStartedAt = requireNotNull(get(WORKSPACES.TRIAL_STARTED_AT)).toInstant(),
		trialEndsAt = requireNotNull(get(WORKSPACES.TRIAL_ENDS_AT)).toInstant(),
	)
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(java.time.ZoneOffset.UTC)
