package com.plot.api.autonomy.signal

import java.time.Instant
import java.util.UUID

/** Provider object identity is scoped by workspace, tenant namespace and source scope. */
data class SignalEnvelope(
	val workspaceId: UUID,
	val sourceNamespaceId: UUID,
	val sourceScopeId: UUID,
	val provider: String,
	val deliveryKey: String,
	val objectKey: String,
	val eventType: String,
	/** Adapter must supply the provider's authoritative object update time, never receipt time. Null means an unordered observation, never an authoritative head update. */
	val sourceVersion: Instant?,
	val payload: String,
	val tombstone: Boolean = false,
	val schemaVersion: Int = 1,
) {
	init {
		require(listOf(provider, deliveryKey, objectKey, eventType).all { it.isNotBlank() })
		require(schemaVersion == 1)
		require(payload.toByteArray(Charsets.UTF_8).size <= 262144)
		require(!tombstone || sourceVersion != null) { "Tombstones require an authoritative version" }
	}
}

data class SignalReceipt(val id: UUID, val inserted: Boolean)
data class SignalClaim(val id: UUID, val token: UUID, val attempt: Int, val envelope: SignalEnvelope)
