package com.plot.api.workspace

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object UserTable : Table("users") {
	val id = javaUUID("id")
	val email = text("email")
	val displayName = text("display_name")
	val status = varchar("status", 255)
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")

	override val primaryKey = PrimaryKey(id)
}

internal object WorkspaceTable : Table("workspaces") {
	val id = javaUUID("id")
	val name = text("name")
	val slug = text("slug")
	val createdByUserId = javaUUID("created_by_user_id").nullable()
	val status = varchar("status", 255)
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	val plan = varchar("plan", 255)
	val polarSubscriptionId = text("polar_subscription_id").nullable()
	val polarCustomerId = text("polar_customer_id").nullable()
	val planUpdatedAt = timestampWithTimeZone("plan_updated_at").nullable()
	val entitlementStatus = varchar("entitlement_status", 255)
	val accessMode = varchar("access_mode", 255)
	val trialStartedAt = timestampWithTimeZone("trial_started_at")
	val trialEndsAt = timestampWithTimeZone("trial_ends_at")
	val logoUrl = text("logo_url").nullable()
	val publicCitationsEnabled = bool("public_citations_enabled")

	override val primaryKey = PrimaryKey(id)
}

internal object WorkspaceMemberTable : Table("workspace_members") {
	val id = javaUUID("id")
	val workspaceId = javaUUID("workspace_id")
	val userId = javaUUID("user_id")
	val role = varchar("role", 255)
	val status = varchar("status", 255)
	val joinedAt = timestampWithTimeZone("joined_at")
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	val workOSMembershipId = text("workos_membership_id").nullable()

	override val primaryKey = PrimaryKey(id)
}
