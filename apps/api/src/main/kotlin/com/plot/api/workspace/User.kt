package com.plot.api.workspace

import java.time.Instant
import java.util.UUID

class User(
	var id: UUID,
	var email: String,
	var displayName: String,
	var status: String,
	var authIssuer: String? = null,
	var authSubject: String? = null,
	var createdAt: Instant,
	var updatedAt: Instant,
)
