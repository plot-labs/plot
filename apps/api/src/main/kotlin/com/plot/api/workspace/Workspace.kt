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
	var publicCitationsEnabled: Boolean = true,
	var plan: String = "none",
	var polarSubscriptionId: String? = null,
	var polarCustomerId: String? = null,
	var polarSubscriptionStatus: String? = null,
	var polarSubscriptionCancelAtPeriodEnd: Boolean? = null,
	var polarSubscriptionCurrentPeriodEnd: Instant? = null,
	var polarSubscriptionEventAt: Instant? = null,
	var planUpdatedAt: Instant? = null,
	var entitlementStatus: String = "subscription_required",
	var accessMode: String = "read_only",
)
