package com.plot.api.common

import org.springframework.http.HttpStatus
import java.util.UUID

class ApiException(
	val status: HttpStatus,
	val error: String,
	override val message: String,
	val resourceId: UUID? = null,
) : RuntimeException(message)
