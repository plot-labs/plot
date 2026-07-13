package com.plot.api.common

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiErrorResponse(
	val error: String,
	val message: String,
	val resourceId: UUID? = null,
)
