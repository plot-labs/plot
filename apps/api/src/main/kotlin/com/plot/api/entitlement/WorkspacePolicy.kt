package com.plot.api.entitlement

/**
 * Workspace creation stays @ReadOnlyAllowed so churned-down users can still
 * start over; this per-user cap keeps fresh-trial creation from being farmed.
 */
object WorkspacePolicy {
	const val MAX_ACTIVE_PER_USER = 3
}
