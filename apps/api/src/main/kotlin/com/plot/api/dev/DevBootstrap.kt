package com.plot.api.dev

import com.plot.api.workspace.User
import com.plot.api.workspace.UserRepository
import com.plot.api.workspace.Workspace
import com.plot.api.workspace.WorkspaceMember
import com.plot.api.workspace.WorkspaceMemberRepository
import com.plot.api.workspace.WorkspaceRepository
import java.time.Instant
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class DevBootstrap(
	private val devContext: DevContext,
	private val userRepository: UserRepository,
	private val workspaceRepository: WorkspaceRepository,
	private val workspaceMemberRepository: WorkspaceMemberRepository,
) : ApplicationRunner {

	override fun run(args: ApplicationArguments) {
		run()
	}

	@Transactional
	fun run() {
		val now = Instant.now()

		if (!userRepository.existsById(devContext.devUserId)) {
			userRepository.save(
				User(
					id = devContext.devUserId,
					email = "dev@plot.local",
					displayName = "Dev User",
					status = "ACTIVE",
					createdAt = now,
					updatedAt = now,
				),
			)
		}

		if (!workspaceRepository.existsById(devContext.devWorkspaceId)) {
			workspaceRepository.save(
				Workspace(
					id = devContext.devWorkspaceId,
					name = "Dev Workspace",
					slug = "dev-workspace",
					createdByUserId = devContext.devUserId,
					status = "ACTIVE",
					createdAt = now,
					updatedAt = now,
				),
			)
		}

		if (workspaceMemberRepository.findByWorkspaceIdAndUserId(devContext.devWorkspaceId, devContext.devUserId) == null) {
			workspaceMemberRepository.save(
				WorkspaceMember(
					id = devContext.devWorkspaceMemberId,
					workspaceId = devContext.devWorkspaceId,
					userId = devContext.devUserId,
					role = "OWNER",
					status = "ACTIVE",
					joinedAt = now,
					createdAt = now,
					updatedAt = now,
				),
			)
		}
	}
}
