package com.plot.api.worksession.dto

import jakarta.validation.constraints.Size

data class UpdateWorkSessionRequest(
	@field:Size(max = 200) val title: String? = null,
)
