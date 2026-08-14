package com.plot.api.workspace

import java.time.Instant
import java.util.UUID

class Workspace(
	var id: UUID,
	var name: String,
	var slug: String,
	var createdByUserId: UUID?,
	var status: String,
	var createdAt: Instant,
	var updatedAt: Instant,
	var logoUrl: String? = null,
	var plan: String = "trial",
	var polarSubscriptionId: String? = null,
	var polarCustomerId: String? = null,
	var planUpdatedAt: Instant? = null,
	var entitlementStatus: String = "trialing",
	var accessMode: String = "full",
	var trialStartedAt: Instant,
	var trialEndsAt: Instant,
)
