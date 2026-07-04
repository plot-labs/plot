package com.plot.api.dev

import com.plot.api.workspace.User
import com.plot.api.workspace.UserRepository
import com.plot.api.workspace.Workspace
import com.plot.api.workspace.WorkspaceMember
import com.plot.api.workspace.WorkspaceMemberRepository
import com.plot.api.workspace.WorkspaceRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DevBootstrapService(
	private val devContext: DevContext,
	private val userRepository: UserRepository,
	private val workspaceRepository: WorkspaceRepository,
	private val workspaceMemberRepository: WorkspaceMemberRepository,
) {

	@Transactional
	fun bootstrap() {
		val now = Instant.now()

		val user = userRepository.findById(devContext.devUserId).orElseGet {
			User(
				id = devContext.devUserId,
				email = "dev@plot.local",
				displayName = "Dev User",
				status = "ACTIVE",
				createdAt = now,
				updatedAt = now,
			)
		}
		user.email = "dev@plot.local"
		user.displayName = "Dev User"
		user.status = "ACTIVE"
		user.updatedAt = now
		userRepository.save(user)

		val workspace = workspaceRepository.findById(devContext.devWorkspaceId).orElseGet {
			Workspace(
				id = devContext.devWorkspaceId,
				name = "Dev Workspace",
				slug = "dev-workspace",
				createdByUserId = devContext.devUserId,
				status = "ACTIVE",
				createdAt = now,
				updatedAt = now,
			)
		}
		workspace.name = "Dev Workspace"
		workspace.slug = "dev-workspace"
		workspace.createdByUserId = devContext.devUserId
		workspace.status = "ACTIVE"
		workspace.updatedAt = now
		workspaceRepository.save(workspace)

		val workspaceMember = workspaceMemberRepository
			.findByWorkspaceIdAndUserId(devContext.devWorkspaceId, devContext.devUserId)
			?: WorkspaceMember(
				id = devContext.devWorkspaceMemberId,
				workspaceId = devContext.devWorkspaceId,
				userId = devContext.devUserId,
				role = "OWNER",
				status = "ACTIVE",
				joinedAt = now,
				createdAt = now,
				updatedAt = now,
			)
		workspaceMember.workspaceId = devContext.devWorkspaceId
		workspaceMember.userId = devContext.devUserId
		workspaceMember.role = "OWNER"
		workspaceMember.status = "ACTIVE"
		workspaceMember.updatedAt = now
		workspaceMemberRepository.save(workspaceMember)
	}
}
