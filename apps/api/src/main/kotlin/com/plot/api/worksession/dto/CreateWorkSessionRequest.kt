package com.plot.api.worksession.dto

import jakarta.validation.constraints.Size

data class CreateWorkSessionRequest(
	@field:Size(max = 200) val title: String?,
)
