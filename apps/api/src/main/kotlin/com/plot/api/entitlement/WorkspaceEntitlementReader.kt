package com.plot.api.entitlement

import com.plot.api.workspace.Workspace
import java.time.Clock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

data class EffectiveWorkspaceEntitlement(
	val status: String,
	val accessMode: String,
)

@Component
class WorkspaceEntitlementReader(
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun resolve(workspace: Workspace): EffectiveWorkspaceEntitlement {
		if (workspace.entitlementStatus != "trialing") return workspace.currentEntitlement()
		if (!workspace.trialEndsAt.isAfter(clock.instant())) return EXPIRED
		val successfulPackCount = jdbcTemplate.queryForObject(
			"select count(*) from content_packs where workspace_id = ?",
			Long::class.java,
			workspace.id,
		) ?: 0
		return if (successfulPackCount >= TrialPolicy.PACK_LIMIT) EXPIRED else workspace.currentEntitlement()
	}

	private fun Workspace.currentEntitlement() =
		EffectiveWorkspaceEntitlement(entitlementStatus, accessMode)

	private companion object {
		val EXPIRED = EffectiveWorkspaceEntitlement("expired", "read_only")
	}
}
