package com.plot.api.worksession

import com.plot.api.dev.DevContext
import com.plot.api.worksession.dto.WorkSessionResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkSessionService(
	private val devContext: DevContext,
	private val workSessionPersistence: WorkSessionPersistence,
) {

	@Transactional(readOnly = true)
	fun list(): List<WorkSessionResponse> {
		return workSessionPersistence
			.findRecentByWorkspaceId(devContext.devWorkspaceId)
			.map { it.toResponse() }
	}


}
