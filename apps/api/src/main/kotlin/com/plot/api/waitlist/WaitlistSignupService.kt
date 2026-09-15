package com.plot.api.waitlist

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.TransactionExecutor
import com.plot.api.waitlist.dto.WaitlistSignupRequest
import com.plot.api.waitlist.dto.WaitlistSignupResponse
import java.sql.Timestamp
import java.time.Clock
import java.util.Locale
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class WaitlistSignupService(
	private val sqlExecutor: SqlExecutor,
	private val transactionExecutor: TransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun signup(request: WaitlistSignupRequest): WaitlistSignupResponse {
		val website = request.website?.trim().orEmpty()
		if (website.isNotEmpty()) {
			return WaitlistSignupResponse(id = null, duplicate = false)
		}

		val email = requireNotNull(request.email).trim().lowercase(Locale.ROOT)
		val painChannel = requireNotNull(request.painChannel).trim()
		if (painChannel !in PAIN_CHANNELS) {
			throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAIN_CHANNEL", "Choose a valid update channel")
		}
		val role = request.role?.trim()?.takeIf { it.isNotEmpty() }
		if (role != null && role !in ROLES) {
			throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "Choose a valid role")
		}
		val company = request.company?.trim()?.takeIf { it.isNotEmpty() }
		val resendContactId = request.resendContactId?.trim()?.takeIf { it.isNotEmpty() }
		val id = uuidGenerator.next()

		return try {
			transactionExecutor.execute {
				sqlExecutor.update(
					"""
					insert into waitlist_signups (
					  id, email, role, pain_channel, company, resend_contact_id, created_at
					) values (?, ?, ?, ?, ?, ?, ?)
					""".trimIndent(),
					id,
					email,
					role,
					painChannel,
					company,
					resendContactId,
					Timestamp.from(clock.instant()),
				)
				WaitlistSignupResponse(id = id, duplicate = false)
			}
		} catch (_: DataIntegrityViolationException) {
			val existingId = findIdByEmail(email)
			WaitlistSignupResponse(id = existingId, duplicate = true)
		}
	}

	private fun findIdByEmail(email: String): UUID? = sqlExecutor.query(
		"select id from waitlist_signups where email = ? limit 1",
		{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) },
		email,
	).firstOrNull()

	private companion object {
		val PAIN_CHANNELS = setOf("docs", "changelog", "customer_updates", "launch_social", "other")
		val ROLES = setOf("founder", "engineering", "product", "devrel", "other")
	}
}
