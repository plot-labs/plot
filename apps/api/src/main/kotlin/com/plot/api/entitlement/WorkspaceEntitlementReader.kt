package com.plot.api.entitlement

import com.plot.api.workspace.Workspace
import java.time.Clock
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

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
	private val clock: Clock = Clock.systemUTC(),
) {
	@Transactional(readOnly = true)
	fun resolve(workspace: Workspace): EffectiveWorkspaceEntitlement {
		if (workspace.entitlementStatus == "revoked") return workspace.currentEntitlement()
		if (workspace.plan != "trial" && workspace.entitlementStatus != "trialing") {
			return workspace.currentEntitlement()
		}
		if (!workspace.trialEndsAt.isAfter(clock.instant())) return EXPIRED
		return TRIALING
	}

	private fun Workspace.currentEntitlement() =
		EffectiveWorkspaceEntitlement(entitlementStatus, accessMode)

	private companion object {
		val TRIALING = EffectiveWorkspaceEntitlement("trialing", "full")
		val EXPIRED = EffectiveWorkspaceEntitlement("expired", "read_only")
	}
}
