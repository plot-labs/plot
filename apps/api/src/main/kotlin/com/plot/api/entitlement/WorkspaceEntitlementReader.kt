package com.plot.api.entitlement

import com.plot.api.persistence.generated.tables.ContentPacks.Companion.CONTENT_PACKS
import com.plot.api.workspace.Workspace
import java.time.Clock
import org.jooq.DSLContext
import org.springframework.stereotype.Component

data class WorkspaceCapabilities(
	val generate: Boolean,
	val edit: Boolean,
	val publish: Boolean,
	val export: Boolean,
	val configure: Boolean,
	val unpublish: Boolean,
) {
	companion object {
		fun forAccessMode(accessMode: String) = when (accessMode) {
			"full" -> WorkspaceCapabilities(
				generate = true,
				edit = true,
				publish = true,
				export = true,
				configure = true,
				unpublish = true,
			)
			"complete_only" -> WorkspaceCapabilities(
				generate = false,
				edit = true,
				publish = true,
				export = true,
				configure = false,
				unpublish = true,
			)
			else -> WorkspaceCapabilities(
				generate = false,
				edit = false,
				publish = false,
				export = true,
				configure = false,
				unpublish = true,
			)
		}
	}
}

data class EffectiveWorkspaceEntitlement(
	val status: String,
	val accessMode: String,
	val capabilities: WorkspaceCapabilities = WorkspaceCapabilities.forAccessMode(accessMode),
)

@Component
class WorkspaceEntitlementReader(
	private val dsl: DSLContext,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun resolve(workspace: Workspace): EffectiveWorkspaceEntitlement {
		if (workspace.entitlementStatus == "revoked") return workspace.currentEntitlement()
		if (workspace.plan != "trial" && workspace.entitlementStatus != "trialing") {
			return workspace.currentEntitlement()
		}
		if (!workspace.trialEndsAt.isAfter(clock.instant())) return EXPIRED
		val successfulPackCount = dsl
			.selectCount()
			.from(CONTENT_PACKS)
			.where(CONTENT_PACKS.WORKSPACE_ID.eq(workspace.id))
			.fetchOne(0, Long::class.java) ?: 0
		return if (successfulPackCount >= TrialPolicy.PACK_LIMIT) COMPLETE_ONLY else TRIALING
	}

	private fun Workspace.currentEntitlement() =
		EffectiveWorkspaceEntitlement(entitlementStatus, accessMode)

	private companion object {
		val TRIALING = EffectiveWorkspaceEntitlement("trialing", "full")
		val COMPLETE_ONLY = EffectiveWorkspaceEntitlement("trialing", "complete_only")
		val EXPIRED = EffectiveWorkspaceEntitlement("expired", "read_only")
	}
}
