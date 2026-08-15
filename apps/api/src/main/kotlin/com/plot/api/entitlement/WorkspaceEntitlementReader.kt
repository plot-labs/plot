package com.plot.api.entitlement

import com.plot.api.persistence.generated.tables.ContentPacks.Companion.CONTENT_PACKS
import com.plot.api.workspace.Workspace
import java.time.Clock
import org.jooq.DSLContext
import org.springframework.stereotype.Component

data class EffectiveWorkspaceEntitlement(
	val status: String,
	val accessMode: String,
)

@Component
class WorkspaceEntitlementReader(
	private val dsl: DSLContext,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun resolve(workspace: Workspace): EffectiveWorkspaceEntitlement {
		if (workspace.entitlementStatus != "trialing") return workspace.currentEntitlement()
		if (!workspace.trialEndsAt.isAfter(clock.instant())) return EXPIRED
		val successfulPackCount = dsl
			.selectCount()
			.from(CONTENT_PACKS)
			.where(CONTENT_PACKS.WORKSPACE_ID.eq(workspace.id))
			.fetchOne(0, Long::class.java) ?: 0
		return if (successfulPackCount >= TrialPolicy.PACK_LIMIT) EXPIRED else workspace.currentEntitlement()
	}

	private fun Workspace.currentEntitlement() =
		EffectiveWorkspaceEntitlement(entitlementStatus, accessMode)

	private companion object {
		val EXPIRED = EffectiveWorkspaceEntitlement("expired", "read_only")
	}
}
