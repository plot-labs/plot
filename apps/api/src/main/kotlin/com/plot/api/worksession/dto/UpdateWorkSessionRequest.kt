package com.plot.api.worksession.dto

import java.util.UUID

data class UpdateWorkSessionRequest(
	val title: String? = null,
	val latestGenerationId: UUID? = null,
)
