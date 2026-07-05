package com.plot.api.common

import org.springframework.http.HttpStatus

class ApiException(
	val status: HttpStatus,
	val error: String,
	override val message: String,
) : RuntimeException(message)
