package com.plot.api.entitlement

/**
 * Workspace creation stays @ReadOnlyAllowed so churned-down users can still
 * start over; the cap prevents unbounded workspace creation for one account.
 */
object WorkspacePolicy {
	const val MAX_ACTIVE_PER_USER = 3
}
