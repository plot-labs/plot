package com.plot.api.common

import java.util.UUID

data class WorkspacePrincipal(
	val workspaceId: UUID,
	val userId: UUID,
)
